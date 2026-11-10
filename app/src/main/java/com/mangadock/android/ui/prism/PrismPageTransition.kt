package com.mangadock.android.ui.prism

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * FlClash 页面切换动画的 1:1 镜像，全部参数来自：
 *  - `_refs/flclash_ui/home.dart:278-415`（Tab 切换 `_toPage` / `_FadeTabPage`）
 *  - `_refs/flclash_ui/motion.dart:6-7`（fadeTabDuration / fadeTabCurve）
 *  - `_refs/flclash_ui/page_transition.dart:72-231`（SharedXPageTransition / _HandOver）
 *  - `_refs/flclash_ui/navigator.dart:145-222`（CommonRoute / CommonDesktopRoute）
 *  - `_refs/flclash_ui/animated_visibility.dart:5,64-74,138-146`（底栏显隐）
 *
 * **为什么 Tab 切换是纯交叉淡入淡出**：源码用 `PageView` + `_FadeTabPage`，
 * 页面本身随 PageView 横向滑动，再套一层 `FractionalTranslation(Offset(-delta, 0))`
 * **把这份滑动整个抵消掉**，于是页面停在原地，只剩 `Opacity(1 - |delta|)`。
 * Compose 的 `AnimatedContent` 本来就只保留 initial/target 两页（等价于源码
 * `:304-313` 交换 `_order` 避免中间页被构建），所以视觉等价物就是两页
 * **原地交叉淡入淡出 220ms / Curves.easeInOut**，没有任何位移。
 * 之前那版 170/120ms + `it/12` 水平位移是自造的，不在源码里。
 */
object PrismPageTransition {

    // ------------------------------------------------------------ Tab 切换

    /** `home.dart:314-319` 默认 `TabAnimation.fade`：220ms / Curves.easeInOut。 */
    fun fadeTab(): ContentTransform =
        fadeIn(animationSpec = tween(PrismDuration.FADE_TAB, easing = PrismCurves.easeInOut)) togetherWith
            fadeOut(animationSpec = tween(PrismDuration.FADE_TAB, easing = PrismCurves.easeInOut))

    /**
     * v72：Tab 交叉淡入淡出的**单条** spec，给「三页常驻 + 各自 alpha」那套用。
     *
     * 参数与 [fadeTab] 完全一致（还是 220ms / easeInOut），只是不再走
     * `AnimatedContent` 的 ContentTransform —— 那边每切一次页都会把目标页从零组合，
     * 弱机上就是点下去先僵一两百毫秒。淡入淡出的曲线和时长一个字没改。
     */
    fun fadeTabSpec(): FiniteAnimationSpec<Float> =
        tween(PrismDuration.FADE_TAB, easing = PrismCurves.easeInOut)

    /**
     * `home.dart:318` 的 slide 模式：PageView 原生整页滑动 + `Curves.easeOut`。
     * ⚠️ `kTabScrollDuration` 由 `material_ui: ^1.5.0` 提供，FlClash 仓库里**没有定义**，
     * 所以这里不擅自填数值；调用方要启用 slide 模式必须先补齐框架常量。
     * 本项目只用默认的 fade（源码 `config.dart:252-254` 默认值）。
     */
    fun slideTab(durationMs: Int): ContentTransform =
        slideInHorizontally(animationSpec = tween(durationMs, easing = PrismCurves.easeOut)) { it } togetherWith
            slideOutHorizontally(animationSpec = tween(durationMs, easing = PrismCurves.easeOut)) { -it }

    // ------------------------------------------------------------ 路由 push / pop

    /** `page_transition.dart:108` `_fadeInCurve = Interval(0.15, 0.55, curve: easeOutCubic)` */
    private val fadeInCurve: Easing = PrismInterval(0.15f, 0.55f, PrismCurves.easeOutCubic)

    /** `page_transition.dart:109` `_fadeOutCurve = Interval(0, 0.3)`（线性） */
    private val fadeOutCurve: Easing = PrismInterval(0f, 0.30f, LinearEasing)

    /** `page_transition.dart:118` */
    private const val HAND_OVER = 0.2f

    /**
     * `page_transition.dart:217-231` `_HandOver`：
     * leaving = `t < at ? curve(t) : 1`；entering = `t < at ? 0 : curve(t)`。
     * 用作 fadeIn 的 easing 时 alpha = curve(t)；用作 fadeOut 的 easing 时
     * alpha = 1 - curve(t)，正好等于 `_takeOver` / `_handBack` 的 `Tween(1,0).chain(...)`。
     */
    private class HandOver(
        private val curve: Easing,
        private val at: Float = HAND_OVER,
        private val leaving: Boolean,
    ) : Easing {
        override fun transform(fraction: Float): Float = if (leaving) {
            if (fraction < at) curve.transform(fraction) else 1f
        } else {
            if (fraction < at) 0f else curve.transform(fraction)
        }
    }

    /** `page_transition.dart:87` 页面位移是自身宽度的 25%。 */
    private const val PAGE_SLIDE = 0.25f

    private fun sharedXSpec() = tween<IntOffset>(
        durationMillis = PrismDuration.SHARED_X,
        easing = PrismCurves.easeInOutCubicEmphasized,
    )

    private fun fadeSpec(easing: Easing) = tween<Float>(PrismDuration.SHARED_X, easing = easing)

    /**
     * `SharedXPageTransition.build` 的 forward / reverse 两套：
     *
     * push（forward=true）
     *  - 进入页：`enterSlide = _slide(slide, 0)`（0.25→0）+ `_takeOver`
     *    = `_HandOver(_fadeInCurve, at .2, leaving:false)`
     *  - 下层页：`_fadeOut`（Interval(0, .3) 淡出），**不位移**（`uncoverSlide` 属于
     *    secondaryAnimation，非手势时下层保持自己的位置）
     *
     * pop（forward=false）
     *  - 退出页：`exitSlide = _slide(0, slide)`（0→0.25）+ `_handBack`
     *    = `1 - _HandOver(_fadeOutCurve, at .2, leaving:true)`
     *  - 下层页：`_fadeIn`（Interval(.15,.55,easeOutCubic)）重新显出
     */
    fun sharedX(forward: Boolean): ContentTransform = if (forward) {
        (slideInHorizontally(animationSpec = sharedXSpec()) {
            (it * PAGE_SLIDE).roundToInt()
        } + fadeIn(animationSpec = fadeSpec(HandOver(fadeInCurve, leaving = false)))) togetherWith
            fadeOut(animationSpec = fadeSpec(fadeOutCurve))
    } else {
        fadeIn(animationSpec = fadeSpec(fadeInCurve)) togetherWith
            (slideOutHorizontally(animationSpec = sharedXSpec()) {
                (it * PAGE_SLIDE).roundToInt()
            } + fadeOut(animationSpec = fadeSpec(HandOver(fadeOutCurve, leaving = true))))
    }

    /** `navigator.dart:185-191` 桌面路由：纯淡入，200ms，进退同长。 */
    fun desktopRoute(): ContentTransform =
        fadeIn(animationSpec = tween(PrismDuration.DESKTOP_ROUTE)) togetherWith
            fadeOut(animationSpec = tween(PrismDuration.DESKTOP_ROUTE))

    /**
     * `animated_visibility.dart:64-74,138-146` 底栏显隐：
     * 300ms / `Easing.standardDecelerate`，reverse 用 `FlippedCurve(standardAccelerate)`。
     * ⚠️ `Easing.standardDecelerate/Accelerate` 属于 material_ui 框架，仓库无控制点定义，
     * 这里只留接口，参数由调用方注入，不擅自编曲线。
     */
    fun barVisibility(decelerate: Easing, accelerate: Easing): ContentTransform =
        (fadeIn(animationSpec = tween(PrismDuration.ANIMATED_VISIBILITY, easing = decelerate)) +
            slideInHorizontally(animationSpec = tween(PrismDuration.ANIMATED_VISIBILITY, easing = decelerate)) { 0 }) togetherWith
            (fadeOut(animationSpec = tween(PrismDuration.ANIMATED_VISIBILITY, easing = accelerate)) +
                slideOutHorizontally(animationSpec = tween(PrismDuration.ANIMATED_VISIBILITY, easing = accelerate)) { 0 })
}
