package com.mangadock.android.reader

import android.content.Context
import android.util.Log
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.webdav.RangeResult
import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * CBZ / ZIP 直读（HTTP Range 随机读，iOS 2.0.1 那套）。
 *
 * ## 为什么不再整包下载
 *
 * 一话 21~114 MB，原来要先 `copyTo` 下完整个包才能列目录、显示第一页，实测 8 秒起。
 * ZIP 的**中央目录在文件尾**，所以只要两次请求就能开读：
 *   ① Range 取末尾 64KB → 找 EOCD → 解析中央目录，拿到每页的偏移和压缩长度
 *   ② Range 取「某页 local header + 压缩数据」→ 解出这一页的字节
 * 首屏只剩 64KB + 一页（这本 1.5MB），局域网里一秒以内。
 *
 * ## 句柄
 *
 * 对外仍然返回 [File]（阅读器零改动），但它现在可能是：
 *   - 目录 `cbz/<key>/`：里面 `meta.json`（远程中央目录）、`pages/`（单页缓存），
 *     或 `full.cbz`（Range 不可用时的整包兜底）
 *   - 文件 `cbz/<key>.cbz`：v14 及以前留下的整包缓存，继续直接复用，不重新下载
 *
 * 账号密码只放在内存 [remotes] 里，**绝不写进 meta.json**。每次打开阅读器都会先跑
 * [ensureLocal] 把内存源补回来，所以进程重启也没问题。
 */
object CbzStore {

    private const val TAG = "MangaDock"
    private const val TAIL_BYTES = 65536
    /** local header 的 filename + extra 可能比中央目录长，取页时多带一点富余。 */
    private const val LOCAL_SLACK = 512

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jfif")

    /** 中央目录里的一页。 */
    private data class ZipPage(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val localOffset: Long,
        val nameLen: Int,
        val flag: Int
    )

    /** 远程源。auth 不落盘。 */
    private data class RemoteSource(val url: String, val auth: String?, val pages: List<ZipPage>)

    /** key 是句柄目录的绝对路径。 */
    private val remotes = ConcurrentHashMap<String, RemoteSource>()

    /** 打开失败时给阅读器看的「人话」；取一次就清掉，别污染下一次。 */
    @Volatile
    private var lastOpenError: String? = null

    fun consumeError(): String? {
        val e = lastOpenError
        lastOpenError = null
        return e
    }

    /** Range 走不通的原因。整包兜底成功时由 [ensureLocal] 清掉。 */
    private fun fail(msg: String): List<ZipPage>? {
        lastOpenError = msg
        Log.i(TAG, "CBZ range fallback: $msg")
        return null
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.cacheDir, "cbz").apply { if (!exists()) mkdirs() }

    /** 设置页「清空缓存」时，磁盘删了，进程内的远程源和 ZipFile 句柄也得一起丢。 */
    @Synchronized
    fun invalidate() {
        remotes.clear()
        runCatching { cachedZip?.close() }
        cachedFile = null
        cachedZip = null
    }

    /** 用文件名哈希做 key，避免把整条 URL 当文件名。 */
    private fun keyOf(url: String): String =
        Integer.toHexString(url.hashCode()) + "_" + url.length

    private fun isImage(name: String): Boolean =
        !name.startsWith("__MACOSX/") &&
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    // ---------------------------------------------------------------- 打开

    /**
     * 准备一个 CBZ 句柄：能 Range 就只取尾部元数据，不能就整包下载兜底。
     * 失败返回 null。
     */
    suspend fun ensureLocal(ctx: Context, url: String, auth: String?): File? =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            lastOpenError = null
            val dir = cacheDir(ctx)
            val key = keyOf(url)

            // v14 之前留下的整包缓存，直接用，别让人重新下 21MB
            val legacy = File(dir, "$key.cbz")
            if (legacy.isFile && legacy.length() > 0) {
                Log.i(TAG, "CBZ legacy local: ${legacy.length()} bytes, ${System.currentTimeMillis() - t0}ms")
                return@withContext legacy
            }

            if (!dir.exists()) dir.mkdirs()
            val handle = File(dir, key)
            if (!handle.exists()) handle.mkdirs()
            val metaFile = File(handle, "meta.json")
            val fullFile = File(handle, "full.cbz")

            // 已经有远程元数据：把内存源补回来（进程重启后第一次打开会走这里）
            if (metaFile.isFile) {
                val pages = readMeta(metaFile)
                if (!pages.isNullOrEmpty()) {
                    remotes[handle.absolutePath] = RemoteSource(url, auth, pages)
                    Log.i(TAG, "CBZ meta cached: ${pages.size} pages, ${System.currentTimeMillis() - t0}ms")
                    return@withContext handle
                }
            }
            if (fullFile.isFile && fullFile.length() > 0) {
                Log.i(TAG, "CBZ full cached: ${fullFile.length()} bytes, ${System.currentTimeMillis() - t0}ms")
                return@withContext handle
            }

            // ① 远程模式：只拉尾部，解析中央目录
            val pages = tryOpenRemote(url, auth)
            if (!pages.isNullOrEmpty()) {
                writeMeta(metaFile, pages)
                remotes[handle.absolutePath] = RemoteSource(url, auth, pages)
                Log.i(TAG, "CBZ RANGE open: ${pages.size} pages in ${System.currentTimeMillis() - t0}ms")
                return@withContext handle
            }

            // ② 兜底：服务器不支持 Range / ZIP64 / 中央目录异常 → 老办法整包下载
            if (downloadFull(url, auth, fullFile)) {
                lastOpenError = null   // 整包读成功了，刚才那些 Range 原因就别报给用户
                Log.i(TAG, "CBZ FULL fallback: ${fullFile.length()} bytes in ${System.currentTimeMillis() - t0}ms")
                return@withContext handle
            }
            // 只留第一行的原因，把「正在改用整包读取」这类过程描述换成最终结果，
            // 别让用户以为还在加载。
            val why = lastOpenError?.lineSequence()?.firstOrNull().orEmpty()
            lastOpenError = (if (why.isEmpty()) "" else "$why\n") +
                    "整包读取也没成功：检查服务器地址、账号密码或网络连接。"
            Log.w(TAG, "CBZ open failed: $url — ${why.ifEmpty { "整包读取失败" }}")
            null
        }

    /**
     * 下一话预加载：读到本话末尾前几页时，把下一话的中央目录和第一页提前拉下来。
     * 点「下一话」时就不用再等一次网络往返。失败静默，不影响当前阅读。
     */
    suspend fun prewarm(ctx: Context, url: String, auth: String?) =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = ensureLocal(ctx, url, auth) ?: return@runCatching
                val first = listPages(f).firstOrNull() ?: return@runCatching
                loadPage(f, first, 720)
            }
        }

    /** 尾部找 EOCD → 解析中央目录。任何一步不满足就返回 null 让调用方兜底。 */
    private suspend fun tryOpenRemote(url: String, auth: String?): List<ZipPage>? {
        val t = WebDavClient.fetchTail(url, auth, TAIL_BYTES)
        if (t !is RangeResult.Ok) {
            val why = when (t) {
                is RangeResult.Fail -> t.message
                else -> "服务器忽略了 Range（返回 200 整包）"
            }
            return fail("拿不到压缩包尾部：$why\n正在改用整包读取。")
        }
        val tail = t.bytes
        val total = t.total
        val tailStart = t.start

        val eocd = findEocd(tail)
            ?: return fail("尾部找不到 ZIP 结束记录（EOCD），这个文件可能不是标准 CBZ。")

        // ZIP64 locator 固定贴在 EOCD 前 20 字节。有它就说明偏移是 64 位的，
        // 按 32 位字段读出来的 localOffset 全是错的，必须整包兜底。
        if (eocd >= 20 && u32(tail, eocd - 20) == 0x07064b50L) {
            return fail("这是 ZIP64 压缩包，暂不支持随机读；正在改用整包读取。")
        }

        val entries = u16(tail, eocd + 10)
        val cdSize = u32(tail, eocd + 12)
        val cdOffset = u32(tail, eocd + 16)
        if (entries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            return fail("ZIP64 字段溢出，正在改用整包读取。")
        }
        if (cdSize <= 0L || cdSize > 16L * 1024 * 1024) {
            return fail("中央目录大小异常（$cdSize 字节），正在改用整包读取。")
        }
        if (cdOffset + cdSize > total) {
            return fail("中央目录偏移超出文件大小，压缩包可能已损坏。")
        }

        val cd = if (cdOffset >= tailStart && cdOffset + cdSize <= total) {
            // 绝大多数包：中央目录就在刚拉回来的 64KB 里，一次请求都不用多发
            val off = (cdOffset - tailStart).toInt()
            if (off < 0 || off + cdSize.toInt() > tail.size) {
                return fail("中央目录没落在已取的尾部区间里。")
            }
            tail.copyOfRange(off, off + cdSize.toInt())
        } else {
            // 中央目录比 64KB 尾部窗口还大：按精确偏移单独再取一次
            val r = WebDavClient.fetchRange(url, auth, cdOffset, cdOffset + cdSize - 1)
            if (r !is RangeResult.Ok) {
                val why = (r as? RangeResult.Fail)?.message ?: "服务器忽略 Range"
                return fail("二次读取中央目录失败：$why")
            }
            r.bytes
        }

        val out = ArrayList<ZipPage>(entries)
        var encrypted = 0
        var p = 0
        for (i in 0 until entries) {
            if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) {
                return fail("中央目录结构损坏，正在改用整包读取。")
            }
            val flag = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            val csz = u32(cd, p + 20)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            val localOff = u32(cd, p + 42)
            if (p + 46 + nameLen > cd.size) {
                return fail("中央目录里的文件名被截断，正在改用整包读取。")
            }
            val name = decodeName(cd.copyOfRange(p + 46, p + 46 + nameLen), flag)
            if ((flag and 1) != 0 && isImage(name)) encrypted++
            // 加密、非 stored/deflate 的条目都不碰
            if ((flag and 1) == 0 && (method == 0 || method == 8) && isImage(name) && csz > 0) {
                out += ZipPage(name, method, csz, localOff, nameLen, flag)
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        if (encrypted > 0) {
            return fail("这个压缩包有 $encrypted 个加密页，Android 端暂时打不开。")
        }
        if (out.isEmpty()) return fail("包里没有能读的图片页。")
        return out.sortedWith { a, b ->
            naturalOrder(a.name.substringAfterLast('/'), b.name.substringAfterLast('/'))
        }
    }

    /** EOCD 必须是文件最后一条记录，用 comment 长度反证，避免误命中数据里的签名。 */
    private fun findEocd(b: ByteArray): Int? {
        var i = b.size - 22
        while (i >= 0) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() &&
                b[i + 2] == 0x05.toByte() && b[i + 3] == 0x06.toByte()
            ) {
                val commentLen = u16(b, i + 20)
                if (i + 22 + commentLen == b.size) return i
            }
            i--
        }
        return null
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    /** ZIP 里中文名可能是 UTF-8（flag bit 11）或 GBK。 */
    private fun decodeName(bytes: ByteArray, flag: Int): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if ((flag and 0x800) != 0 || '\uFFFD' !in utf8) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }

    // ---------------------------------------------------------------- 元数据落盘

    private fun writeMeta(f: File, pages: List<ZipPage>) {
        val arr = JSONArray()
        pages.forEach { p ->
            arr.put(JSONObject().apply {
                put("n", p.name)
                put("m", p.method)
                put("c", p.compressedSize)
                put("o", p.localOffset)
                put("l", p.nameLen)
                put("f", p.flag)
            })
        }
        atomicWrite(f, arr.toString().toByteArray())
    }

    private fun readMeta(f: File): List<ZipPage>? = runCatching {
        val arr = JSONArray(f.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ZipPage(
                name = o.getString("n"),
                method = o.getInt("m"),
                compressedSize = o.getLong("c"),
                localOffset = o.getLong("o"),
                nameLen = o.getInt("l"),
                flag = o.optInt("f", 0)
            )
        }
    }.getOrNull()

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".part")
        runCatching {
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            tmp.writeBytes(bytes)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }.onFailure { tmp.delete() }
    }

    // ---------------------------------------------------------------- 取页

    /** 包里的图片条目名，按「文件名」自然序排好（目录层级不影响顺序）。 */
    fun listPages(handle: File): List<String> = runCatching {
        if (handle.isDirectory) {
            val meta = File(handle, "meta.json")
            if (meta.isFile) {
                val pages = readMeta(meta)
                if (!pages.isNullOrEmpty()) return pages.map { it.name }
            }
            return zipNames(File(handle, "full.cbz"))
        }
        zipNames(handle)
    }.getOrDefault(emptyList())

    private fun zipNames(f: File): List<String> = runCatching {
        val zf = open(f) ?: return emptyList()
        Collections.list(zf.entries())
            .asSequence()
            .filter { !it.isDirectory && isImage(it.name) }
            .map { it.name }
            .sortedWith { a, b ->
                naturalOrder(a.substringAfterLast('/'), b.substringAfterLast('/'))
            }
            .toList()
    }.getOrDefault(emptyList())

    fun pageCount(handle: File): Int = listPages(handle).size

    /**
     * 读一页并解码。[targetW] 和图片目录那条路一样，按屏宽降采样。
     * 解压后的原始图片字节会落到 `pages/` 里，滚回来不用再下一遍（iOS 2.2 踩过的坑）。
     */
    suspend fun loadPage(
        handle: File,
        entryName: String,
        targetW: Int,
        maxPixels: Long = ImageDecoder.MAX_PIXELS
    ): ImageDecoder.Decoded? =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = if (handle.isDirectory) remotes[handle.absolutePath] else null
                if (src != null) {
                    val cache = pageCache(handle, entryName)
                    if (cache.isFile && cache.length() > 0) {
                        return@runCatching decode(cache.readBytes(), targetW, maxPixels)
                    }
                    val p = src.pages.firstOrNull { it.name == entryName }
                        ?: return@runCatching null
                    val t0 = System.currentTimeMillis()
                    val bytes = fetchPageBytes(src, p) ?: return@runCatching null
                    Log.i(TAG, "CBZ page $entryName: ${bytes.size} bytes, ${System.currentTimeMillis() - t0}ms")
                    atomicWrite(cache, bytes)
                    return@runCatching decode(bytes, targetW, maxPixels)
                }

                // 整包兜底 / 旧缓存：本地 ZipFile 随机读
                val f = if (handle.isDirectory) File(handle, "full.cbz") else handle
                val zf = open(f) ?: return@runCatching null
                val entry: ZipEntry = zf.getEntry(entryName) ?: return@runCatching null
                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                decode(bytes, targetW, maxPixels)
            }.getOrNull()
        }

    private fun pageCache(handle: File, name: String): File {
        val d = File(handle, "pages").apply { if (!exists()) mkdirs() }
        return File(d, Integer.toHexString(name.hashCode()) + ".img")
    }

    /** 一次 Range 把 local header 和这一页的压缩数据一起取回来。 */
    private suspend fun fetchPageBytes(src: RemoteSource, p: ZipPage): ByteArray? {
        val csz = p.compressedSize
        if (csz <= 0 || csz > 64L * 1024 * 1024) return null
        val end = p.localOffset + 30 + p.nameLen + LOCAL_SLACK + csz
        val r = WebDavClient.fetchRange(src.url, src.auth, p.localOffset, end)
        if (r !is RangeResult.Ok) return null
        val b = r.bytes
        if (b.size < 30 || u32(b, 0) != 0x04034B50L) return null
        val localNameLen = u16(b, 26)
        val localExtraLen = u16(b, 28)
        val from = 30 + localNameLen + localExtraLen
        val to = from + csz.toInt()
        if (from < 30 || from > b.size) return null
        // local header 的 extra 字段偶尔比预留的 512 字节还长。按解析出来的精确
        // 长度再取一次，别让这一页静默变成「打不开」。
        val buf = if (to > b.size) {
            val exactEnd = p.localOffset + to - 1
            val again = WebDavClient.fetchRange(src.url, src.auth, p.localOffset, exactEnd)
            if (again !is RangeResult.Ok || again.bytes.size < to) return null
            again.bytes
        } else {
            b
        }
        val data = buf.copyOfRange(from, to)
        return if (p.method == 0) data else inflateRaw(data)
    }

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inf = Inflater(true)   // raw deflate，ZIP 里没有 zlib 头
        return try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val buf = ByteArray(16 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inf.needsDictionary() || inf.needsInput()) break
            }
            out.toByteArray()
        } finally {
            inf.end()
        }
    }

    // ---------------------------------------------------------------- 整包兜底

    private fun downloadFull(url: String, auth: String?, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        val ok = runCatching {
            val req = Request.Builder()
                .url(url)
                .apply { auth?.let { header("Authorization", it) } }
                .build()
            WebDavClient.httpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
        }.getOrDefault(false)
        if (!ok || tmp.length() <= 0L) {
            tmp.delete()
            return false
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        return true
    }

    // ---- ZipFile 实例缓存：整包兜底模式翻页时别每次重开 ----

    private var cachedFile: File? = null
    private var cachedZip: ZipFile? = null

    @Synchronized
    private fun open(file: File): ZipFile? {
        if (cachedFile == file) cachedZip?.let { return it }
        runCatching { cachedZip?.close() }
        val zf = runCatching { ZipFile(file) }.getOrNull()
        cachedFile = file
        cachedZip = zf
        return zf
    }

    private fun decode(
        bytes: ByteArray,
        targetWidth: Int,
        maxPixels: Long
    ): ImageDecoder.Decoded? = ImageDecoder.decodeDetailed(bytes, targetWidth, maxPixels)
}
