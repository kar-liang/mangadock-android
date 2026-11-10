package com.mangadock.android.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.mangadock.android.ui.prism.PrismSpringDesc
import com.mangadock.android.ui.prism.PrismSpringSim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * ============================================================================
 * prism_ui `navigation_dock.dart` 的 1:1 Kotlin 镜像（v67：状态机已接通 UI）
 * ============================================================================
 *
 * 原件：_refs/prism_ui/prism_navigation_dock.dart（2139 行）
 * 配套：PrismLiquidGlass.kt（liquid_glass.dart 的 style + frag 的 AGSL）
 * 接线：GlassDock.kt 只认 [PrismDockState] 与 [prismLensRect]，不再有第二套手写动画。
 *
 * 老大 23:01 定调：「直接底栏代码 1:1 搬过来，然后功能映射再慢慢修」。
 * v67 把骨架阶段留下的 14 处 FEATURE_MAP 待办桩全部按源码处理完：
 *  能落地的给实现；Flutter 专有机制（RenderObject / CustomClipper / MouseRegion /
 *  RepaintBoundary / Focus-Actions / Semantics）写明 Compose 等价物与实际落点，
 *  不再留空桩、也不靠截图补近似。全文件已无任何待办标记。
 *
 * Flutter → Compose 映射表
 * ---------------------------------------------------------------------------
 *  ChangeNotifier + Ticker      → PrismSpring（Animatable + mutableStateOf + listeners）
 *  SpringDescription           → PrismSpringDesc 精确换算后 spring(dampingRatio, stiffness)
 *  ValueNotifier<T>            → mutableStateOf<T>
 *  Listenable.merge            → 读多个 state 自动重组
 *  VelocityTracker             → pointerInput 里的 androidx.compose.ui.input.pointer.util.VelocityTracker
 *  Stack(clipBehavior: none)   → Box（默认不裁子项）
 *  Positioned / Positioned.fill→ Modifier.offset / matchParentSize
 *  CustomClipper<Path>         → Shape（Outline.Generic(Path)）
 *  ClipPath(clipper)           → Modifier.clip(shape) / drawScope.clipPath
 *  BackdropFilter / uTexture   → GraphicsLayer.record + RuntimeShader(uContent)
 *  RenderProxyBox.pushTransform→ Modifier.graphicsLayer
 *  pushClipPath 分层染色       → PathOperation.Difference 挖洞（GlassDock.kt:690-706）
 *  RepaintBoundary             → graphicsLayer（自带重绘边界）
 *  MouseRegion / hover         → Android 触摸无 PointerHoverEvent，保留状态与参数但不触发
 *  Focus / Actions / Semantics → Compose 的 focusable / semantics，底栏不启用焦点导航
 *  GestureDetector / Listener  → Modifier.pointerInput(awaitEachGesture)
 *  AppShape.full               → LensSuperellipse（GlassDock.kt）
 * ============================================================================
 */

/* ---------------------------------------------------------------------------
 * 1. 常量（dart:24-62），原值原单位（dp / 无量纲），一律不动
 * ------------------------------------------------------------------------- */
object PrismDockSpec {
    const val BAR_HEIGHT = 62f
    const val BAR_PADDING = 4f
    const val EDGE_MARGIN = 21f
    const val SHADOW_ROOM = 8f
    const val HIT_SLOP = 8f
    /** dart:1185 `_slide` 用的是 Flutter 框架 `kTouchSlop`（18 逻辑像素），不是 `_hitSlop`。 */
    const val TOUCH_SLOP = 18f
    const val MAX_ITEM_EXTENT = 86f
    const val MIN_LENS_WIDTH = 84f
    const val FULL_WIDTH_COUNT = 5
    const val ICON_SIZE = 28f
    const val LABEL_GAP = 2f
    const val LABEL_INSET = 2f
    const val LABEL_SIZE = 10f
    const val LABEL_LEADING = 14.5f
    const val MIN_LABEL_SIZE = 9f

    const val PRESS_GROWTH = 1f / 8f
    const val MAX_PRESS_GROWTH = 16f
    const val LENS_GROWTH = 8f
    const val LENS_MAGNIFY = 0.16f
    const val LIFT_SNAP = 0.975f
    const val LIFT_RELEASE = 0.04f

    const val HOVER_MAGNET = 0.2f
    const val HOVER_PARALLAX = 0.5f
    const val HOVER_ALPHA = 0.08f
    const val HOVER_SWELL = 0.4f

    const val JELLY_SPEED = 3f
    const val JELLY_STRETCH = 0.2f
    const val JELLY_BRAKE = 25f
    const val JELLY_SQUASH = 0.15f
    const val JELLY_ASPECT = 1.4f
    const val JELLY_REST = 3e-3f

    const val GLOW_REACH = 28f
    const val GLOW_GROWTH = 3f
    const val GLOW_PEAK = 0.06f
    const val GLOW_WASH = 0.7f

    const val OVERDRAG = 0.35f
    const val PULL_LIMIT = 7f / 32f
    const val PULL_STRETCH = 0.5f

    /** dart:58 `_flingProjection = Duration(milliseconds: 100)` */
    const val FLING_PROJECTION_MS = 100
}

/* ---------------------------------------------------------------------------
 * 2. Spring（dart:60-104）
 *    v67：不再用「与 duration 对应的近似值」，全部按 Flutter 官方
 *    SpringDescription.withDurationAndBounce / withDampingRatio 的公式精确换算
 *    （PrismMotion.kt 已镜像），再转成 Compose spring(dampingRatio, stiffness)。
 * ------------------------------------------------------------------------- */
private fun specOf(d: PrismSpringDesc): SpringSpec<Float> =
    spring(dampingRatio = d.dampingRatio, stiffness = d.stiffness)

object PrismSprings {
    val track = specOf(PrismSpringDesc.withDurationAndBounce(120))          // dart:60
    val press = specOf(PrismSpringDesc.withDampingRatio(311f, 0.593f))      // dart:63
    val follow = specOf(PrismSpringDesc.withDampingRatio(1024f, 0.9f))     // dart:68
    val lift = specOf(PrismSpringDesc.withDurationAndBounce(250))           // dart:73
    val travel = specOf(PrismSpringDesc.withDurationAndBounce(400, 0.15f))  // dart:77
    val jelly = specOf(PrismSpringDesc.withDampingRatio(100f, 0.4f))        // dart:82
    val settle = specOf(PrismSpringDesc.withDurationAndBounce(500, 0.32f))  // dart:86
    val hover = specOf(PrismSpringDesc.withDurationAndBounce(260, 0.18f))   // dart:91
    val fade = specOf(PrismSpringDesc.withDurationAndBounce(200))           // dart:95
    val glowIn = specOf(PrismSpringDesc.withDurationAndBounce(100))         // dart:98
    val glowOut = specOf(PrismSpringDesc.withDurationAndBounce(500))        // dart:101
}

/**
 * `_Spring`（dart:156-230）的 Compose 映射 —— v69 按源码重写了运行方式。
 *
 * ⚠️ 之前用 `Animatable.animateTo` + 每次 `job.cancel()` 重启动画是**错的**：
 * dart:151-154 的注释就点明了这个坑 ——
 * 「AnimationController.animateWith restarts its ticker, whose first frame reads
 *  no elapsed time, so retargeting it on every pointer move holds the value still
 *  for as long as the finger keeps moving.」
 * 实测慢速拖动 111 帧里镜片完全卡住，只有图标按 pressedIndex 跨项跳变色。
 *
 * 源码的做法：`_ticker` 常驻，`springTo` 只用**当前 value 与 velocity** 建一条新
 * `SpringSimulation`（`_start = _now`），ticker 不重启；`_tick` 每帧读 `x(time)`。
 * 这里用 `withFrameNanos` 循环当 ticker，`PrismSpringSim` 当 SpringSimulation，
 * 一一对应：_value / _target / _simulation / _spring / _start / _now 全都有。
 */
class PrismSpring(
    initial: Float,
    private val scope: CoroutineScope,
    val rest: Float = 1e-3f,
) {
    private var job: Job? = null
    private var sim: PrismSpringSim? = null
    private var currentSpec: SpringSpec<Float>? = null
    /** 当前 simulation 建立时的绝对时间，等价源码 `_start`。 */
    private var startNanos = 0L
    private val listeners = mutableListOf<() -> Unit>()

    var value: Float by mutableStateOf(initial)
        private set
    var target: Float by mutableStateOf(initial)
        private set

    private fun seconds(): Float =
        ((System.nanoTime() - startNanos) / 1_000_000_000f).coerceAtLeast(0f)

    /** dart:177 `_simulation?.dx(_now - _start) ?? 0` */
    val velocity: Float get() = sim?.dx(seconds()) ?: 0f

    /** dart:178-183 `(k*(target-value) - c*velocity) / m`，m=1。 */
    val acceleration: Float
        get() {
            val s = currentSpec ?: return 0f
            val mass = 1f
            val damping = s.dampingRatio * 2f * sqrt(mass * s.stiffness)
            return (s.stiffness * (target - value) - damping * velocity) / mass
        }

    fun addListener(l: () -> Unit) { listeners += l }

    /** SpringSpec -> Flutter SpringDescription（mass 固定 1）。 */
    private fun descOf(spec: SpringSpec<Float>): PrismSpringDesc {
        val mass = 1f
        val k = spec.stiffness
        return PrismSpringDesc(mass, k, spec.dampingRatio * 2f * sqrt(mass * k))
    }

    /**
     * dart:185-204 `springTo`：换 simulation。
     *
     * v74：ticker 的运行方式改成「一次 springTo 对应一条 job」。
     * 旧写法是 `if (job?.isActive == true) return`（模仿 Flutter 的常驻 ticker），
     * 但这里的 job 在 isDone 那一帧会把 sim 置 null 并退出循环 —— 存在一个
     * 「job 仍 isActive、马上就要收尾」的窗口。此时 listener 里的 dropLift() /
     * updateSelected() 调 springTo，只换了 sim 却没人再驱动它：
     *   · lift 卡在 1.0 → 松手后气泡一直鼓着不回缩
     *   · lens 卡在旧 index → 页面在「文件」、气泡却停在「设置」
     * Compose 不能照搬 Flutter 的常驻 ticker（那会每帧请求绘制，破「静止 0 帧」），
     * 所以改成：每次重定向都 cancel 旧 job、起一条新 job，并用 sim 身份校验
     * 挡住旧回调踩新状态。sim 用绝对 startNanos + 当前 value 建立，
     * 重启 job 不会像 v69 那版 `animateTo` 一样把值卡在第一帧。
     */
    fun springTo(target: Float, spec: SpringSpec<Float>) {
        val carriedVelocity = velocity   // 必须在替换 sim 之前读，等价源码的入参 velocity
        this.target = target
        currentSpec = spec
        val simulation = PrismSpringSim(descOf(spec), value, target, carriedVelocity)
        sim = simulation
        startNanos = System.nanoTime()   // _start = _now
        job?.cancel()
        job = scope.launch {
            val me = coroutineContext[Job]
            while (isActive) {
                withFrameNanos {
                    val s = sim ?: return@withFrameNanos
                    // 已被更新的 springTo 接管：旧回调不许再写 value / 清 sim
                    if (s !== simulation) return@withFrameNanos
                    val t = seconds()
                    val specNow = currentSpec
                    val velocityTol = if (specNow != null) rest * sqrt(specNow.stiffness) else rest
                    if (s.isDone(t, rest, velocityTol)) {
                        // dart:218-222 isDone -> _value = _target; _simulation = null; ticker.stop()
                        value = target
                        sim = null
                        currentSpec = null
                    } else {
                        value = s.x(t)
                    }
                    listeners.forEach { it() }
                }
                // listeners 里可能又 springTo 了新 sim（新 job 已接管），本 job 退出
                if (sim == null || sim !== simulation) break
            }
            if (job === me) job = null
        }
    }

    /**
     * dart:206-211 `jumpTo`：停 simulation 与 ticker，直接落值。
     * [notify] = false 用于拖动直通：源码 jumpTo 会 notifyListeners，但拖动时
     * 那会连锁 `wobble -> jelly.springTo -> 又一條幀循環 + 額外重組`，v70 刻意跳過。
     */
    fun jumpTo(v: Float, notify: Boolean = true) {
        job?.cancel()
        job = null
        sim = null
        currentSpec = null
        target = v
        value = v
        if (notify) listeners.forEach { it() }
    }

    fun dispose() {
        job?.cancel()
        job = null
        sim = null
        currentSpec = null
    }
}

/* ---------------------------------------------------------------------------
 * 3. 纯函数（dart:104-154）—— 全部 1:1，无平台依赖
 * ------------------------------------------------------------------------- */

/**
 * dart:104-108 `_rubberBand(overshoot, limit)`。
 * 骨架阶段误写成 `limit * overshoot * sign`，那不是源码；已按原式修正。
 */
fun prismRubberBand(overshoot: Float, limit: Float): Float {
    val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
    return limit * pull * overshoot.sign
}

/** dart:110 `_saturate` */
fun prismSaturate(value: Float): Float =
    1f - 2f / (exp(2f * value) + 1f)

/** dart:114-131 `_lensRect`：气泡的全部几何都在这里，别再另写一套 */
fun prismLensRect(
    position: Float,
    stretch: Float,
    pitch: Float,
    width: Float,
    height: Float,
    lift: Float,
): Rect {
    val growth = PrismDockSpec.LENS_GROWTH * 2f * lift
    val lensWidth = (width + growth) * (1f + stretch)
    val lensHeight = (height + growth) * (1f - stretch * PrismDockSpec.JELLY_ASPECT)
    return Rect(
        left = (position + 0.5f) * pitch - lensWidth / 2f,
        top = (height - lensHeight) / 2f,
        right = (position + 0.5f) * pitch + lensWidth / 2f,
        bottom = (height + lensHeight) / 2f,
    )
}

/** dart:133-149 `_dockLayout` */
data class PrismDockLayout(val pitch: Float, val lens: Float, val inset: Float)

fun prismDockLayout(span: Float, count: Int): PrismDockLayout {
    val pad = PrismDockSpec.BAR_PADDING
    if (count <= 1) return PrismDockLayout(span - pad * 2, span, pad)
    val pitch = (span - pad * 2) / count
    if (pitch + pad * 2 >= PrismDockSpec.MIN_LENS_WIDTH) {
        return PrismDockLayout(pitch, pitch + pad * 2, pad)
    }
    val lens = min(PrismDockSpec.MIN_LENS_WIDTH, span)
    val closed = (span - lens) / (count - 1)
    return PrismDockLayout(closed, lens, (lens - closed) / 2f)
}

/** dart:450 `ElasticPress.growthOf` = `min(_pressGrowth*2, maxGrowth/side)`（返回比例，不是 dp 长度）。 */
fun prismPressGrowth(side: Float, maxGrowth: Float = PrismDockSpec.MAX_PRESS_GROWTH): Float =
    min(PrismDockSpec.PRESS_GROWTH * 2f, maxGrowth / max(side, 1f))

/** dart:1046-1050 `_positionAt` 两端的 overdrag，用原版 rubberBand 而非线性 0.35 倍。 */
fun prismOverdrag(position: Float, lastIndex: Float): Float {
    if (position < 0f) return prismRubberBand(position, PrismDockSpec.OVERDRAG)
    if (position > lastIndex) {
        return lastIndex + prismRubberBand(position - lastIndex, PrismDockSpec.OVERDRAG)
    }
    return position
}

/** dart:1042-1050 `_positionAt` */
fun prismPositionAt(dx: Float, span: Float, count: Int): Float {
    val layout = prismDockLayout(span - PrismDockSpec.BAR_PADDING * 2, count)
    val raw = (dx - PrismDockSpec.BAR_PADDING - layout.inset) / layout.pitch - 0.5f
    return prismOverdrag(raw, (count - 1).toFloat())
}

/** dart:1052 `_indexAt` */
fun prismIndexAt(position: Float, count: Int): Int =
    position.roundToIntCompat().coerceIn(0, count - 1)

/** dart:1054-1057 `_leanAt` */
fun prismLeanAt(position: Float, count: Int): Float {
    val index = prismIndexAt(position, count)
    return index + (position - index) * PrismDockSpec.HOVER_MAGNET
}

private fun Float.roundToIntCompat(): Int =
    if (this >= 0f) (this + 0.5f).toInt() else (this - 0.5f).toInt()

/* ---------------------------------------------------------------------------
 * 4. 类镜像（dart 顶层声明按原顺序）—— 14 处 TODO 全部落地
 * ------------------------------------------------------------------------- */

/** dart:304 `NavigationDockDestination` */
data class PrismDestination(
    val icon: String,
    val label: String,
    val selectedIcon: String = icon,
)

/** dart:323-333 `NavigationDock.heightOf / _bottomMarginOf / insetOf` */
object PrismDockMetrics {
    fun heightOf(shadowRoom: Float = PrismDockSpec.SHADOW_ROOM) =
        PrismDockSpec.BAR_HEIGHT + shadowRoom
    fun bottomMarginOf(systemInset: Float) = max(PrismDockSpec.EDGE_MARGIN, systemInset)
    fun insetOf() = PrismDockSpec.BAR_HEIGHT + PrismDockSpec.SHADOW_ROOM
}

/**
 * dart:254 `_DockSurface`：Surface.transparent —— 底栏自己不铺 colorScheme 底色，
 * 底下内容直接透过玻璃。Compose 等价物是 `GlassSurface` 的透明 tint + 内容层采样
 * （GlassDock.kt:428-478），不需要额外 Widget，这里只保留「不画底色」的语义常量。
 */
object PrismDockSurface {
    /** 恒为 true：任何模式下都不允许在底栏下铺不透明 surface。 */
    const val transparent = true
}

/**
 * dart:386 `_HitSlop` / 393 `_RenderHitSlop`：把命中区向外扩 8dp。
 * Compose 等价物是 `pointerInput` 里读 `viewConfiguration.touchSlop` 并在 DOWN 时
 * 允许落在胶囊外 8dp 内（GlassDock.kt 的 positionAt 已按 _positionAt 覆盖整条）。
 * 这里给出源码里那条扩展后的矩形，供调用方做 bounds 判定。
 */
object PrismHitSlop {
    fun expanded(size: Size): Rect = Rect(
        -PrismDockSpec.HIT_SLOP, -PrismDockSpec.HIT_SLOP,
        size.width + PrismDockSpec.HIT_SLOP, size.height + PrismDockSpec.HIT_SLOP,
    )
}

/**
 * dart:425 `ElasticPress` / 504 `ElasticPressHandle` / 514 `_ElasticPressState`。
 * 整套行为已 1:1 落在 `ui/prism/PrismElasticPress.kt`（v65：四条 spring、_settle
 * 等 lift≥.975 才回落、rubberBand、glow、NoSplash）。顶栏按钮与 v66 弹窗都在用。
 * 底栏的整条 swell 由 [PrismDockState.swell] 与 [PrismPressTransform] 承担，
 * 不重复包一层 Composable，避免 pointerInput 互相抢事件。
 */
object PrismDockElasticPress {
    fun growthOf(side: Float, maxGrowth: Float = PrismDockSpec.MAX_PRESS_GROWTH) =
        prismPressGrowth(side, maxGrowth)
}

/**
 * dart:666 `ElasticHover` / 675 `_ElasticHoverState`：MouseRegion 的 enter/exit。
 * Android 触摸没有 PointerHoverEvent（源码 `_handleHover` 也显式忽略 touch kind），
 * 所以这里只保留 hover 状态机（[PrismDockState.showHover] / [PrismDockState.leanContent]）
 * 与 magnet/parallax 参数，不接 MouseRegion。桌面/外接鼠标场景可直接调 showHover。
 */
object PrismElasticHover {
    const val magnet = PrismDockSpec.HOVER_MAGNET
    const val parallax = PrismDockSpec.HOVER_PARALLAX
}

/**
 * dart:710 `ElasticButton`：把按压交给 ElasticPress，同时把按钮自己的
 * pressLook 设成 none。Compose 等价物就是 `PrismElasticPress` + `indication = null`
 * （MainActivity.kt:1442 TopGlassButton 已经是这个组合），无需第二个类。
 */
object PrismElasticButton

/**
 * dart:734 `_PressTransform` / 771 `_RenderPressTransform`：
 * `_transform` 是「以 panel 中心为不动点的 swell + pull 拉伸矩阵」。
 * Compose 的 `Modifier.graphicsLayer` 就是 pushTransform，这里按原值算出四个分量，
 * 供 UI 直接填进 graphicsLayer，不再另写一套缩放公式。
 */
data class PrismPressTransform(
    val lift: Float = 0f,
    val pull: Offset = Offset.Zero,
    val maxGrowth: Float = PrismDockSpec.MAX_PRESS_GROWTH,
) {
    data class Result(val scaleX: Float, val scaleY: Float, val tx: Float, val ty: Float)

    fun transform(panel: Size, longestSide: Float = max(panel.width, panel.height)): Result {
        val swell = 1f + lift * prismPressGrowth(longestSide, maxGrowth)
        val scaleX = swell * (1f + abs(pull.x) / max(panel.width, 1f) * PrismDockSpec.PULL_STRETCH)
        val scaleY = swell * (1f + abs(pull.y) / max(panel.height, 1f) * PrismDockSpec.PULL_STRETCH)
        return Result(
            scaleX, scaleY,
            panel.width / 2f * (1f - scaleX) + pull.x,
            panel.height / 2f * (1f - scaleY) + pull.y,
        )
    }
}

/**
 * dart:1413-1435 `_HoverHighlight`：alpha = _hoverAlpha * opacity，
 * 一个 AppShape.full 胶囊形的 onSurface 色块。Compose 侧按需用这个 alpha 画
 * RoundedCornerShape(percent=50) 的 Box 即可（桌面 hover 才可见）。
 */
data class PrismHoverHighlight(val start: Float, val width: Float, val opacity: Float) {
    val alpha: Float get() = PrismDockSpec.HOVER_ALPHA * opacity.coerceIn(0f, 1f)
    val visible: Boolean get() = alpha > 0f
}

/** dart:1446 `_Lens`：platter 按 (1 - lifted) 淡出 */
data class PrismLensPlatter(val rect: Rect, val lift: Float, val glass: Boolean)

/** dart:1489 `_GlassLens`：lift>0 才画，style = lens().scale(lift) */
data class PrismGlassLens(val rect: Rect, val lift: Float) {
    val visible: Boolean get() = lift > 0f
}

/** dart:1517 `_lensShadows`：blur 5 / offset(0,8) / alpha .055*strength*lift */
fun prismLensShadowAlpha(lift: Float, dark: Boolean): Float =
    0.055f * (if (dark) 3f else 1f) * lift.coerceIn(0f, 1f)

/**
 * dart:1533 `_LensContent` / 1590 `_LensClip`：
 * 图标层按镜片胶囊裁成 inside / outside 两份，inside 上再压一块
 * `LiquidGlassStyle.lensContent` 玻璃。Compose 等价实现已在
 * GlassDock.kt:679-777（`iconLayer.record` + `PathOperation.Difference` 挖 outside +
 * 第二块 lensContent shader 面板）。这里只保留源码的数据模型与 warp 判定。
 */
data class PrismLensContent(val rect: Rect, val lift: Float, val warp: Boolean) {
    /** warp 只在抬起时生效，静止态整行原样画。 */
    val active: Boolean get() = warp && lift > 0.01f
}

/** dart:1613 `_LensPart` */
enum class PrismLensPart { BOTH, OUTSIDE, INSIDE }

/**
 * dart:1615 `_FloatingBarItem` / 1653 `_FloatingBarItemState`：
 *  - parallax：由 [PrismDockState.leanContent] 按 _hoverMagnet*_hoverParallax 逐项 spring；
 *  - jelly：由 [PrismDockState.wobble] 驱动，几何走 [prismLensRect] 的 stretch；
 *  - label overflow：Compose 侧 Text(maxLines = 1, overflow = Ellipsis)；
 *  - _LensTint 的 inside/outside 分层染色见下。
 */
data class PrismBarItem(
    val index: Int,
    val selected: Boolean,
    val part: PrismLensPart,
    val labelStyleScale: Float = 1f,
) {
    /** dart:1663-1672 inside 且 standard quality 时，tint 按 lensContent.brightness 提亮。 */
    fun tintInside(primary: FloatArray, brightness: Float): FloatArray =
        if (part == PrismLensPart.INSIDE) FloatArray(3) {
            min(primary[it] * brightness, 1f)
        } else primary
}

/**
 * dart:1754 `_LensTint` / 1808 `_RenderLensTint` / 2055-2081 `paint`：
 * outside = evenOdd(整块 reach, 镜片 path) 挖洞后画普通墨色图标；
 * inside = clip 到镜片 path 再用 `ColorFilter.mode(tint, BlendMode.srcIn)` 整体染色。
 * Compose 的等价物已在 GlassDock.kt:690-706（PathOperation.Difference 挖 outside）
 * 与 711-721（按 visualIdx 整项换 accent）。**刻意不搬竖切染色**：
 * v51 实测半途拖动时中文标签会被竖着切成左黑右紫，在纯色矢量图标上像渲染错误；
 * 这一点在交接记录里是明确记过的功能映射取舍，不是没实现。
 */
object PrismLensTint {
    const val outsideUsesEvenOdd = true
    const val insideBlend = "srcIn"
}

/**
 * dart:2115-2139 `_FilledBoundary`：页面 zoom 过去时用 `BarItemPresence.filledOf`
 * 把底栏整块藏起来，只留裸玻璃。Compose 等价物是
 * `PrismPageTransition.barVisibility`（animated_visibility.dart 的 300ms 显隐），
 * 由路由层控制，不在底栏内部再套 RepaintBoundary。
 */
object PrismFilledBoundary {
    const val maintainState = true
    const val maintainAnimation = true
    const val maintainSize = true
}

/** dart:221 `dockShadows` */
data class PrismDockShadow(val alpha: Float, val blurRadius: Float, val offsetY: Float)

fun prismDockShadows(dark: Boolean): List<PrismDockShadow> {
    val strength = if (dark) 3f else 1f
    return listOf(
        PrismDockShadow(0.08f * strength, 24f, 8f),
        PrismDockShadow(0.04f * strength, 3f, 1f),
    )
}

/** dart:986-996 `_shine()` 产出的 LiquidGlassGlow 四个参数。 */
data class PrismTouchGlow(
    val center: Offset = Offset.Zero,
    val reach: Float = 0f,
    val strength: Float = 0f,
    val wash: Float = PrismDockSpec.GLOW_WASH,
)

/* ---------------------------------------------------------------------------
 * 5. 状态机（dart:890-1260）—— v67 全部接通，GlassDock 的唯一动画来源
 * ------------------------------------------------------------------------- */
class PrismDockState(
    private val scope: CoroutineScope,
    selectedIndex: Int,
    val destinationCount: Int,
) {
    val lastIndex: Int get() = max(0, destinationCount - 1)

    // dart:891-900
    val lens = PrismSpring(selectedIndex.toFloat(), scope)
    val lift = PrismSpring(0f, scope)
    val hover = PrismSpring(0f, scope)
    val hoverShow = PrismSpring(0f, scope)
    val swell = PrismSpring(0f, scope)
    val stretch = PrismSpring(0f, scope)
    val jelly = PrismSpring(0f, scope, rest = PrismDockSpec.JELLY_REST)
    val glow = PrismSpring(0f, scope)
    val glowSpread = PrismSpring(0f, scope)
    val parallax: List<PrismSpring> = List(destinationCount) { PrismSpring(0f, scope) }

    // dart:901-916
    var selectedIndex by mutableStateOf(selectedIndex.coerceIn(0, lastIndex))
        private set
    var lifted by mutableStateOf(false)
        private set
    var pointerId: Long? by mutableStateOf(null)
        private set
    var pressedIndex: Int? by mutableStateOf(null)
        private set
    var pressX by mutableStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var dropping by mutableStateOf(false)
        private set
    var cursor: Offset? by mutableStateOf(null)
        private set
    var hoverAt: Float? by mutableStateOf(null)
        private set
    // v70：拖动时每帧写 glowAt 会多触发一次重组，而 glow 强度在拖动期间恒定、
    // 只有 DOWN/UP 的 shine() 用到它。改成普通字段，不参与快照。
    var glowAt: Offset = Offset.Zero
        private set
    var touchGlow by mutableStateOf(PrismTouchGlow())
        private set

    /**
     * v85：气泡跨到另一个 tab 时的轻触感回调，由 GlassDock 注入。
     * 对应 prism_ui `_slide` / `_fling` 里的 `HapticFeedback.selectionClick()`；
     * 只在 pressedIndex 真的变化时调用，初次按下（press）不触发。
     */
    var onCrossTab: (() -> Unit)? = null

    init {
        // dart:initState 的六条 listener 绑定，一条不少
        lens.addListener(::wobble)
        lens.addListener(::dropLift)
        lift.addListener(::dropLift)
        lift.addListener(::noteLift)
        glow.addListener(::shine)
        glowSpread.addListener(::shine)
    }

    // ------------------------------------------------------------ 源码方法

    /** dart:973-983 `_wobble`：stretch 减去 braking 的 squash。 */
    fun wobble() {
        // v70：拖动直通期间不跑果冻弹簧 —— 否则 lens 每帧跳一次就会连锁
        // jelly.springTo 再起一条帧循环 + 一次额外重组。松手后自动恢复。
        if (dragging) return
        val velocity = lens.velocity
        val braking = max(0f, -lens.acceleration * velocity.sign)
        val target = PrismDockSpec.JELLY_STRETCH *
            prismSaturate(abs(velocity) / PrismDockSpec.JELLY_SPEED) -
            PrismDockSpec.JELLY_SQUASH * prismSaturate(braking / PrismDockSpec.JELLY_BRAKE)
        // v70：就地落值，不再 springTo 起第二条帧循环。v65 的果冻本来就是
        // `sin(π·t/0.72)` 的直接函数，不是弹簧；这样松手后也只剩 lens 一条循环。
        if (target != jelly.target) jelly.jumpTo(target, notify = false)
    }

    /** dart:986-996 `_shine` */
    fun shine() {
        val g = glow.value
        touchGlow = PrismTouchGlow(
            center = glowAt,
            reach = PrismDockSpec.GLOW_REACH *
                (1f + PrismDockSpec.GLOW_GROWTH * glowSpread.value),
            strength = PrismDockSpec.GLOW_PEAK * g * g,
        )
    }

    /** dart:1000-1010 `_leanContent`：逐项 parallax。 */
    fun leanContent() {
        val at = hoverAt
        parallax.forEachIndexed { index, spring ->
            val offset = if (at == null) 0f else at - index
            val target = if (abs(offset) > 0.5f) 0f
            else offset * PrismDockSpec.HOVER_MAGNET * PrismDockSpec.HOVER_PARALLAX
            if (target != spring.target) spring.springTo(target, PrismSprings.hover)
        }
    }

    /** dart:1019-1025 `_noteLift` */
    fun noteLift() {
        val now = lift.value > 0f
        if (now != lifted) lifted = now
    }

    /** dart:1027-1036 `_dropLift`：抬满且镜片到位才回落。 */
    fun dropLift() {
        if (!dropping || pointerId != null || lift.value < PrismDockSpec.LIFT_SNAP) return
        if (abs(lens.target - lens.value) > PrismDockSpec.LIFT_RELEASE) return
        dropping = false
        lift.springTo(0f, PrismSprings.lift)
    }

    /** dart:1061-1068 `_lensRest` */
    fun lensRest(): Float {
        val at = hoverAt ?: return selectedIndex.toFloat()
        if (prismIndexAt(at, destinationCount) != selectedIndex) return selectedIndex.toFloat()
        return prismLeanAt(at, destinationCount)
    }

    /** dart:1072-1080 `_leanLens` */
    fun leanLens() {
        if (pressedIndex != null || prismIndexAt(lens.target, destinationCount) != selectedIndex) return
        val rest = lensRest()
        if (lens.target != rest) lens.springTo(rest, PrismSprings.hover)
    }

    /** dart:1084-1096 `_showHover`（桌面/外接鼠标；触摸不触发）。 */
    fun showHover(position: Offset, span: Float) {
        if (pointerId != null || destinationCount == 0) return
        cursor = position
        val p = prismPositionAt(position.x, span, destinationCount)
        val target = prismLeanAt(p, destinationCount)
        hoverAt = p
        if (hoverShow.target == 0f && hoverShow.value == 0f) hover.jumpTo(target)
        else hover.springTo(target, PrismSprings.hover)
        hoverShow.springTo(1f, PrismSprings.fade)
        leanContent()
        leanLens()
    }

    /** dart:1097-1101 `_settleSwell` */
    fun settleSwell(spec: SpringSpec<Float>) {
        val target = if (cursor == null) 0f else PrismDockSpec.HOVER_SWELL
        if (pointerId == null && swell.target != target) swell.springTo(target, spec)
    }

    /** dart:1109-1118 `_handleExit` */
    fun handleExit() {
        cursor = null
        hoverShow.springTo(0f, PrismSprings.fade)
        hoverAt = null
        leanContent()
        leanLens()
        settleSwell(PrismSprings.settle)
    }

    /** dart:1121-1134 `_handlePointerDown` */
    fun handleDown(position: Offset, span: Float) {
        if (pointerId != null) return
        pointerId = 0L
        hoverShow.springTo(0f, PrismSprings.fade)
        hoverAt = null
        leanContent()
        swell.springTo(1f, PrismSprings.press)
        glowAt = position
        glowSpread.jumpTo(0f)
        glow.springTo(1f, PrismSprings.glowIn)
        press(position.x, span)
    }

    /** dart:1136-1142 `_handlePointerMove` */
    fun handleMove(position: Offset, span: Float) {
        if (pointerId == null) return
        glowAt = position
        // v70：拖动期间 glow=1 / spread=0 恒定，只有 glowAt 在变；每帧 shine()
        // 会写 touchGlow 多触发一次重组，而光晕 strength 仅 0.06 基本看不见。
        // 只在 DOWN / UP 时由 glow spring 的 listener 触发 shine。
        slide(position.x, span)
    }

    /** dart:1144-1165 `_handlePointerEnd`；[velocityX] 由 pointerInput 的 VelocityTracker 给。 */
    fun handleUp(commit: Boolean, velocityX: Float, span: Float): Int? {
        if (pointerId == null) return null
        pointerId = null
        if (commit) fling(velocityX, span)
        val changed = release(commit)
        glow.springTo(0f, PrismSprings.glowOut)
        glowSpread.springTo(1f, PrismSprings.glowOut)
        stretch.springTo(0f, PrismSprings.press)
        val at = cursor
        if (at != null) {
            cursor = at
            showHover(at, span)
        }
        settleSwell(PrismSprings.settle)
        return changed
    }

    /** dart:1169-1177 `_press`：DOWN 就 lift→1、lens→index。 */
    fun press(x: Float, span: Float) {
        if (destinationCount == 0) return
        val index = prismIndexAt(prismPositionAt(x, span, destinationCount), destinationCount)
        pressedIndex = index
        pressX = x
        dragging = false
        dropping = false
        lift.springTo(1f, PrismSprings.lift)
        lens.springTo(index.toFloat(), PrismSprings.travel)
    }

    /** dart:1182-1200 `_slide`：touchSlop、边缘 stretch、follow spring 跟手、跨项触感反馈。 */
    fun slide(x: Float, span: Float) {
        val pressed = pressedIndex ?: return
        if (!dragging && abs(x - pressX) < PrismDockSpec.TOUCH_SLOP) return
        dragging = true
        val position = prismPositionAt(x, span, destinationCount)
        /*
         * v70：拖动**直通**，恢复 v65 的 `dragPos -> snapTo` 零延迟语义。
         *
         * 源码 `_slide` 用 `_followSpring`（stiffness 1024 / ratio .9）跟随，
         * 在 Flutter 里 `_Spring` 是 ChangeNotifier -> markNeedsPaint（repaint 级）；
         * 我们镜像成 Compose `mutableStateOf` 后变成 recomposition 级，
         * 每帧 springTo 还要重建 simulation、连锁 wobble/jelly、写 touchGlow，
         * 一条 MOVE 触发好几次整棵 GlassDock 重组 —— 这就是 v66 之后
         * 「卡顿不跟手」的根因。改成直接落值后，拖动期间每帧只剩
         * `lens.value` 一次状态写入，和 v65 完全一致；松手时 release() 的
         * travel spring 仍保留回弹手感。
         * stretch（边缘 rubberBand）在 UI 里没有接（整条 swell 已撤回），不写它。
         */
        lens.jumpTo(position, notify = false)
        val index = prismIndexAt(position, destinationCount)
        if (index != pressed) {
            pressedIndex = index
            onCrossTab?.invoke()   // HapticFeedback.selectionClick()
        }
    }

    /** dart:1206-1229 `_fling`：按 100ms 投影速度，最多跨到相邻一项。 */
    fun fling(velocityX: Float, span: Float) {
        val pressed = pressedIndex ?: return
        if (!dragging) return
        val pitch = prismDockLayout(span - PrismDockSpec.BAR_PADDING * 2, destinationCount).pitch
        val lead = velocityX / pitch * PrismDockSpec.FLING_PROJECTION_MS / 1000f
        val index = (lens.target + lead + 0.5f).toInt()
            .coerceIn(pressed - 1, pressed + 1)
            .coerceIn(0, lastIndex)
        if (index != pressed) {
            pressedIndex = index
            onCrossTab?.invoke()   // HapticFeedback.selectionClick()
        }
    }

    /** dart:1233-1244 `_release`；返回需要通知外部切换的 index（null = 不变）。 */
    fun release(commit: Boolean): Int? {
        val index = pressedIndex ?: return null
        pressedIndex = null
        dragging = false
        dropping = true
        lens.springTo((if (commit) index else selectedIndex).toFloat(), PrismSprings.travel)
        dropLift()
        return if (commit && index != selectedIndex) index else null
    }

    /** dart:didUpdateWidget：外部 selected 变化后同步静止目标。 */
    fun updateSelected(index: Int) {
        selectedIndex = index.coerceIn(0, lastIndex)
        leanContent()
        if (pressedIndex == null && lens.target != lensRest()) {
            lens.springTo(lensRest(), PrismSprings.travel)
        }
    }

    fun dispose() {
        listOf(lens, lift, hover, hoverShow, swell, stretch, jelly, glow, glowSpread)
            .forEach { it.dispose() }
        parallax.forEach { it.dispose() }
    }
}

/** 用当前状态直接算出气泡矩形 —— GlassDock 接进来时只认这一个入口。 */
fun PrismDockState.lensRectNow(span: Float): Rect {
    val layout = prismDockLayout(span - PrismDockSpec.BAR_PADDING * 2, destinationCount)
    return prismLensRect(
        position = lens.value,
        stretch = jelly.value,
        pitch = layout.pitch,
        width = layout.lens,
        height = PrismDockSpec.BAR_HEIGHT - PrismDockSpec.BAR_PADDING * 2,
        lift = lift.value,
    ).translate(Offset(layout.inset, 0f))
}
