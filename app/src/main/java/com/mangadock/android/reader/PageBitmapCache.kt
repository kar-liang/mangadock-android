package com.mangadock.android.reader

import android.util.LruCache

/**
 * 解码后的页位图内存缓存（v19）。
 *
 * 三种翻页动画都会让「新的一页」在一个全新的组合节点上出现，而 PageImage 的
 * produceState 默认从 Loading 起步 —— 哪怕这张图刚刚才显示过，也会闪一帧
 * 「第 N 页 · 载入中…」，卷页动画里尤其明显（旧页已经转走了，新页还没上屏）。
 *
 * 这里按「会话 + 页号 + 目标宽」记住最近几张解码好的页，新组合直接拿它当初始状态；
 * 动画期间就只会发生 RenderNode 变换，不会有空白帧。只存内存，清缓存时一起清掉。
 *
 * ## v19.1 的两条纪律（老大反馈「超分过的漫画没以前清晰」）
 *
 * 1. **只缓存全分辨率结果**。[ImageDecoder.Decoded.degraded] 为 true 的（OOM 降档图）
 *    一律不 put —— 否则一次缺内存就永久复用糊图，重启都不一定恢复。
 * 2. **它是进程内唯一持有页位图的地方**，容量 = 堆的 1/8。
 *    以前 [ReaderPageLoader] 还有一份按 URL 的 LRU，目录图片会被两份缓存各算一次，
 *    等于白白多吃一倍内存把进程推到 OOM 边缘；现在那份 LRU 已经去掉，
 *    全部页（CBZ / 目录 / 条漫 / 分页）统一走这里，容量反而比两份加起来更宽裕。
 *    太小的容量（曾经试过 1/16）会让同一页反复重解 —— 实测一页解 6 次，纯浪费。
 */
object PageBitmapCache {

    private val cache: LruCache<String, ImageDecoder.Decoded> by lazy {
        val maxBytes = (Runtime.getRuntime().maxMemory() / 8).toInt()
            .coerceAtLeast(8 * 1024 * 1024)
        object : LruCache<String, ImageDecoder.Decoded>(maxBytes) {
            override fun sizeOf(key: String, value: ImageDecoder.Decoded): Int =
                value.bmp.byteCount
        }
    }

    fun key(scope: String, index: Int, targetW: Int): String = "$scope|$index|$targetW"

    fun get(k: String): ImageDecoder.Decoded? = cache.get(k)

    /** 降档图直接拒绝入缓存，返回 false 让调用方知道没缓存成功。 */
    fun put(k: String, d: ImageDecoder.Decoded): Boolean {
        if (d.degraded) return false
        cache.put(k, d)
        return true
    }

    fun clear() {
        cache.evictAll()
    }
}
