package com.mangadock.android.ui.prism

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * `navigation_dock.dart:421-666` `ElasticPress` 的 1:1 镜像（按钮点击动画）。
 *
 * 搬的是源码那套行为，不是「按下缩小一点」这种自造反馈：
 *  - 四条 spring：`_lift` / `_hover` / `_pullX` / `_pullY`，
 *    原值 `_pressSpring = withDampingRatio(mass 1, stiffness 311, ratio .593)`、
 *    `_trackSpring = withDurationAndBounce(120ms)`、`_settleSpring = 500ms/.32`。
 *  - `_swell` DOWN 立刻 springTo(1)；`_settle` 要等 `_lift >= .975` 才回落 ——
 *    **快速点按也要先涨满再收**，这是 UIKit tab bar 的手感。
 *  - `_pullToward` 用 `_rubberBand(overshoot, limit)`，limit = `shortestSide * 7/32`。
 *  - `_PressTransform._transform`（`:838-851`）：以 panel 中心为不动点做
 *    swell + 拉伸矩阵，四条参数 `growthOf / pullStretch / center / pull` 全是源码原值。
 *  - `_shine()`（`:985-995`）：glow reach `28*(1+3*spread)`、strength `0.06*g²`、wash `0.7`。
 *  - 明确**没有 ripple**：`ElasticPress.buttonStyle` 用 `NoSplash.splashFactory` +
 *    `overlayColor: transparent`（`:432-438`），按压只由缩放 + 光晕表达。
 *
 * Compose 侧 `spring(dampingRatio, stiffness)` 与 Flutter `withDampingRatio(mass=1,…)`
 * 是同一个阻尼谐振子，直接把源码的 stiffness / ratio 填进去，没有重定标。
 */
@Composable
fun PrismElasticPress(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxGrowthDp: Float = PrismPressSpec.MAX_PRESS_GROWTH,
    pullPastEdges: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val lift = remember { Animatable(0f) }
    val hover = remember { Animatable(0f) }
    val pullX = remember { Animatable(0f) }
    val pullY = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val glowSpread = remember { Animatable(0f) }

    var origin by remember { mutableStateOf(Offset.Zero) }
    var pullTarget by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var glowAt by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(Size.Zero) }

    // `navigation_dock.dart:576-589` —— 拖动时每帧把手指位置转成 rubberBand 后的 pull。
    LaunchedEffect(pullTarget, dragging, sizePx) {
        if (!dragging || sizePx == Size.Zero) return@LaunchedEffect
        val limit = minOf(sizePx.width, sizePx.height) * PrismPressSpec.PULL_LIMIT
        val pull = if (pullPastEdges) {
            Offset(
                pullTarget.x - pullTarget.x.coerceIn(0f, sizePx.width),
                pullTarget.y - pullTarget.y.coerceIn(0f, sizePx.height),
            )
        } else {
            pullTarget - origin
        }
        val track = specOf(PrismSprings.TRACK)
        launch { pullX.animateTo(prismRubberBand(pull.x, limit), track) }
        launch { pullY.animateTo(prismRubberBand(pull.y, limit), track) }
        glowAt = pullTarget
    }

    // `navigation_dock.dart:655` lift = _lift + (1 - _lift) * _hover
    val l = (lift.value + (1f - lift.value) * hover.value).coerceIn(0f, 1.5f)
    val longest = max(sizePx.width, sizePx.height)
    val maxGrowthPx = with(density) { maxGrowthDp.dp.toPx() }
    val swell = 1f + l * PrismPressSpec.growthOf(longest, maxGrowthPx)
    val scaleX = swell * (1f + abs(pullX.value) / max(sizePx.width, 1f) * PrismPressSpec.PULL_STRETCH)
    val scaleY = swell * (1f + abs(pullY.value) / max(sizePx.height, 1f) * PrismPressSpec.PULL_STRETCH)
    val tx = sizePx.width / 2f * (1f - scaleX) + pullX.value
    val ty = sizePx.height / 2f * (1f - scaleY) + pullY.value

    // `_shine()`：按压缩放之外的第二层反馈，光源跟着手指。
    val g = glow.value
    val strength = PrismPressSpec.GLOW_PEAK * g * g
    val reachPx = with(density) {
        (PrismPressSpec.GLOW_REACH * (1f + PrismPressSpec.GLOW_GROWTH * glowSpread.value)).dp.toPx()
    }

    Box(
        modifier
            .onSizeChanged { sizePx = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    origin = down.position
                    dragging = true
                    pullTarget = down.position
                    glowAt = down.position
                    // `_swell` + `_shine` 的进光（navigation_dock.dart:1124-1168）
                    scope.launch { lift.animateTo(1f, specOf(PrismSprings.PRESS)) }
                    scope.launch { glow.animateTo(1f, specOf(PrismSprings.GLOW_IN)) }
                    scope.launch { glowSpread.snapTo(0f) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        pullTarget = ch.position
                        if (!ch.pressed) break
                    }
                    // `_release` / `_settle`：先涨满到 .975 再回落，pull 归零，光晕扩散淡出
                    dragging = false
                    scope.launch {
                        if (lift.value < PrismPressSpec.LIFT_SNAP) {
                            lift.animateTo(1f, specOf(PrismSprings.PRESS))
                        }
                        lift.animateTo(0f, specOf(PrismSprings.PRESS))
                    }
                    val press = specOf(PrismSprings.PRESS)
                    scope.launch { pullX.animateTo(0f, press) }
                    scope.launch { pullY.animateTo(0f, press) }
                    scope.launch { glow.animateTo(0f, specOf(PrismSprings.GLOW_OUT)) }
                    scope.launch { glowSpread.animateTo(1f, specOf(PrismSprings.GLOW_OUT)) }
                }
            }
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
                translationX = tx
                translationY = ty
            }
            .drawWithContent {
                drawContent()
                if (strength > 0.0001f && reachPx > 0f) {
                    val brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = strength),
                            Color.White.copy(alpha = strength * PrismPressSpec.GLOW_WASH),
                            Color.Transparent,
                        ),
                        center = glowAt,
                        radius = reachPx,
                    )
                    drawCircle(brush = brush, radius = reachPx, center = glowAt)
                }
            },
        content = content,
    )
}

/** Flutter `withDampingRatio(mass=1,…)` ↔ Compose `spring(dampingRatio, stiffness)` 同参数。 */
private fun specOf(d: PrismSpringDesc): AnimationSpec<Float> = spring(
    dampingRatio = d.dampingRatio,
    stiffness = d.stiffness,
    visibilityThreshold = 0.0005f,
)
