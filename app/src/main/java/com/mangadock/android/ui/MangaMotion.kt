package com.mangadock.android.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * v22 动效 token，直接照抄 Material 3 的 easing / duration 规范。
 *
 * 以前全是一句 `tween(170)`：曲线走默认的 FastOutSlowIn，全局只有一种手感，
 * 而且页面切换、阅读器进出、详情页推入用的是同一套曲线 —— 该「快进慢出」的
 * 东西也慢吞吞收尾。
 *
 * **时长刻意不动**（170 / 180 / 190ms 是 v17 压出来的，783 项目录的性能红线
 * 就是按这个跑出来的）。这次只换曲线，观感更「Material」，性能不受影响。
 */
object MangaMotion {

    /** 全局通用：起步快、收尾稳。 */
    val standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** 元素进入：进场后迅速减速停稳（页面推入、阅读器淡入）。 */
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** 元素退出：先加速再消失（页面淡出、弹层关闭）。 */
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** 强调曲线，位移类动画用它（底栏镜片、tab 切换）。 */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /**
     * 卷页专用：**先慢后快再收**。
     *
     * 卷页不能用 emphasizedDecelerate。那条曲线的第一个控制点是 (0.05, 0.7)，
     * 意思是「一开始就已经走完七成」，用在淡入、推入这种「到场即停」的动画上刚好，
     * 用在卷页上就是灾难：实测点一下右侧，t=21ms 时进度 0.63、t=54ms 时 0.88，
     * 剩下的 260ms 只负责把最后那一小条纸角抹平 —— 观感就是「刚点上就跳页了」。
     *
     * 真正的纸是从边缘被手指挑起来的：头 15% 时间几乎不动（纸还没离页），
     * 中段加速翻过去，最后压平减速。所以这里用 y1=0 / x1≈0.34 的 S 形。
     */
    val curl: Easing = CubicBezierEasing(0.34f, 0.0f, 0.16f, 1.0f)

    /** 简单淡入淡出的减速曲线。 */
    val standardDecelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

    /** 简单淡出。 */
    val standardAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)

    // ---- duration token（毫秒），沿用 M3 值域 ----
    const val short2 = 100
    const val short3 = 150
    const val short4 = 200
}
