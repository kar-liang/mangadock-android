package com.mangadock.android.bookshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import com.mangadock.android.bangumi.BangumiClient
import com.mangadock.android.pages.isImageFile
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.reader.CbzStore
import com.mangadock.android.reader.ImageDecoder
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.webdav.DavResult
import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 书架真实封面：从书本身取第一张图，缩成小 JPEG 落在 `cache/covers`。
 *
 * ## 为什么这么绕
 *
 * 书架是本地收藏，只存路径，不存服务器内容（iOS 1.3.5 的设计）。
 * 所以封面必须**按需**去 WebDAV 取，不能加书时就下载 —— 加书那一刻可能没网，
 * 而且一次加几十本会把 NAS 打爆。这里的策略是：
 *
 * 1. UI 里每个可见卡片各起一个 IO 协程（`produceState`），只管自己那本。
 * 2. 内存 LRU → 磁盘 JPEG → 都没有才生成，命中磁盘时只有一次小图解码。
 * 3. **生成阶段串行**（[generateLock]）：一屏 6 本新书不会同时发 6 个 PROPFIND / Range。
 * 4. 失败写一个 `.miss` 标记，10 分钟内不再重试，免得书架每次重组都去打 NAS。
 *    用户想强制重来，去设置页「清空缓存」即可。
 *
 * 取不到封面就返回 null，UI 回退到原来的首字色块 —— 封面永远是锦上添花，
 * 不能变成书架打不开的理由。
 *
 * ## v25 封面优先级（对齐 iOS `CoverStore`）
 *
 * **自定义 > Bangumi > WebDAV 首图 > 首字色块**
 *
 * - 自定义封面存 `filesDir/custom_covers`（**不是 cacheDir**：用户手选的是数据，
 *   不能被「清空缓存」顺手删掉）；
 * - Bangumi 封面只读「已经匹配过」的本地映射 + 详情缓存，**不在书架上发起搜索**。
 *   搜索交给 [BangumiCoverBackfill] 串行补，避免一进书架就整页打网络；
 * - 网络图（Bangumi）与本地生成图（WebDAV 首图）各用各的 key，互相不覆盖。
 */
object CoverStore {

    private const val TAG = "MangaDock"
    /**
     * v19：书架改 3 列竖版，每列约 103dp / 206px，缩略图目标宽降到 320px（约 1.5x，够清晰）。
     * 尺寸变了就让旧缓存自动作废，不然新旧清晰度混着显示。
     */
    private const val COVER_VERSION = 2
    private const val TARGET_W = 320
    /** 极端长条图别整张存下来。 */
    private const val MAX_H = 1200
    private const val JPEG_QUALITY = 82
    private const val RETRY_AFTER_MS = 10 * 60 * 1000L

    private val ARCHIVE_EXT = setOf("cbz", "zip")

    private val memory: LruCache<String, Bitmap> by lazy {
        val max = (Runtime.getRuntime().maxMemory() / 16).toInt()
            .coerceAtLeast(8 * 1024 * 1024)
        object : LruCache<String, Bitmap>(max) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    /** 一次只生成一张封面。UI 不等它：协程在 IO 线程排队，占位色块照常显示。 */
    private val generateLock = Mutex()

    fun clearMemory() {
        memory.evictAll()
    }

    /**
     * 手动刷新书架时用：清掉「生成失败」的冷却标记，让这次真的重试一遍。
     *
     * 已经生成好的 `.jpg` 不动 —— 那些是有效的图，删了等于让用户白等一轮网络。
     * 只有 Bangumi 那边的封面换了才需要重做，而 Bangumi 封面本来就不看这个缓存。
     */
    fun clearMisses(ctx: Context) {
        runCatching {
            dir(ctx).listFiles { f -> f.name.endsWith(".miss") }?.forEach { it.delete() }
        }
    }

    private fun dir(ctx: Context): File =
        File(ctx.cacheDir, "covers").apply { if (!exists()) mkdirs() }

    /** 换服务器 / 换根路径后旧封面不能复用，所以 key 里带上 base。 */
    private fun keyOf(base: String, path: String): String {
        val md = MessageDigest.getInstance("MD5")
            .digest("$COVER_VERSION|$base|$path".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    private fun files(ctx: Context, base: String, path: String): Pair<File, File> {
        val k = keyOf(base, path)
        val d = dir(ctx)
        return File(d, "$k.jpg") to File(d, "$k.miss")
    }

    /**
     * 给书架卡片用。全程 IO 线程；返回已经降采样、可以直接上屏的 Bitmap。
     */
    suspend fun load(ctx: Context, book: BookItem, settings: AppSettings): Bitmap? =
        withContext(Dispatchers.IO) {
            // ① 自定义封面：用户手选的，最高优先级，且不依赖网络
            val custom = customFile(ctx, book.key)
            if (custom.isFile && custom.length() > 0) {
                val mk = "c|${book.key}"
                memory.get(mk)?.let { return@withContext it }
                val hit = decodeDisk(custom, CUSTOM_W)
                if (hit != null) {
                    memory.put(mk, hit)
                    return@withContext hit
                }
                runCatching { custom.delete() }   // 文件坏了就别一直命中
            }

            // ② Bangumi 正式封面：只读本地已匹配数据，不发搜索
            if (settings.bangumiEnabled) {
                bangumiCover(ctx, book, settings)?.let { return@withContext it }
            }

            val base = settings.webdavUrl
            if (base.isBlank()) return@withContext null
            val ck = keyOf(base, book.key)
            memory.get(ck)?.let { return@withContext it }

            val (jpg, miss) = files(ctx, base, book.key)
            if (jpg.isFile && jpg.length() > 0) {
                val b = decodeDisk(jpg)
                if (b != null) {
                    memory.put(ck, b)
                    return@withContext b
                }
                // 文件坏了就删掉，别一直命中一个解不开的图
                runCatching { jpg.delete() }
            }
            if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < RETRY_AFTER_MS) {
                return@withContext null
            }

            generateLock.withLock<Bitmap?> {
                // 排队期间可能已被前一个任务生成好了
                if (jpg.isFile && jpg.length() > 0) {
                    decodeDisk(jpg)?.let {
                        memory.put(ck, it)
                        return@withLock it
                    }
                }

                // CancellationException 必须原样抛出去：切页动画期间 UI 协程被取消是常事，
                // 用 runCatching 吞掉它会被当成「生成失败」写成 .miss，白白冷却 10 分钟。
                val raw = try {
                    generate(ctx, book, settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "COVER generate failed: ${book.title} — ${t.javaClass.simpleName}")
                    null
                }
                if (raw == null) {
                    runCatching { miss.writeBytes(ByteArray(0)) }
                    Log.i(TAG, "COVER miss: ${book.title}")
                    return@withLock null
                }

                val thumb = shrink(flatten(raw))
                val bytes = ByteArrayOutputStream().use { out ->
                    thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
                atomicWrite(jpg, bytes)
                runCatching { miss.delete() }
                memory.put(ck, thumb)
                Log.i(
                    TAG,
                    "COVER saved: ${book.title} ${thumb.width}x${thumb.height}, ${bytes.size} B"
                )
                return@withLock thumb
            }
        }

    private fun decodeDisk(f: File, targetW: Int = TARGET_W): Bitmap? = runCatching {
        ImageDecoder.decode(f.readBytes(), targetW)
    }.getOrNull()

    // ------------------------------------------------------- 自定义 / Bangumi 封面

    private const val CUSTOM_W = 600
    private const val CUSTOM_H = 900
    private const val CUSTOM_QUALITY = 86

    private fun customDir(ctx: Context): File =
        File(ctx.filesDir, "custom_covers").apply { if (!exists()) mkdirs() }

    /** 用 key（不是 title）命名：改名 / 换排序都不会错位。 */
    private fun customFile(ctx: Context, key: String): File {
        val md = MessageDigest.getInstance("MD5").digest("custom|v1|$key".toByteArray())
        return File(customDir(ctx), md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    fun hasCustom(ctx: Context, key: String): Boolean = customFile(ctx, key).isFile

    /**
     * 用户从相册选一张图当封面：居中裁 2:3 → 600×900 → JPEG。
     * 不走缓存目录，也不受「清空缓存」影响。
     */
    suspend fun setCustom(ctx: Context, book: BookItem, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@withContext false

            val prepared = runCatching { prepareCustom(bytes) }.getOrNull()
                ?: return@withContext false
            val out = ByteArrayOutputStream().use { o ->
                flatten(prepared).compress(Bitmap.CompressFormat.JPEG, CUSTOM_QUALITY, o)
                o.toByteArray()
            }
            val f = customFile(ctx, book.key)
            atomicWrite(f, out)
            memory.remove("c|${book.key}")
            Log.i(TAG, "COVER custom: ${book.title} ${CUSTOM_W}x$CUSTOM_H, ${out.size} B")
            true
        }

    fun removeCustom(ctx: Context, key: String) {
        runCatching { customFile(ctx, key).delete() }
        memory.remove("c|$key")
    }

    /**
     * `android.graphics.ImageDecoder`（API 28+，本项目 minSdk 33）会自动按 EXIF 转正，
     * 不用额外引 ExifInterface。硬件位图不能 `createBitmap` 裁切，所以指定软件分配。
     */
    private fun prepareCustom(bytes: ByteArray): Bitmap {
        val src = android.graphics.ImageDecoder.decodeBitmap(
            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        ) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = false
            // 相册原图可能 8000px 宽，直接整张解会 OOM；先按 2 的幂降到 ~1200px，
            // 对 600×900 的封面绰绰有余。setTargetSampleSize 不会改变画面比例。
            var sample = 1
            while (info.size.width > 0 && info.size.width / (sample * 2) >= 1200) {
                sample *= 2
            }
            d.setTargetSampleSize(sample)
        }
        return centerCrop(src, CUSTOM_W, CUSTOM_H)
    }

    /** 2:3 居中裁切后缩到目标尺寸；不放大，避免糊图被拉得更糊。 */
    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val target = w.toFloat() / h
        val sw = src.width.coerceAtLeast(1)
        val sh = src.height.coerceAtLeast(1)
        val rect = if (sw.toFloat() / sh > target) {
            val nw = (sh * target).roundToInt().coerceAtLeast(1)
            android.graphics.Rect((sw - nw) / 2, 0, (sw + nw) / 2, sh)
        } else {
            val nh = (sw / target).roundToInt().coerceAtLeast(1)
            android.graphics.Rect(0, (sh - nh) / 2, sw, (sh + nh) / 2)
        }
        val cropped = Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
        if (cropped.width == w && cropped.height == h) return cropped
        return Bitmap.createScaledBitmap(cropped, w, h, true)
    }

    /**
     * 书架用的 Bangumi 封面。**只读**：`cachedSubject` 命中映射 + 详情缓存才返回，
     * 不在书架组合时发起搜索。图片本身走 `BangumiClient.image` 的磁盘缓存。
     */
    private suspend fun bangumiCover(
        ctx: Context,
        book: BookItem,
        s: AppSettings
    ): Bitmap? {
        val url = BangumiClient.cachedSubject(ctx, book.path)?.coverUrl ?: return null
        val mk = "b|$url"
        memory.get(mk)?.let { return it }
        val bmp = runCatching {
            BangumiClient.image(ctx, url, s, targetW = TARGET_W)?.asAndroidBitmap()
        }.getOrNull() ?: return null
        memory.put(mk, bmp)
        return bmp
    }

    // ---------------------------------------------------------------- 生成

    private suspend fun generate(ctx: Context, book: BookItem, s: AppSettings): Bitmap? {
        val auth = WebDavClient.authHeader(s.webdavUser, s.webdavPass)
        if (book.isCbz) {
            val url = WebDavClient.fileUrl(s.webdavUrl, book.path) ?: return null
            return firstPageOfCbz(ctx, url, auth)
        }
        return firstImageOfDir(s, ctx, book.path, auth, depth = 0)
    }

    /** CBZ：走和阅读器一样的 Range 随机读，只取第一页，页面字节还会落进单页缓存。 */
    private suspend fun firstPageOfCbz(ctx: Context, url: String, auth: String?): Bitmap? {
        val handle = CbzStore.ensureLocal(ctx, url, auth) ?: return null
        val first = CbzStore.listPages(handle).firstOrNull() ?: return null
        return CbzStore.loadPage(handle, first, TARGET_W)?.bmp
    }

    /**
     * 目录书：优先目录里第一张图片；没有就找第一个 cbz 取它的第一页；
     * 再没有才下探**一层**子目录（有些库是「书名 / 第001话 / 001.jpg」这种结构）。
     * 不递归到底，免得一本收藏把整台 NAS 遍历一遍。
     */
    private suspend fun firstImageOfDir(
        s: AppSettings,
        ctx: Context,
        path: String,
        auth: String?,
        depth: Int
    ): Bitmap? {
        val r = WebDavClient.list(s.webdavUrl, s.webdavUser, s.webdavPass, path)
        if (r !is DavResult.Ok) return null
        val entries = r.entries

        // iOS 端会在库里放 .mangadock-covers / .mangadock-sync.json 这类隐藏项，
        // 按字典序它们永远排第一，不跳过就会钻进一个空目录、白跑一趟还生不出封面。
        val visible = entries.filter { !it.name.startsWith(".") }

        val image = visible.asSequence()
            .filter { !it.isDir && isImageFile(it.name) }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
            .firstOrNull()
        if (image != null) {
            val url = WebDavClient.fileUrl(s.webdavUrl, image.path) ?: return null
            val bytes = fetchBytes(url, auth) ?: return null
            return ImageDecoder.decode(bytes, TARGET_W)
        }

        val archive = visible.asSequence()
            .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXT }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
            .firstOrNull()
        if (archive != null) {
            val url = WebDavClient.fileUrl(s.webdavUrl, archive.path) ?: return null
            return firstPageOfCbz(ctx, url, auth)
        }

        if (depth < 1) {
            val sub = visible.asSequence()
                .filter { it.isDir }
                .sortedWith { a, b -> naturalOrder(a.name, b.name) }
                .firstOrNull() ?: return null
            return firstImageOfDir(s, ctx, sub.path, auth, depth + 1)
        }
        return null
    }

    private fun fetchBytes(url: String, auth: String?): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        WebDavClient.httpClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 位图处理

    private fun shrink(src: Bitmap): Bitmap {
        var scale = TARGET_W.toFloat() / src.width.coerceAtLeast(1)
        val byHeight = MAX_H.toFloat() / src.height.coerceAtLeast(1)
        if (byHeight < scale) scale = byHeight
        if (scale > 1f) scale = 1f   // 原图比缩略图还小就别放大
        if (scale >= 1f) return src
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** JPEG 没有 alpha，PNG 封面直接压会把透明区变黑，先合成到白底。 */
    private fun flatten(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    /** 先写 .part 再改名：生成到一半被杀掉，不会留下一个解不开的 .jpg。 */
    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".part")
        runCatching {
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            tmp.writeBytes(bytes)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }.onFailure { tmp.delete() }
    }
}
