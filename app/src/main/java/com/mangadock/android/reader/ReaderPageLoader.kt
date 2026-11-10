package com.mangadock.android.reader

import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 目录图片（非 CBZ）的取字节 + 解码。CBZ 走 [CbzStore]。
 *
 * ## 为什么不在这里做内存缓存（v19.1 改）
 *
 * 以前这里有一个按 URL 的 LruCache，而 UI 层的 [PageBitmapCache] 又会把同一个
 * 位图再存一份 —— 同一张目录图片被两份缓存各算一次内存，等于白白多吃一倍，
 * 长条漫预取几页就把进程推到 OOM 边缘。OOM 会触发放宽降档，然后就被当成
 * 「画质变差了」。
 *
 * 现在内存缓存只在 [PageBitmapCache] 一处，这里只负责网络 + 解码。
 */
object ReaderPageLoader {

    suspend fun load(
        url: String,
        auth: String?,
        targetWidthPx: Int,
        maxPixels: Long = ImageDecoder.MAX_PIXELS
    ): ImageDecoder.Decoded? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .apply { auth?.let { header("Authorization", it) } }
                    .build()

                val bytes = WebDavClient.httpClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes() ?: return@withContext null
                }

                // 降档信息原样带回；要不要入缓存由 PageBitmapCache 自己判断
                ImageDecoder.decodeDetailed(bytes, targetWidthPx, maxPixels)
            } catch (t: Throwable) {
                // OOM / 网络 / 解码失败都吞掉，让 UI 显示"这一页打不开"
                null
            }
        }

    /**
     * 离线下载的散图章节：直接从本地文件读。
     *
     * 不复用 [load] 是因为那条路只认 URL；而下载过的章节**必须**在没有网络时也能读，
     * 走 URL 等于白下。CBZ 不需要这个 —— 本地包交给 CbzStore 的 ZipFile 分支就行。
     */
    suspend fun loadFile(
        file: File,
        targetWidthPx: Int,
        maxPixels: Long = ImageDecoder.MAX_PIXELS
    ): ImageDecoder.Decoded? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.isFile || file.length() == 0L) return@withContext null
                ImageDecoder.decodeDetailed(file.readBytes(), targetWidthPx, maxPixels)
            } catch (t: Throwable) {
                null
            }
        }
}
