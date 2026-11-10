package com.mangadock.android.komga

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.mangadock.android.reader.ImageDecoder
import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Komga 系列封面。策略和 [com.mangadock.android.bookshelf.CoverStore] 一致，
 * 但**简单得多** —— 封面是 Komga 自己生成的缩略图，一个 GET 就完事，
 * 不用 PROPFIND、不用 Range、不用解 CBZ：
 *
 *   内存 LRU → 磁盘 JPEG → 都没有才发一次请求（然后缩成小图落盘）
 *
 * 取不到就返回 null，卡片回退首字色块。封面永远不该成为「资源库打不开」的理由。
 *
 * key 用完整 URL 的 MD5，其中已经含了 base 和 seriesId，换服务器自然换 key。
 */
object KomgaThumb {

    private const val TARGET_W = 360
    private const val JPEG_QUALITY = 82
    /** 失败后 10 分钟内不再重试，免得一屏 20 张卡片各打一次网络。 */
    private const val RETRY_AFTER_MS = 10 * 60 * 1000L

    private val memory: LruCache<String, Bitmap> by lazy {
        val max = (Runtime.getRuntime().maxMemory() / 24).toInt()
            .coerceAtLeast(4 * 1024 * 1024)
        object : LruCache<String, Bitmap>(max) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    fun clearMemory() = memory.evictAll()

    private fun dir(ctx: Context): File =
        File(ctx.cacheDir, "komga").apply { if (!exists()) mkdirs() }

    private fun keyOf(url: String): String =
        MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    suspend fun load(ctx: Context, url: String, auth: String?): Bitmap? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            memory.get(url)?.let { return@withContext it }

            val k = keyOf(url)
            val d = dir(ctx)
            val img = File(d, "$k.jpg")
            val miss = File(d, "$k.miss")

            // ① 磁盘命中：只有一次小图解码，不碰网络
            if (img.isFile && img.length() > 0) {
                runCatching { img.readBytes() }.getOrNull()
                    ?.let { ImageDecoder.decode(it, TARGET_W) }
                    ?.let { memory.put(url, it); return@withContext it }
                runCatching { img.delete() }
            }

            // ② 刚失败过就别再试
            if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < RETRY_AFTER_MS) {
                return@withContext null
            }

            // ③ 真去取
            val bytes = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .apply { auth?.let { header("Authorization", it) } }
                    .build()
                WebDavClient.httpClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }
            }.getOrNull()

            val bmp = bytes?.let { ImageDecoder.decode(it, TARGET_W) }
            if (bmp == null) {
                runCatching { miss.writeText("1") }
                return@withContext null
            }
            memory.put(url, bmp)
            // 落盘一份小 JPEG：原图可能几百 KB，存缩略图就够书架用
            runCatching {
                img.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (miss.isFile) miss.delete()
            }
            bmp
        }
}
