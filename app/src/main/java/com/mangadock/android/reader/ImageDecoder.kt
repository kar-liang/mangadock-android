package com.mangadock.android.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * 漫画单页解码的唯一入口。目录图片和 CBZ 页都走这里。
 *
 * 两层保护：
 *  1. 按屏幕宽降采样（`inSampleSize` 只会是 2 的幂，所以解出来的宽一定
 *     **落在 [targetWidth, 2×targetWidth)**，不会小于屏幕宽 —— 这条不变，
 *     它就是「清晰度不会低于屏幕」的保证）。
 *  2. **总像素上限**：条漫常见的「宽 1200 × 高 30000」长图，宽度不大不会触发
 *     降采样，但解码出来 3600 万像素 ≈ 144MB，直接 OOM。按总像素再压一次。
 *
 * 真撞上 OutOfMemoryError 时把 sample 翻倍重试，最多三次，还不行就交给 UI
 * 显示「这一页打不开」，而不是把整个进程带崩。
 *
 * ## v19.1 的两个修正（老大反馈「超分过的漫画没以前清晰」）
 *
 * 1. **降档结果不再污染缓存**。以前 OOM 重试把 sample 翻倍后拿到的是一张
 *    糊图，调用方照样把它塞进内存/磁盘缓存 —— 之后哪怕内存宽裕了，只要命中
 *    缓存就一直复用这张糊图，等于「一次缺内存，永久变糊」。现在用
 *    [Decoded.degraded] 标出来，调用方**只缓存全分辨率结果**，降档图本次显示、
 *    下次重新解。
 * 2. **`inScaled = false`**。BitmapFactory 默认会按 inDensity/inTargetDensity
 *    再缩一次；`decodeByteArray` 一般取不到 density，但显式关掉没有代价，
 *    免得某些带 density 的 JPEG 被系统悄悄二次缩小。
 *
 * 另外每条解码都会打一行 `IMG 源尺寸 -> sample -> 实解尺寸`，以后判断
 * 「是不是降质了」直接看 logcat，不用猜。
 */
object ImageDecoder {

    private const val TAG = "MangaDock"

    /** 标准档：16M 像素 ≈ ARGB_8888 64MB。清晰度靠「宽度 ≥ 屏宽」保证。 */
    const val MAX_PIXELS = 16_000_000L

    /** 画质优先档：48M 像素 ≈ 192MB 上限（实际由像素闸和图片本身决定）。 */
    const val HQ_MAX_PIXELS = 48_000_000L

    /**
     * 硬上限：不管宽度够不够，超过这个像素数就必须继续降。
     * 96MB 单页已经是「勉强能解、大概率 OOM」的红线，留个兜底别把进程打死。
     */
    private const val HARD_MAX_PIXELS = 24_000_000L

    private const val MAX_RETRY = 3

    /** 解码结果。带上 sample 和「是否因 OOM 被迫降档」，供调用方决定能不能缓存。 */
    class Decoded(
        val bmp: Bitmap,
        val sample: Int,
        /**
         * true = 这次是 OOM 重试降档解出来的。
         * **这种图不能进任何长期缓存**，否则会变成永久糊图；下次必须重新解一次。
         */
        val degraded: Boolean
    ) {
        val pixels: Int get() = bmp.width.toLong().times(bmp.height).toInt()
    }

    /** 便捷版：只要位图，不在乎档位（封面缩略图这类用）。 */
    fun decode(bytes: ByteArray, targetWidth: Int, maxPixels: Long = MAX_PIXELS): Bitmap? =
        decodeDetailed(bytes, targetWidth, maxPixels)?.bmp

    fun decodeDetailed(
        bytes: ByteArray,
        targetWidth: Int,
        maxPixels: Long = MAX_PIXELS
    ): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val srcW = bounds.outWidth
        val srcH = bounds.outHeight

        // ① 按目标宽降采样。条件用 >=，保证解出来的宽度不小于 targetWidth。
        var sample = 1
        if (targetWidth > 0) {
            while (srcW / (sample * 2) >= targetWidth) sample *= 2
        }
        // ② 长条漫的第二道闸：压总像素，但**不能把宽度压到目标宽以下**。
        //    以前这里无条件翻倍，屏宽 2800 的机器上会把 3200 宽压成 1600（屏宽的 0.57 倍），
        //    超分图直接糊掉。现在只要还没到硬上限，就宁可多占点内存也保住宽度。
        while (pixels(srcW, srcH, sample) > maxPixels) {
            val nextW = srcW / (sample * 2)
            val overHardCap = pixels(srcW, srcH, sample) > HARD_MAX_PIXELS
            if (targetWidth > 0 && nextW < targetWidth && !overHardCap) break
            sample *= 2
        }

        var s = sample
        var degraded = false
        repeat(MAX_RETRY) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = s
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            try {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: return null
                Log.i(
                    TAG,
                    "IMG ${srcW}x$srcH -> sample=$s = ${bmp.width}x${bmp.height}" +
                        " (target=$targetWidth${if (degraded) " OOM-DEGRADED" else ""})"
                )
                return Decoded(bmp, s, degraded)
            } catch (_: OutOfMemoryError) {
                degraded = true
                s *= 2
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        Log.w(TAG, "IMG ${srcW}x$srcH 解码失败：连续 OOM，sample 已到 $s")
        return null
    }

    private fun pixels(w: Int, h: Int, sample: Int): Long =
        (w.toLong() / sample) * (h.toLong() / sample)
}
