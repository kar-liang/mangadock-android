package com.mangadock.android.ui

import androidx.compose.ui.unit.dp

/**
 * v22 间距 token（4dp 网格）。
 *
 * 只有 4 的倍数。新代码一律从这里取，别再写 `12.dp`、`10.dp` 这种散值。
 *
 * ## 为什么没有全局重排
 *
 * 老页面里有一堆 6 / 10 / 14 / 22 dp 的间距，全都逐像素视觉验证过。
 * 一次性把所有间距吸到网格上，收益只有「看着更整齐」，
 * 但会让每个列表行、每张卡片的内边距都动一点 —— 属于高风险低收益。
 * 所以这一版只把**明显不在网格上、且改了不会动布局结构**的地方拉正
 * （书架网格间距 10/14 → 12/16），其余保持原样。
 */
object MangaSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp

    /** 列表卡片左右留白（16dp），全 app 统一。 */
    val pageInset = md

    /** 卡片圆角，M3 的 medium 形状档。 */
    val cardRadius = 12.dp
}
