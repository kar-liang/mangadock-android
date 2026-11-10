package com.mangadock.android.ui.prism

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FlClash 动效底座 —— 全部数值、公式逐值镜像，不自行设计。
 *
 * 源码（已物理搬到 `_refs/flclash_ui/`）：
 *  - `motion.dart`          SpringCurve / riseAndFall / fadeTabDuration / dropletSpring
 *  - `navigation_dock.dart` 常量表 :20-100、_Spring :152-230、_rubberBand/_saturate :102-107
 *  - `page_transition.dart` SharedXPageTransition :72-231
 *  - `popup.dart`           :28-126 全部时长与 spring
 *  - `button.dart`          :66-75 _markSlide / _markFade
 *  - `metrics.dart`         commonDuration / midDuration
 *  - `liquid_glass.dart`    :13-16 glowIn/glowOut
 *  - Flutter 框架：`packages/flutter/lib/src/physics/spring_simulation.dart`
 *    （SpringDescription / SpringSimulation / _CriticalSolution /
 *     _OverdampedSolution / _UnderdampedSolution）与
 *    `packages/flutter/lib/src/animation/curves.dart`（Cubic / Interval / ThreePointCubic）
 *
 * 单位：时间统一秒（Flutter Simulation 的约定），长度统一逻辑像素/dp。
 */

// ---------------------------------------------------------------- SpringDescription

/** flutter/physics/spring_simulation.dart `SpringDescription` 原值镜像。 */
data class PrismSpringDesc(
    val mass: Float,
    val stiffness: Float,
    val damping: Float,
) {
    val dampingRatio: Float get() = damping / (2f * sqrt(mass * stiffness))

    companion object {
        /** dart: `damping = ratio * 2.0 * sqrt(mass * stiffness)` */
        fun withDampingRatio(stiffness: Float, ratio: Float, mass: Float = 1f) =
            PrismSpringDesc(mass, stiffness, ratio * 2f * sqrt(mass * stiffness))

        /** dart: `stiffness = 4*pi^2*m / duration^2`, `zeta = bounce>0 ? 1-bounce : 1/(bounce+1)` */
        fun withDurationAndBounce(durationMs: Int, bounce: Float = 0f): PrismSpringDesc {
            val s = durationMs / 1000f
            val mass = 1f
            val k = 4f * PI.toFloat() * PI.toFloat() * mass / (s * s)
            val zeta = if (bounce > 0f) 1f - bounce else 1f / (bounce + 1f)
            return PrismSpringDesc(mass, k, zeta * 2f * sqrt(mass * k))
        }
    }
}

// ---------------------------------------------------------------- SpringSimulation

/**
 * flutter/physics/spring_simulation.dart `SpringSimulation` + `_SpringSolution` 逐行镜像。
 *
 * factory 判据：`damping^2 - 4*mass*stiffness`，>0 过阻尼 / <0 欠阻尼 / ==0 临界。
 * 三个解的 x/dx 公式与源码同名系数（r1,r2,c1,c2 / w,r,c1,c2）一一对应。
 */
class PrismSpringSim(
    spring: PrismSpringDesc,
    start: Float,
    private val end: Float,
    velocity: Float = 0f,
) {
    private enum class Kind { UNDER, CRITICAL, OVER }

    private val kind: Kind
    private val a: Float
    private val b: Float
    private val c: Float
    private val d: Float

    init {
        val cmk = spring.damping * spring.damping - 4f * spring.mass * spring.stiffness
        val distance = start - end
        when {
            cmk < 0f -> {
                // _UnderdampedSolution
                kind = Kind.UNDER
                a = sqrt(4f * spring.mass * spring.stiffness - spring.damping * spring.damping) /
                    (2f * spring.mass) // w
                b = -(spring.damping / 2f / spring.mass) // r
                c = distance // c1
                d = (velocity - b * distance) / a // c2
            }
            cmk > 0f -> {
                // _OverdampedSolution
                kind = Kind.OVER
                a = (-spring.damping - sqrt(cmk)) / (2f * spring.mass) // r1
                b = (-spring.damping + sqrt(cmk)) / (2f * spring.mass) // r2
                d = (velocity - a * distance) / (b - a) // c2
                c = distance - d // c1
            }
            else -> {
                // _CriticalSolution
                kind = Kind.CRITICAL
                a = -spring.damping / (2f * spring.mass) // r
                b = 0f
                c = distance // c1
                d = velocity - a * distance // c2
            }
        }
    }

    /** 相对 end 的位移（源码 `_solution.x(time)`）。 */
    private fun solutionX(t: Float): Float = when (kind) {
        Kind.UNDER -> exp(b * t) * (c * cos(a * t) + d * sin(a * t))
        Kind.CRITICAL -> (c + d * t) * exp(a * t)
        Kind.OVER -> c * exp(a * t) + d * exp(b * t)
    }

    private fun solutionDx(t: Float): Float = when (kind) {
        Kind.UNDER -> {
            val p = exp(b * t)
            val co = cos(a * t)
            val si = sin(a * t)
            p * (d * a * co - c * a * si) + b * p * (d * si + c * co)
        }
        Kind.CRITICAL -> {
            val p = exp(a * t)
            a * (c + d * t) * p + d * p
        }
        Kind.OVER -> c * a * exp(a * t) + d * b * exp(b * t)
    }

    /** dart: `x(time) = _endPosition + _solution.x(time)` */
    fun x(timeSeconds: Float): Float = end + solutionX(timeSeconds)

    /** dart: `dx(time) = _solution.dx(time)` */
    fun dx(timeSeconds: Float): Float = solutionDx(timeSeconds)

    /** dart: `nearZero(x, tolerance.distance) && nearZero(dx, tolerance.velocity)` */
    fun isDone(timeSeconds: Float, distanceTol: Float = 1e-3f, velocityTol: Float = 1e-3f) =
        abs(solutionX(timeSeconds)) < distanceTol && abs(solutionDx(timeSeconds)) < velocityTol
}

// ---------------------------------------------------------------- motion.dart

/** `motion.dart:11-23` —— 把弹簧的前 [seconds] 秒铺成一条恰好收在 1 的曲线。 */
class PrismSpringCurve(
    spring: PrismSpringDesc,
    private val seconds: Float,
) : Easing {
    private val sim = PrismSpringSim(spring, start = 0f, end = 1f, velocity = 0f)
    private val residual = 1f - sim.x(seconds)

    override fun transform(fraction: Float): Float =
        sim.x(fraction * seconds) + residual * fraction
}

/** `motion.dart:32-38` */
fun prismRiseAndFall(time: Float, peak: Float, sharpness: Float): Float {
    if (time <= 0f) return 0f
    val x = time / peak
    return x.pow(sharpness) * exp(sharpness * (1f - x))
}

/** `navigation_dock.dart:107` */
fun prismSaturate(value: Float): Float = 1f - 2f / (exp(2f * value) + 1f)

/** `navigation_dock.dart:102-105` */
fun prismRubberBand(overshoot: Float, limit: Float): Float {
    val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
    return limit * pull * if (overshoot >= 0f) 1f else -1f
}

/** `curves.dart Interval`：先 clamp 到 [begin,end] 再套曲线，两端保持 0/1。 */
class PrismInterval(
    private val begin: Float,
    private val end: Float,
    private val curve: Easing = LinearEasing,
) : Easing {
    override fun transform(fraction: Float): Float {
        if (fraction == 0f || fraction == 1f) return fraction
        val t = ((fraction - begin) / (end - begin)).coerceIn(0f, 1f)
        if (t == 0f || t == 1f) return t
        return curve.transform(t)
    }
}

/** Flutter `Curves.linear` 的等价物。 */
object LinearEasing : Easing {
    override fun transform(fraction: Float): Float = fraction
}

/**
 * Flutter 框架曲线，控制点原值来自 `packages/flutter/lib/src/animation/curves.dart`。
 * 本仓库（FlClash）不自带框架源码，这些是公开定义，不是估的。
 */
object PrismCurves {
    val easeInOut = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)
    val easeOut = CubicBezierEasing(0.0f, 0.0f, 0.58f, 1.0f)
    val easeIn = CubicBezierEasing(0.42f, 0.0f, 1.0f, 1.0f)
    val easeOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1.0f)
    val easeOutBack = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)
    val easeInBack = CubicBezierEasing(0.6f, -0.28f, 0.735f, 0.045f)
    val dropletGlyph = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f) // popup.dart:87

    /**
     * `Curves.easeInOutCubicEmphasized` = ThreePointCubic，五个 Offset：
     * (0.05,0) (0.133333,0.06) (0.166666,0.4) (0.208333,0.82) (0.25,1)。
     * 按 curves.dart `ThreePointCubic.transformInternal` 的两段 Cubic 逐行镜像。
     */
    val easeInOutCubicEmphasized: Easing = Easing { t ->
        val mx = 0.166666f
        val my = 0.4f
        val first = t < mx
        val sx = if (first) mx else 1f - mx
        val sy = if (first) my else 1f - my
        val st = (t - if (first) 0f else mx) / sx
        if (first) {
            CubicBezierEasing(0.05f / sx, 0f / sy, 0.133333f / sx, 0.06f / sy)
                .transform(st) * sy
        } else {
            CubicBezierEasing(
                (0.208333f - mx) / sx, (0.82f - my) / sy,
                (0.25f - mx) / sx, (1f - my) / sy,
            ).transform(st) * sy + my
        }
    }
}

// ---------------------------------------------------------------- 全部时长常量

/** 时长（毫秒），逐条对应源码常量，别改数值。 */
object PrismDuration {
    const val FADE_TAB = 220              // motion.dart:6
    const val SHARED_X = 450              // page_transition.dart:84
    const val DESKTOP_ROUTE = 200         // navigator.dart:188-191
    const val DRAG_SETTLE = 350           // drag_back.dart:6
    const val ANIMATED_VISIBILITY = 300   // animated_visibility.dart:5
    const val COMMON = 300                // metrics.dart:28
    const val MID = 200                   // metrics.dart:27
    const val POPUP_OPEN = 450            // popup.dart:62
    const val POPUP_DISMISS = 500         // popup.dart:64
    const val POPUP_SHRINK = 200          // popup.dart:66
    const val POPUP_DWELL = 400           // popup.dart:117
    const val POPUP_UNFOLD = 420          // popup.dart:120
    const val POPUP_FOLD = 340            // popup.dart:125
    const val ALERT = 404                 // dialog.dart:31
    const val GLOW_IN = 120               // liquid_glass.dart:14
    const val GLOW_OUT = 360              // liquid_glass.dart:15
    const val FLING_PROJECTION = 100      // navigation_dock.dart:58
}

/** 全部 spring 原值（navigation_dock.dart:59-100 / popup.dart:95-106 / button.dart:70-75）。 */
object PrismSprings {
    val TRACK = PrismSpringDesc.withDurationAndBounce(120)
    val PRESS = PrismSpringDesc.withDampingRatio(311f, 0.593f)
    val FOLLOW = PrismSpringDesc.withDampingRatio(1024f, 0.9f)
    val LIFT = PrismSpringDesc.withDurationAndBounce(250)
    val TRAVEL = PrismSpringDesc.withDurationAndBounce(400, 0.15f)
    val JELLY = PrismSpringDesc.withDampingRatio(100f, 0.4f)
    val SETTLE = PrismSpringDesc.withDurationAndBounce(500, 0.32f)
    val HOVER = PrismSpringDesc.withDurationAndBounce(260, 0.18f)
    val FADE = PrismSpringDesc.withDurationAndBounce(200)
    val GLOW_IN = PrismSpringDesc.withDurationAndBounce(100)
    val GLOW_OUT = PrismSpringDesc.withDurationAndBounce(500)

    val POPUP_OPEN = PrismSpringDesc.withDampingRatio(400f, 0.754f)
    val POPUP_CORNERS = PrismSpringDesc.withDampingRatio(324f, 0.75f)
    val DROPLET = PrismSpringDesc.withDampingRatio(165f, 0.8f)
    val MARK_SLIDE = PrismSpringDesc.withDampingRatio(2350f, 0.95f)
    val MARK_FADE = PrismSpringDesc.withDurationAndBounce(380)
    val SEARCH = PrismSpringDesc.withDampingRatio(390f, 0.75f)
    val SEARCH_CLOSE = PrismSpringDesc.withDampingRatio(600f, 0.75f)
    val SEARCH_MORPH = PrismSpringDesc.withDampingRatio(241f, 0.77f)

    val UNFOLD = PrismSpringDesc.withDurationAndBounce(420, 0.15f)
    val FOLD = PrismSpringDesc.withDurationAndBounce(340)
    val ALERT = PrismSpringDesc.withDurationAndBounce(275)
}

/** `navigation_dock.dart:34-58` 常量原值。 */
object PrismPressSpec {
    const val PRESS_GROWTH = 1f / 8f
    const val MAX_PRESS_GROWTH = 16f
    const val PULL_LIMIT = 7f / 32f
    const val PULL_STRETCH = 0.5f
    const val LIFT_SNAP = 0.975f
    const val LIFT_RELEASE = 0.04f
    const val HOVER_SWELL = 0.4f
    const val HOVER_ALPHA = 0.08f
    const val GLOW_REACH = 28f
    const val GLOW_GROWTH = 3f
    const val GLOW_PEAK = 0.06f
    const val GLOW_WASH = 0.7f

    /** `ElasticPress.growthOf(side)` = `min(_pressGrowth*2, maxGrowth/side)` */
    fun growthOf(sidePx: Float, maxGrowthPx: Float): Float =
        minOf(PRESS_GROWTH * 2f, maxGrowthPx / max(sidePx, 1f))
}
