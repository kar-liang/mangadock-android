package com.mangadock.android.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.settings.GlassLevel
import com.mangadock.android.ui.MangaTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.launch

/*
 * v49 那版「从 frag 裁几行出来的最小 AGSL」已删除。
 * 现在气泡只认 PrismLiquidGlass.kt 的 PRISM_GLASS_AGSL（1:1 移植的光学主体），
 * 参数统一由 LiquidGlassStyle.lens().scale(lift) 提供，UI 里不再有第二套 shader。
 */

/**
 * prism_ui `AppShape.full` = `RoundedSuperellipseBorder(borderRadius: 1000)`
 * （`shape.dart:88`）。v51 用「一整条 n=5 超椭圆」近似是错的：整条超椭圆把
 * 上下边也一起弯掉、四角撑得很方，93×64 的镜片看着就是个圆角矩形 ——
 * 老大 22:30 说「完全也不一样」，形状是第一眼的主因。
 *
 * Flutter 的圆角超椭圆是**四段直边 + 四段超椭圆角**，不是一整条超椭圆。
 * radius=1000 时角半径被 clamp 到 shortestSide/2，于是收敛成
 * 「两端各一个超椭圆半头 + 上下两段直边」的胶囊。这里按同一个定义画：
 * r = min(w,h)/2，角用 |cos|^(2/n) / |sin|^(2/n) 的超椭圆参数。
 *
 * n 定档 2.0（= 圆弧）：先按 n=3.5 做了一版，实测两端被撑成「圆角矩形」的方角，
 * 老大给的 FlClash 截图里气泡两端是干净的半圆（量测 385×235，两端半径≈半高，
 * 中间平边≈0.64h）。radius 取到 1000 这个极限时 Flutter 的超椭圆角与圆弧几乎重合，
 * 所以这里让 e=2/n=1 直接走圆弧，形状反而和参考图一致。
 */
private fun superellipseCapsulePath(w: Float, h: Float, n: Float = 2f): Path {
    val r = minOf(w, h) * 0.5f
    val e = 2f / n
    val steps = 20
    val cy = h * 0.5f
    val right = w - r
    return Path().apply {
        // 顶边：左端半头结束处 → 右端半头起点
        moveTo(r, 0f)
        lineTo(right, 0f)
        // 右端半头 t: -π/2 → π/2
        for (i in 1..steps) {
            val t = (-Math.PI / 2.0 + Math.PI * i / steps).toFloat()
            val c = cos(t)
            val s = sin(t)
            lineTo(right + r * c.sign * abs(c).pow(e), cy + r * s.sign * abs(s).pow(e))
        }
        // 底边：右端 → 左端
        lineTo(r, h)
        // 左端半头 t: π/2 → 3π/2
        for (i in 1..steps) {
            val t = (Math.PI / 2.0 + Math.PI * i / steps).toFloat()
            val c = cos(t)
            val s = sin(t)
            lineTo(r + r * c.sign * abs(c).pow(e), cy + r * s.sign * abs(s).pow(e))
        }
        close()
    }
}

/** 镜片的圆角超椭圆 Shape（按节点自身尺寸生成）。底栏本体仍用 pill，不动 v39 定档。 */
private val LensSuperellipse = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(superellipseCapsulePath(size.width, size.height))
}

/**
 * v85：胶囊的两条硬边（0.67dp 暗 hairline + 1.2dp 上下白高光）。
 *
 * 原来这两笔是胶囊 Box 的**子节点**（Canvas），因此会被 `dockBackdrop.record`
 * 一起录进气泡的采样源。凸透镜 shader 沿法线向外读（lens 分支
 * `shift = normal * -uOptics.x * ...`），会把这条硬黑线向外复制出一条平行线；
 * 原位那条又还在 —— 纯色背景下就成了老大说的「气泡里底栏边缘有两层线」。
 * 封面背景内容杂，复制线被盖住，所以只有纯色页看得出来。
 *
 * prism_ui 的 `_DockSurface` / `LiquidGlassStyle.pill` 没有独立 Canvas 描边：
 * 它的 rim 由 frag 的 hairline 项在玻璃自身最外 0.67dp 内算出，很淡，
 * 折一次也读不出第二条硬线。
 *
 * 现在把这两笔挪到 `drawWithContent` 里 `drawLayer(dockBackdrop)` **之后**画：
 * 屏幕上看到的胶囊边线一字不差，但 `dockBackdrop` 只剩玻璃本体，
 * 气泡采样源里不再有可被复制的硬线。
 *
 * v85b 真机诊断（MuMu 纯色设置页长按最右 tab）：光挪走还不够 ——
 * 气泡节点的 lens shader 在边缘 coverage < 1，下面这层硬描边仍会**透过**
 * 气泡显出来，y=1790 剖面 x=909 那条深灰线就是它（关掉 `drawCapsuleEdges`
 * 后 x=909 立刻消失，只剩 x=930 气泡自身一条干净线，与 Flutter 一致）。
 *
 * v85 曾用「硬边 alpha = 1 - lift」把抬起时的硬边淡掉来遮掩，但那是治标：
 * 抬起时底栏边界也跟着变软，而且解释不了「文件页长按本来就是单层线」。
 * v86 真正的原因与修法在 `drawBehind` 开头那层不透明底色 —— 见那里的注释。
 * 这层硬描边因此恢复恒显，静止/抬起都是 v81c 之后那条 1px 精度的清晰边界。
 */
private fun DrawScope.drawCapsuleEdges(oneDpPx: Float, dark: Boolean, alpha: Float) {
    if (alpha <= 0f) return
    val outline = superellipseCapsulePath(size.width, size.height)
    if (!dark) {
        /*
         * v93：暗线加重，解决「底栏和背景融为一体」。
         *
         * 真机实测（设置页纯色底，MuMu 1080×1920）：胶囊填充 rgb(244,244,245)，
         * 页面背景 rgb(242,243,249) —— 两者只差 1~6 个色阶，视觉上同色。
         * 整条边全靠这 0.67dp 的线撑着，而原来中段只有 4% 黑（244 → 234）、
         * 两端 10%（→219），1px 宽、对比不足，截图里几乎看不出边界。
         *
         * 现在中段 13%（244 → 212）、两端 26%（→181）：仍是「细线」而不是黑框，
         * 但在浅底上第一眼就能读出胶囊轮廓。线宽 / 渐变位置 / 几何一律不动 ——
         * v81c 定的「1px 精度几何路径」是这层存在的理由，别改成 stroke 采样。
         */
        drawPath(
            outline,
            brush = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.260f),
                0.10f to Color.Black.copy(alpha = 0.155f),
                0.5f to Color.Black.copy(alpha = 0.130f),
                0.90f to Color.Black.copy(alpha = 0.155f),
                1f to Color.Black.copy(alpha = 0.260f)
            ),
            style = Stroke(width = 0.67f * oneDpPx),
            alpha = alpha
        )
    }
    drawPath(
        outline,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.60f),
            0.09f to Color.White.copy(alpha = 0.07f),
            0.91f to Color.White.copy(alpha = 0.07f),
            1f to Color.White.copy(alpha = 0.60f)
        ),
        style = Stroke(width = 1.2f * oneDpPx),
        alpha = (if (dark) 0.5f else 0.35f) * alpha
    )
}

/**
 * 镜片（气泡）的显式定位：节点自身只报**父容器的尺寸**，把放大的气泡按
 * prism_ui `_lensRect` 的 px 坐标 place 出去。
 *
 * 为什么不用 `offset()` + `requiredSize()`（2026-11-05 实测）：
 * 气泡抬起后高 70dp，比胶囊 62dp 高；`offset` 节点报给父 Box 的尺寸仍是 70dp，
 * 父 Box 摆这个「超高子项」时会再向上挪 (62-70)/2 = -4dp，
 * 于是每帧都差半个超出量 —— 表现就是「上鼓 20px、下鼓 0px」。
 * 这里把「报给父的尺寸」和「子项落点」彻底分开，父布局没有二次挪动的余地。
 */
private fun Modifier.lensPlacement(xPx: Float, yPx: Float) = layout { measurable, constraints ->
    val boxW = constraints.maxWidth
    val boxH = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints(maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity)
    )
    layout(boxW, boxH) { placeable.place(xPx.roundToInt(), yPx.roundToInt()) }
}

/** 位移用 backOut：先冲过头再弹回来，420ms。 */
private val BackOut = Easing { t ->
    val c1 = 1.2f
    val c3 = c1 + 1f
    val u = t - 1f
    1f + c3 * u * u * u + c1 * u * u
}

enum class DockTab(val label: String) {
    // v98：「文件」升级成「资源库」—— 点进去不再是某台 WebDAV 的目录，
    // 而是所有服务器的卡片墙。枚举名保持 FILES，避免几十处调用点跟着改。
    BOOKS("书架"), FILES("资源库"), SETTINGS("设置")
}

/**
 * v97：液态玻璃三档的参数（老大要的「无 / 标准 / 极致」）。
 *
 * 全部写成**倍率 + 原值**，STANDARD 是 1f 和原来的 0.72 / 15.6dp ——
 * 这样「标准」跑出来和 v96 逐像素一致，以后 prism 那套基准值变了标准档也自动跟着变，
 * 不会出现「设置里选标准，画面却不是老样子」这种漂移。
 *
 * ULTRA 只动四样肉眼真能分辨的：
 *  - **tint alpha ×0.75**：这才是「更透」的主开关。第一版只降了 haze
 *    （0.72 → 0.55），真机整段 maxdiff 只有 13、mean 不到 1 —— 完全看不出来：
 *    不透明度主要由 tint（浅色 0x93 = 57% 白）决定，haze 只是雾面权重，
 *    被 tint 盖住了。所以必须动 tint。
 *  - refraction ×1.5：边缘把背景挤得更狠，玻璃球感更强；
 *  - dispersion ×1.3：气泡边缘的红蓝分离更明显；
 *  - 饱和度 ×1.15：透出来的背景更「玻璃」，不发灰。
 * **bevel 故意不动**：胶囊 62dp 高，shader 里 `uBevel` 会被 `halfMinor*0.5`
 * （= 15.5dp）夹住，标准档的 20dp 本来就顶到上限了，往上调是空转。
 */
private data class GlassTune(
    val refractionScale: Float,
    val dispersionScale: Float,
    val tintAlphaScale: Float,
    val saturationScale: Float,
    val haze: Float,
    val hazeBlurDp: Float,
) {
    companion object {
        /** v81~v96 一直在用的那一档，一个数都没改 */
        val STANDARD = GlassTune(1f, 1f, 1f, 1f, 0.72f, 15.6f)

        fun of(level: GlassLevel): GlassTune = when (level) {
            // 「无」不跑 shader，参数没人读，回标准省得留个假的
            GlassLevel.NONE, GlassLevel.STANDARD -> STANDARD
            GlassLevel.ULTRA -> GlassTune(1.5f, 1.3f, 0.75f, 1.15f, 0.50f, 13f)
        }
    }
}

/**
 * 底栏几何，全部来自 FlClash `navigation_dock.dart` + `metrics.dart`，单位 dp。
 * 材质层已经去掉玻璃，这里只保留几何和果冻曲线。
 */
object DockMetrics {
    /*
     * v82：整组几何改回 **对齐 prism_ui 原版**（老大要「把 Flutter 的底栏搬过来」）。
     *
     * 之前 v39 为了「别太长太空」把胶囊定档成 216×56、图标 23，看着比 Flutter 版
     * 明显矮一截、小一号。现在按 `navigation_dock.dart` 的真实公式取：
     *
     *   宽 = n * _maxItemExtent + _barPadding * 4 = 3 * 86 + 16 = 274
     *   高 = _barHeight = 62
     *   边距 = _edgeMargin = 21
     *   图标 = _iconSize = 28
     *   标签 = _labelSize = 10
     *
     * 宽度只在这六个值里选；切到 5 个 tab 时原版才「占满」，我们恒为 3 个，
     * 所以固定 274 就是原版的实际值，不用再算。
     * 由 MainActivity 用 (w - barW) / 2 居中；窄屏 / 横屏才退让到最小边距。
     */
    const val BAR_WIDTH = 274f
    const val BAR_HEIGHT = 62f
    const val BAR_PADDING = 4f
    /** 窄屏时至少留的左右边距，正常宽度用不上（BAR_WIDTH 已经居中）。 */
    const val MIN_EDGE_MARGIN = 21f
    /** 胶囊下沿到屏幕底的距离。 */
    const val BOTTOM_GAP = 24f
    const val ICON_SIZE = 28f
    const val LABEL_SIZE = 10f
    /*
     * ⚠️ 镜片的 growth / 圆角**不在这里**：真实值由 `PrismDockSpec`
     * （PrismDock.kt:86 `LENS_GROWTH = 8f`）和 `_lensRect` 给，UI 只读。
     * 以前这里还留着 `LENS_GROWTH = 16f` / `LENS_RADIUS = 28f` 两个
     * **从未被引用**的死常量，值是错的（16 是 8*2*lift 里 8 的两倍），
     * 排查时极易误判成「放大过头」。已删除，别再往这里加。
     */

    /**
     * 果冻峰值。FlClash 原版 0.20，拉出来是 172×50 的长条，太拖尾；
     * 定档 0.07 → 153×63。
     */
    const val JELLY = 0.07f
    /** 拉宽的同时压扁的倍数，stretch 乘上它。 */
    const val JELLY_ASPECT = 1.4f

    /** 位移用 backOut：先过冲再回弹。 */
    const val TRAVEL_MS = 420

    /** 顶栏：状态栏 + 56dp 标题栏 + 16dp 溢出让雾化完。 */
    const val TOP_BAR_HEIGHT = 56f
    const val HEADER_OVERHANG = 16f
}

/**
 * 悬浮胶囊底栏：普通白色胶囊 + 主题色选中镜片，没有任何模糊 / 着色器采样。
 * 几何和果冻曲线沿用原版，只把材质层换成最轻的 Compose 控件。
 *
 * 之前那套「整页软件位图 + AGSL 折射」每帧都要重栅格化整屏并多次采样，
 * 输入延迟高到不跟手；现在和阅读器一样走常规 Compose 绘制路径。
 */
@Composable
fun GlassDock(
    barLeft: Dp,
    barTop: Dp,
    barWidth: Dp,
    selected: DockTab,
    /** 选中项的颜色，跟主题走 */
    accent: Color,
    /** v37 玻璃源：内容层录在哪儿。null 就退回不透明胶囊。 */
    glass: GlassSource? = null,
    /** v97：液态玻璃档位。默认标准 = v81~v96 那套参数原样。 */
    glassLevel: GlassLevel = GlassLevel.STANDARD,
    onSelect: (DockTab) -> Unit,
) {
    val c = MangaTheme.colors
    val density = LocalDensity.current
    /*
     * prism_ui 的 `BackdropFilter` 采的是「气泡之前已经画好的 backdrop」。
     * Compose 的 RenderEffect 只采节点自身内容，所以这里把底栏胶囊
     * （玻璃 + 上下 hairline）录进一张硬件 GraphicsLayer，气泡再把它
     * 连同页面内容层一起画进自己的采样源。等价于 frag 的 uTexture/uClamp。
     */
    val dockBackdrop: GraphicsLayer = rememberGraphicsLayer()
    /*
     * `_LensContent`（navigation_dock.dart:1540-1590）需要把「图标那一层」
     * 单独录一遍，再用 `LiquidGlassStyle.lensContent` 这**第二个**玻璃面板折一次。
     * FlClash 长按气泡边缘那种图标被拉成弧形、带彩边的效果就是这层折出来的 ——
     * 只用 `lens` 折 backdrop 是折不到图标的（图标画在气泡上面）。
     */
    val iconLayer: GraphicsLayer = rememberGraphicsLayer()
    val barH = DockMetrics.BAR_HEIGHT.dp
    val pad = DockMetrics.BAR_PADDING.dp
    val pitch = (barWidth - pad * 2) / 3
    // 静止镜片正好一个 tab 宽：以前是 pitch + pad*2，会比格子宽出两侧 padding，
    // 选中第 1 / 第 3 项时镜片直接顶出胶囊边缘。v39 定档，抬起时才按
    // FlClash 的 layout.lens = pitch + padding*2 长出去（见下面 lensWpx）。
    val restH = barH - pad * 2

    // ---- v67：动画唯一来源 = prism_ui 的 PrismDockState ----
    //
    // 旧 v50 那套 `Animatable(lensPos / pressLift)` + tween(420, BackOut) 是手搓近似，
    // 不是 FlClash 源码。现在 press/slide/release 走 dart:1169 / 1182 / 1233，
    // lens/lift/jelly/glow/… 十条 spring 按 dart:60-104 精确换算，
    // _wobble 的 braking/squash、_fling 的 100ms 速度投影、_dropLift 的条件回落
    // 全在 PrismDock.kt 的状态机里，UI 只读当前值、不再自己算曲线。
    val dockScope = rememberCoroutineScope()
    val dockState = remember {
        PrismDockState(dockScope, selected.ordinal, DockTab.entries.size)
    }
    DisposableEffect(Unit) { onDispose { dockState.dispose() } }
    val onSelectNow by rememberUpdatedState(onSelect)

    /*
     * v85 ①：气泡拖到另一个 tab 上的轻触感反馈。
     * prism_ui `_slide`（dart:1203）和 `_fling`（dart:1228）只在
     * `index != _pressedIndex` 时调 `HapticFeedback.selectionClick()`，
     * 初次按下（_press）不震。CLOCK_TICK 是 Android 上最接近 selectionClick
     * 的轻 tick；重复触发由状态机去抖。
     */
    val view = LocalView.current
    dockState.onCrossTab = {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // 外部 selected 变化 = 源码 didUpdateWidget；点按松手后 onSelectNow 会触发它。
    LaunchedEffect(selected) { dockState.updateSelected(selected.ordinal) }

    val padPx = with(density) { pad.toPx() }
    // 描边宽度按 dp 换算一次，别在 Canvas 里碰 DrawScope.density（同名，会取成 Float）
    val oneDpPx = density.density
    val pitchPx = with(density) { pitch.toPx() }
    // 状态机在 dp 域算位置（源码 localPosition 也是逻辑像素）；
    // pointerInput 给的 px 在传进去之前统一除以 oneDpPx。
    val spanDp = barWidth.value

    // 状态机的十条 spring 是唯一数据源：lens 位置 / lift 抬起 / jelly 果冻拉伸。
    // 旧版 `sin(π·t/0.72)` 脉冲果冻和 pulseLift 都是自造的，已删除。
    val lensPos = dockState.lens.value
    /*
     * v97：「无」档把 lift 钉死在 0。
     *
     * 这一档不跑任何 shader，但镜片那层（`② platter`）还在 —— 它是**选中块**，
     * 不能一起删，否则底栏上看不出当前在哪个 tab。
     * lift=0 时镜片几何就是静止镜片（正好一个 tab 格），platter 的
     * `alpha = 1 - lift` 也正好是 1，于是它自然退化成一个静态选中块；
     * 气泡轮廓 / 挖洞 / 折射全部跟着这条 if 一起关掉，不用在五个地方各写一遍。
     */
    val liquid = glassLevel != GlassLevel.NONE
    val lift = if (liquid) dockState.lift.value else 0f
    val stretch = dockState.jelly.value
    /*
     * v97：档位 → 一套倍率。STANDARD 全是 1f 且 haze 原值，
     * 所以「标准」跑出来的像素和 v96 一模一样（改完必须回调比对，别信肉眼）。
     */
    val tune = GlassTune.of(glassLevel)
    /*
     * v85 ②：整条底栏长按时的浮动放大，1:1 对 prism_ui
     * `_PressTransform._transform`（navigation_dock.dart:818-830）：
     *   growth = ElasticPress.growthOf(panel.longestSide) = min(1/8*2, 16 / side)
     *   swell  = 1 + _swell.value * growth
     * panelOf 没有传，所以 panel 就是底栏自己：longestSide = 274dp，
     * 16/274 = 0.0584 → 整条 bar（连气泡、图标一起）绕底栏中心放大到 105.84%。
     * swell 由 _press(DOWN) → 1、_release(UP) → 0 的 spring 驱动，抬手会弹回。
     */
    val swell = dockState.swell.value
    val pressGrowth = prismPressGrowth(spanDp)
    val pressScale = 1f + swell * pressGrowth
    // graphicsLayer 的缩放不参与 positionInRoot()，整屏内容层的采样偏移要手动
    // 换算成「缩放后的实际 root 坐标」，否则气泡里的页面会与玻璃错位（v67 的坑）。
    var barCenterInRoot by remember { mutableStateOf(Offset.Zero) }

    /*
     * 严格照 `_lensRect`（navigation_dock.dart:114-131）：
     *   growth     = _lensGrowth * 2 * lift          = 16 * lift
     *   lensWidth  = (width  + growth) * (1 + stretch)，width  = layout.lens = pitch + padding*2
     *   lensHeight = (height + growth) * (1 - stretch * _jellyAspect)，height = 内容区高
     *   top        = (height - lensHeight) / 2 —— 相对内容区居中 ⇒ 上下各鼓出胶囊
     * 我们的胶囊 62dp、内容区 54dp，和 FlClash 完全一致；
     * 加同一个 growth(16) 后气泡高 70dp，相对胶囊上下各鼓出 4dp。
     *
     * v82 之前这里是 56 / 48，为了凑「鼓出 4dp」的比例把胶囊压矮了 6dp ——
     * 现在高度直接对齐原版，不用再靠缩小胶囊来维持比例。
     */
    // 几何只认 prism_ui 的 `_lensRect`（PrismDock.kt），UI 里不再另写一套公式。
    // width 沿用 v52 的 baseW（静止不加 padding，避免镜片顶穿 v39 缩短后的胶囊圆角）；
    // growth/stretch/top 的定义全部由原版函数给出。
    val lensRectDp = prismLensRect(
        position = lensPos,
        stretch = stretch,
        pitch = pitch.value,
        width = pitch.value + pad.value * 2f * lift,
        height = restH.value,
        lift = lift,
    )
    // `_lensRect(...).shift(Offset(layout.inset, 0))` + Padding(all: barPadding)
    val lensW = lensRectDp.width.dp
    val lensH = lensRectDp.height.dp
    val lensX = (lensRectDp.left + pad.value).dp
    val lensY = (pad.value + lensRectDp.top).dp
    val lensWpx = with(density) { lensW.toPx() }
    val lensHpx = with(density) { lensH.toPx() }
    val lensXpx = with(density) { lensX.toPx() }
    val lensYpx = with(density) { lensY.toPx() }

    Box(
        Modifier
            .offset(x = barLeft, y = barTop)
            .width(barWidth)
            .height(barH)
            /*
             * v85 ②：整条底栏的浮动放大。transformOrigin 默认就是节点中心，
             * 与 prism_ui `_PressTransform` 的「绕 panel.center 缩放」一致；
             * 气泡、胶囊、图标、文字作为子节点一起进同一个 layer，缩放完全同步。
             */
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .onGloballyPositioned {
                barCenterInRoot = it.positionInRoot() +
                    Offset(it.size.width * 0.5f, it.size.height * 0.5f)
            }
            /*
             * v51：这里**不能**再 clip 成胶囊。
             * 按下 / 拖动时镜片会长 16dp（高 54→70dp），超过 62dp 的胶囊；
             * 老版这一刀把气泡的上下沿切成水平直线，左右却还是圆弧 ——
             * 老大 22:13 指出的瑕疵。液态玻璃要的就是镜片鼓出胶囊外，
             * 玻璃本体 GlassSurface 和外沿 border 各自带 clip(pill)，
             * 只有镜片这一层允许溢出，删掉外层 clip 不会影响别的层。
             */
            .pointerInput(spanDp, oneDpPx, dockState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // `_handlePointerDown` → `_press`：DOWN 就抬镜片、进光、swell。
                    // 状态机在 dp 域工作，pointerInput 给的是 px，这里统一换算。
                    fun dpOf(p: Offset) = Offset(p.x / oneDpPx, p.y / oneDpPx)
                    dockState.handleDown(dpOf(down.position), spanDp)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var commit = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        // `_handlePointerMove` → `_slide`（内含 touchSlop / overdrag /
                        // follow spring / 跨项更新 pressedIndex）。
                        dockState.handleMove(dpOf(ch.position), spanDp)
                        if (dockState.dragging) ch.consume()
                        if (!ch.pressed) {
                            // 只有真正的 UP 才 commit；手势被取消时按源码 `commit:false` 弹回。
                            commit = ch.previousPressed
                            break
                        }
                    }
                    // `_handlePointerEnd`：先 `_fling` 用 100ms 速度投影，再 `_release`。
                    val vx = tracker.calculateVelocity().x / oneDpPx  // px/s -> dp/s
                    val changed = dockState.handleUp(commit, vx, spanDp)
                    if (changed != null) onSelectNow(DockTab.entries[changed])
                }
            }
            /*
             * v67 曾把整条 swell 撤回：给最外层 Box 加 graphicsLayer 后，
             * backdrop 采样仍用 `positionInRoot()`（不含 graphicsLayer 变换），
             * 采样层与玻璃节点错位，气泡右上方会露出一块边界笔直的矩形。
             *
             * v85 重新接上，并补掉那个坑：气泡 drawBehind 里整屏内容层的偏移
             * 不再直接用 lensAt，而是先按 pressScale 换算成「缩放后的实际 root
             * 坐标」（见下面 drawBehind 的 lensRoot）。胶囊 backdrop 与气泡同处
             * 这个 layer 内，相对关系不变，不需要补偿。
             * 整屏页面层会跟着 layer 一起被放大 5.84%（纯色页看不出来，
             * 封面页与 prism_ui 的 TransformLayer 重放行为一致）。
             */
            /*
             * v67：`_shine()`（dart:986-996）的 LiquidGlassGlow —— 光源跟着手指，
             * 按下进光、松手扩散淡出（glow / glowSpread 两条 spring）。
             * 末端用透明白而不是 Color.Transparent，避免插值中段把白雾拉灰。
             */
            .drawWithContent {
                drawContent()
                val g = dockState.touchGlow
                if (g.strength > 0.0001f && g.reach > 0f) {
                    val centerPx = g.center * oneDpPx
                    val reachPx = g.reach * oneDpPx
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = g.strength),
                                Color.White.copy(alpha = g.strength * g.wash),
                                Color.White.copy(alpha = 0f),
                            ),
                            center = centerPx,
                            radius = reachPx,
                        ),
                        center = centerPx,
                        radius = reachPx,
                    )
                }
            }
    ) {

        // ① 底栏本体（静止不动）：真玻璃 —— 底下内容模糊后**直接透出来**，只留极薄一层底色。
        //
        // v37 初版给了 75% 白（0xBFFBFBFD）/ 54% 黑，等于在玻璃上刷了层漆：
        // 实测 SenPlayer 胶囊区域彩色像素 5.0%、均值 219，我们只有 1.0%、均值 237，
        // 底下滚过的列表文字和彩色图标一点都看不见。老大指出「它的底栏还是透明玻璃，
        // 我们的不是」—— 就是这层 tint。现在浅色只留 10% 白、深色 15% 黑：
        // 纯色底时靠模糊层 + 描边 + 高光仍有厚度，有内容穿过时能看见颜色和轮廓。
        //
        // blur 26 → 16 → 8 → 4 → **1dp**：
        // 老大实测「底栏背后的『镇魂街』一个字都没透过来」。诊断：blur=0.01dp 时文字
        // 清晰可见，说明 glassSource 录的内容是对的；而 8dp 与 4dp 的画面像素差只有
        // 0.88 —— BlurEffect 对 40px 高的中文黑字柔化极狠，4dp 已把笔画摊平。
        // 1dp 只留一层极轻的雾面，保住笔画轮廓；tint 的 10% 白负责压低对比，
        // 保证底栏自己的图标文字仍读得清。GPU 开销比之前任何一档都小。
        val pill = RoundedCornerShape(percent = 50)
        /*
         * prism_ui 的 `_GlassLens` 用 BackdropFilter 采「气泡之前画好的整个底栏」。
         * 这里把胶囊玻璃 + 上下 hairline 录进 dockBackdrop，气泡再采它 ——
         * 录一次、贴一次，内容只画一遍（和 Modifier.glassSource 同一手法）。
         * 之前是在气泡里手工重画一份胶囊，形状/边线总对不齐，现在换成真实画面。
         */
        Box(
            Modifier
                .size(barWidth, barH)
                .drawWithContent {
                    if (size.width > 0f && size.height > 0f) {
                        // v85：只录胶囊玻璃本体，硬描边不进 backdrop（见 drawCapsuleEdges 注释）。
                        dockBackdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(dockBackdrop)
                        // 录制之后再画硬边：屏幕上照旧，气泡采样源里没有这条可被
                        // refraction 复制的线。
                        // v86：撤回 v85 的「随 lift 淡出」遮掩，硬边恒显；
                        // 靠 drawBehind 铺不透明底从根上阻止它透出来。
                        if (glass != null) {
                            drawCapsuleEdges(oneDpPx, c.dark, 1f)
                        }
                    } else {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            /*
             * 胶囊本体 = prism_ui 的 `LiquidGlassStyle.pill`（v81）：
             * 折射 60 / bevel 20 / 雾面 0.72@15.6dp / 饱和 2.1 / tint 0x93F4F4F4。
             * 体交给 AGSL（边缘 14dp 内把背景向内挤成弧、rim 高光按法线分布、
             * tint 按背景明暗自动加厚），**边**交给 Canvas —— 见下面那段注释。
             */
            if (glass == null || !liquid) {
                /*
                 * 没有内容层（预览 / 单测），或者老大选了「无」档 ——
                 * 都退回不透明胶囊，别露一块半透明色。
                 * 「无」不是「把玻璃调淡」：调淡会在边缘留一圈糊边，
                 * 反而不如干脆不跑 shader。
                 */
                Box(Modifier.size(barWidth, barH).background(c.dockSurface, pill))
            } else {
                PrismGlassBar(
                    source = glass,
                    // v81c：暗线关掉，改由下面 Canvas 画。
                    // shader 的暗线是 SDF 里「最外 0.67dp」算出来的，边缘像素永远
                    // 带一点 SDF 采样的糊（尤其配 haze 时）；Canvas 描边是几何路径，
                    // 1px 精度、不带采样，压上去边界立刻是硬的。FlClash 也是这个道理：
                    // 它那条线由 UIKit 用路径画在 bounds 上，不经过折射采样。
                    // v97：refraction / tint / 饱和度走档位倍率，标准档全是 ×1f = 原值。
                    style = LiquidGlassStyle.pill(c.dark).let { st ->
                        st.copy(
                            refraction = st.refraction * tune.refractionScale,
                            saturation = 1f + (st.saturation - 1f) * tune.saturationScale,
                            tint = st.tint.copy(alpha = st.tint.alpha * tune.tintAlphaScale),
                            hairline = 0f,
                            hairlineSide = 0f
                        )
                    },
                    shape = pill,
                    haze = tune.haze,
                    hazeBlur = tune.hazeBlurDp.dp,
                    modifier = Modifier.size(barWidth, barH),
                )
                // v85：硬边已从子节点移走，改在父 Box 的 drawWithContent 里
                // 「drawLayer(dockBackdrop) 之后」画（drawCapsuleEdges）。
                // 子节点只留玻璃本体，录进 backdrop 的就是纯净的一片。
            }
        }

        // ② 选中镜片 = **在玻璃上挖一个洞**（v48）
        //
        // 以前是 c.chip 中性灰实心底，等于在玻璃上贴了块膏药；SenPlayer 的镜片是
        // 能看见内容的清透镜片，FlClash / prism_ui 那套叫「挖洞」。这里在镜片区域
        // 直接贴一份**未模糊、未染色**的内容层（就是 glassSource 录的整屏 RenderNode），
        // 只靠一圈亮边标出洞口的玻璃厚度，中间不画任何底色。
        //
        // 坐标换算和 GlassSurface 一样：selfAt - source.origin 把洞挪到整屏层的
        // 同一坐标系，再 translate 后 drawLayer。这个节点被 clip 成镜片形状，
        // 所以只贴出洞口那一块，不会每帧重绘整屏。
        var lensAt by remember { mutableStateOf(Offset.Zero) }
        // 1:1 搬过来的 prism_ui 光学 shader。RuntimeShader 是**设备侧编译**：
        // gradle 过不代表 Skia 过，构造失败就静默退回纯挖洞，别让底栏消失。
        val lensShader = remember { runCatching { RuntimeShader(PRISM_GLASS_AGSL) }.getOrNull() }
        // `_LensContent` 那块面板要**另一个** shader 实例：同一帧里两块面板的
        // uniform 不同，共用一个 RuntimeShader 会互相覆盖。
        val lensContentShader = remember { runCatching { RuntimeShader(PRISM_GLASS_AGSL) }.getOrNull() }
        Box(
            Modifier
                /*
                 * 显式定位，不再用 `offset` + `requiredSize`。
                 *
                 * 踩过的坑（2026-11-05 实测）：气泡抬起后高 70dp，比胶囊 62dp 高。
                 * `offset` 报给父 Box 的尺寸仍是 70dp（超出父约束），父 Box 在摆放
                 * 这个「超高子项」时会额外向上挪 (62-70)/2 = -4dp，于是气泡被挪成
                 * 上鼓 20px、下鼓 0px —— 老大反复说的「不对称」就是这 4dp。
                 * 这里改成：**节点自身只报胶囊大小**（142.8px），把放大的气泡按
                 * prism_ui 的 `_lensRect` 坐标显式 place 出去，尺寸/位置都只由
                 * 公式决定，父布局没有任何二次挪动的余地。
                 */
                .lensPlacement(lensXpx, lensYpx)
                .requiredSize(lensW, lensH)
                // `_lensShadows`：blurRadius 5 / offset(0,8) / alpha 0.055×strength×lift
                // （深色 strength=3）。v51 用 8dp elevation + 0.10 alpha 太重，像贴了块板。
                .shadow(
                    elevation = 4.dp * lift,
                    shape = LensSuperellipse,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.055f * lift * if (c.dark) 3f else 1f),
                    spotColor = Color.Black.copy(alpha = 0.055f * lift * if (c.dark) 3f else 1f)
                )
                // 外层不 clip：Stroke 有一半画在形状外侧，裁掉就成了半根线。
                // 挖洞内容层自己 clip，玻璃本体另有 pill 裁切，只有镜片这一层允许溢出。
                .onGloballyPositioned { lensAt = it.positionInRoot() }
        ) {
            // ① 洞里的画面：单独一个节点吃 shader。uContent 只覆盖镜片这一小块，
            //    clip = true 保证 RenderEffect 的离屏缓冲按镜片大小开（v13 的性能红线）。
            //    v97：「无」档整层不组合 —— 胶囊已经不透明了，这层再往里透内容就穿帮。
            if (liquid) Box(
                Modifier
                    .matchParentSize()
                    .clip(LensSuperellipse)
                    .graphicsLayer {
                        clip = true
                        val s = lensShader
                        if (s != null) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            // 全部参数只认 prism_ui 的 LiquidGlassStyle.lens().scale(lift)，
                            // dp→px 换算集中在 PrismLiquidGlass.applyTo，UI 里不再散落魔数。
                            // v97：折射 / 色散走档位倍率；标准档 ×1f = 原值。
                            PrismLiquidGlass.applyTo(
                                s,
                                LiquidGlassStyle.lens(c.dark).scale(lift).let { st ->
                                    st.copy(
                                        refraction = st.refraction * tune.refractionScale,
                                        dispersion = st.dispersion * tune.dispersionScale
                                    )
                                },
                                w, h, oneDpPx
                            )
                            renderEffect = RenderEffect
                                .createRuntimeShaderEffect(s, "uContent")
                                .asComposeRenderEffect()
                        }
                    }
                    .drawBehind {
                        // v86：先铺一层不透明的页面底色 —— 双线的真正根因在这里。
                        //
                        // `MainActivity` 把 `background(bg)` 画在 `glassSource` 录制层
                        // **之外**（BoxWithConstraints 上），所以录制层里页面空白区域
                        // 是 alpha=0 的透明像素：设置页只有上半张白卡片、书架页封面
                        // 之间有空隙，底栏背后全是透明；而文件页那张白色列表卡片
                        // 一路铺到屏幕底部，底栏压在不透明像素上。
                        //
                        // 气泡的 `uContent` = 本节点内容。frag 里
                        // `coverage = mix(coverage, under.a, line)` 让最外 0.67dp
                        // hairline 的 alpha 直接取 uContent：内容透明 → coverage=0
                        // → 气泡最外一圈透明 → 下面胶囊的 Canvas 硬描边透出来，
                        // 就是老大看到的第二条线。文件页/封面页内容不透明，
                        // coverage=1 把硬边盖住，所以本来就是 Flutter 那样的单层。
                        //
                        // 铺 c.bg 与屏幕像素等价（空白区本来显示的就是这个颜色），
                        // 但让 uContent 在整个镜片矩形里恒为不透明 —— 三页、三个
                        // tab 位置统一单层，胶囊硬描边也不必再为抬起而淡出。
                        drawRect(c.bg)
                        // ① 屏幕内容层（未模糊）—— v48 挖洞
                        val layer = glass?.layer
                        if (layer != null) {
                            // v85：`positionInRoot()` 不含 graphicsLayer 的缩放，
                            // 这里把镜片原点换算成缩放后的实际 root 坐标，
                            // 否则整屏内容层会与玻璃节点错位（v67 露矩形的根因）。
                            val lensRoot = barCenterInRoot +
                                (lensAt - barCenterInRoot) * pressScale
                            val o = lensRoot - glass.origin
                            translate(left = -o.x, top = -o.y) { drawLayer(layer) }
                        }

                        // ② 真实录制的底栏 backdrop —— 对应 frag 的 uTexture。
                        //    胶囊在 Dock 局部 (0,0)，气泡原点在 (lensXpx,lensYpx)，
                        //    所以把这一层平移 (-lensXpx,-lensYpx) 贴进气泡局部坐标。
                        //    层里是**真实画出来的胶囊**：雾面玻璃、上沿高光、上下
                        //    hairline 全都在，不再手工重画一份。凸透镜 shader 再把这些
                        //    直线掰弯映射进气泡（老大 22:51 要的效果）。
                        //    气泡鼓出胶囊的部分由 ① 的整屏内容层补上，不会露黑。
                        translate(left = -lensXpx, top = -lensYpx) {
                            drawLayer(dockBackdrop)
                        }
                    }
            )
            // ② platter —— `_Lens` 的 alpha × (1 - lifted)，**抬起后完全归零**。
            //    v51 只淡出一半、还留一道 1dp 黑实线，气泡于是成了「灰底膏药」；
            //    FlClash 的 `LiquidGlassStyle.lens` 是 tint: transparent / blur: 0，
            //    气泡里一点底色都没有，全靠边缘几道光勾出轮廓。
            Box(
                Modifier
                    .matchParentSize()
                    .clip(LensSuperellipse)
                    .background(
                        /*
                         * `_Lens`（navigation_dock.dart:1452-1476）静止态 platter：
                         *   tone     = dark ? 4 : 90
                         *   platter  = HCT(secondary.hue, secondary.chroma, tone) @ 0.85
                         *   alpha    = platter.a * (1 - lifted)   // 抬起后完全归零
                         *
                         * v82：以前这里硬编码「亮色 7% 纯黑 / 深色 10% 白」，
                         * 所以选中块是一块**灰**，而 Flutter 版是跟着主题色走的浅蓝。
                         * 原生没有 HCT，用「accent 20% 压到白上」等效 ——
                         * 蓝 #3B82F6 压 20% 得到 #D8E6FD，和 Flutter 那张截图的
                         * 浅蓝气泡基本同色，且换主题色时自动跟着变。
                         */
                        if (c.dark) {
                            Color.White.copy(alpha = 0.10f * (1f - lift))
                        } else {
                            // lerp 在 sRGB 上白↔accent 13%：蓝 #3B82F6 → #E4EDFE。
                            // 20% 那档实测比 Flutter 版明显更饱和（老大对比图一眼看出），
                            // prism 的 tone 90 + alpha 0.85 叠在玻璃上就这个深度。
                            lerp(Color.White, accent, 0.13f)
                                .copy(alpha = 0.85f * (1f - lift))
                        }
                    )
            )
            /*
             * ③ 玻璃边缘不再手搓：part / shine / line 三层已经 1:1 在
             *    PRISM_GLASS_AGSL 里（uEdge.y 色散、uLight.x×facing 上下亮边、
             *    uEdge.x hairline）。backdrop 里有真实胶囊边线，色散会把它们
             *    分离出彩色，不需要再叠一圈 sweep 彩环。
             *    静止态的 platter 淡底仍在上面那一层，用来认出选中态。
             */
            /*
             * ④ 气泡自身的轮廓亮边。源码 `_lensShadows` 只给一层投影，
             *    老大 23:55 要「上下边缘更亮」，所以在 shader 的 shine 之外
             *    再叠一条 1.15dp（≈ rimWidth 1.1）的竖向渐变亮边：
             *    上下沿最亮、四角收住，和 lens 的 fade 曲线同构。
             *    宽度/强度都跟 lift 走，静止态完全不出现。
             */
            // v80：纯色背景下 shader 的 shine 失效（glass ~= coverage，加光项差为 0），
            // 气泡几乎看不见（老大 14:32「设置页看不出来，书架页明显一点」）。
            // 补一条**单条低对比**轮廓：不是 v48 那条 0.92 白亮线（会和胶囊 rim
            // 叠成双层硬边），而是 10% 黑 / 18% 白、0.8dp，只在 lift 时淡入。
            // shader 构造失败时仍用旧的白亮线兜底。
            if (lift > 0.01f) {
                Canvas(Modifier.matchParentSize()) {
                    val outline = superellipseCapsulePath(size.width, size.height)
                    if (lensShader == null) {
                        drawPath(
                            outline,
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.92f),
                                0.10f to Color.White.copy(alpha = 0.10f),
                                0.90f to Color.White.copy(alpha = 0.10f),
                                1f to Color.White.copy(alpha = 0.92f)
                            ),
                            style = Stroke(width = 1.15f * oneDpPx),
                            alpha = lift
                        )
                    } else {
                        drawPath(
                            outline,
                            color = if (c.dark) Color.White.copy(alpha = 0.18f)
                                    else Color.Black.copy(alpha = 0.10f),
                            style = Stroke(width = 0.8f * oneDpPx),
                            alpha = lift
                        )
                    }
                }
            }
        }

        // ③ 图标与文字：拖动时跟着镜片逐项点亮，页面 selected 全程不变。
        //
        // FlClash 的 `_LensContent` / `_LensClip`（2055–2081 行）确实把图标分成
        // 镜片内（主题色）/ 镜片外（墨色）两层，按镜片曲线切开。**这里没搬**：
        // 实测半途拖动时「书架」两个汉字会被竖着切成左黑右紫，在这套纯色矢量
        // 图标上比 Flutter 版突兀得多，像是渲染错误而不是液态玻璃。保持整项变色。
        // 源码 `_slide` 跨项时更新 `_pressedIndex`，图标高亮跟它走；
        // 未按压时回到 widget.selectedIndex。
        // v80：以前用 pressedIndex 整项跳色，要等镜片中心完全跨进下一项才变
        // （老大 14:32「气泡接触到另一个图标时就应该由黑色变了，你是完全覆盖才变」）。
        // 现在按镜片与图标的**重叠比例**做颜色插值：边缘刚碰到就开始从 ink
        // 渐变到 accent，完全覆盖才满色；松手 lift->0 回到 selected 单项高亮。
        val active = lift > 0.01f
        val lensHalfIdx = (lensWpx / (2f * pitchPx)).coerceAtLeast(0.1f)
        val iconHalfIdx =
            (DockMetrics.ICON_SIZE * oneDpPx / (2f * pitchPx)).coerceAtLeast(0.05f) * 1.15f
        Row(
            Modifier
                .fillMaxWidth()
                .height(barH)
                /*
                 * `_LensContent` 的取样源：整行图标录进 iconLayer，同时照常画出来。
                 * 录的是**底栏局部坐标**，后面那块 lensContent 面板按
                 * (-lensXpx, -lensYpx) 平移贴回来，坐标天然对齐。
                 */
                .drawWithContent {
                    if (size.width > 0f && size.height > 0f) {
                        iconLayer.record { this@drawWithContent.drawContent() }
                    }
                    /*
                     * `_LensPart.outside`（navigation_dock.dart:1376）：镜片范围内
                     * **不画图标**，那一块交给 ⑤ 的折射面板。源码就是把它拆成
                     * outside / inside 两份的 —— 不拆的话，镜片里的图标会变成
                     * 「清晰一份 + 折射一份」的重影，而且折射采样会把镜片外的
                     * 邻居图标也拉进来，边缘糊成一团。
                     */
                    if (lift > 0.01f) {
                        val lensShape = superellipseCapsulePath(
                            with(density) { lensW.toPx() },
                            with(density) { lensH.toPx() }
                        )
                        val lensInDock = Path().apply {
                            addPath(lensShape, Offset(lensXpx, lensYpx))
                        }
                        val full = Path().apply {
                            addRect(Rect(0f, 0f, size.width, size.height))
                        }
                        val outside = Path().apply {
                            op(full, lensInDock, PathOperation.Difference)
                        }
                        clipPath(outside) { this@drawWithContent.drawContent() }
                    } else {
                        this@drawWithContent.drawContent()
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockTab.entries.forEach { tab ->
                val t = if (!active) {
                    if (tab.ordinal == selected.ordinal) 1f else 0f
                } else {
                    val d = abs(lensPos - tab.ordinal.toFloat())
                    ((lensHalfIdx + iconHalfIdx - d) / (2f * iconHalfIdx)).coerceIn(0f, 1f)
                }
                Box(
                    Modifier.weight(1f).height(barH),
                    contentAlignment = Alignment.Center
                ) {
                    TabContent(tab, lerp(c.ink, accent, t))
                }
            }
        }

        /*
         * ⑤ `_LensContent` 的第二块玻璃（navigation_dock.dart:1540-1590 的
         *    `warp` 分支）：把刚录好的图标层再折一次。
         *
         *    源码结构是「图标整行画在镜片裁剪里 + 上面压一块 lensContent 面板」，
         *    所以镜片范围内的图标被折、范围外原样。Compose 这里等价实现：
         *    在镜片矩形上再放一个节点，内容 = 录好的图标层，效果 = lensContent。
         *
         *    它的参数是**正**折射（refraction +14、bevel 11.2、dispersion 3.6），
         *    走 frag 的凹陷 bevel 分支：越靠边折得越狠，于是图标在气泡边缘被
         *    拉成弧形并分离出彩边 —— 就是老大 23:55 截图里那圈「边缘变形」。
         *    只用 `lens` 折 backdrop 是折不到图标的：图标画在气泡上面。
         */
        if (lift > 0.01f) {
            val contentShader = lensContentShader
            /*
             * 采样余量：`refraction 14dp + dispersion 3.6dp ≈ 18dp`，取 20dp。
             * 这一圈**只用于采样**（读镜片外的邻居图标），最后被外层 clip 裁掉；
             * 没有它的话，镜片边缘往外采样会被 clamp 成边缘像素，图标就变成
             * 一条硬舌头，而不是 FlClash 那种「内容被吸进边缘」的弧形拉丝。
             */
            /*
             * v84：采样余量从 20dp 改成 0。
             *
             * 原来这里把节点外扩 20dp，想读到镜片外的邻居图标。但外扩后的节点
             * 超出了祖先 `clip(LensSuperellipse)` 的范围，离屏缓冲被裁回镜片大小，
             * shader 的坐标却仍按扩展后的节点算 —— 和底栏胶囊那次是同一个坑
             * （底栏表现为「上透下不透」）。
             * lensContent 是**正**折射（采样点往里挤），本来就不需要往外读，
             * 去掉余量后折射照样成立，而且不会再错位。
             */
            val padDp = 0.dp
            val padPx = with(density) { padDp.toPx() }
            Box(
                Modifier
                    .lensPlacement(lensXpx, lensYpx)
                    .requiredSize(lensW, lensH)
                    .clip(LensSuperellipse)
            ) {
                Box(
                    Modifier
                        .lensPlacement(-padPx, -padPx)
                        .requiredSize(lensW + padDp * 2, lensH + padDp * 2)
                        .graphicsLayer {
                            // v97：「无」档图标不折射，直接贴 —— shader 一律不跑
                            if (contentShader != null && liquid) {
                                PrismLiquidGlass.applyTo(
                                    contentShader,
                                    LiquidGlassStyle.lensContent(c.dark).scale(lift),
                                    size.width, size.height, oneDpPx, padPx
                                )
                                renderEffect = RenderEffect
                                    .createRuntimeShaderEffect(contentShader, "uContent")
                                    .asComposeRenderEffect()
                            }
                        }
                        .drawBehind {
                            translate(
                                left = -lensXpx + padPx,
                                top = -lensYpx + padPx
                            ) { drawLayer(iconLayer) }
                        }
                )
            }
        }
    }
}

@Composable

private fun TabContent(tab: DockTab, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(DockMetrics.ICON_SIZE.dp)) { drawDockGlyph(tab, tint) }
        Text(
            text = tab.label,
            style = TextStyle(
                fontSize = DockMetrics.LABEL_SIZE.sp,
                color = tint,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/*
 * v82：三个图标改成**直接解析 Flutter 版那三条 SVG 路径**
 * （`lib/main.dart` 的 _glyphBooks / _glyphFolder / _glyphGear，24×24 网格）。
 *
 * 以前这里各画一套：书架 4 根高矮细柱、文件夹是「开口带横线」、齿轮 8 齿大孔 ——
 * 跟 Flutter 版并排一看就知道不是同一套图标。现在路径逐字照抄，
 * PathParser 解析后按 24 网格缩放到 ICON_SIZE，两边是同一张图。
 *
 * ⚠️ Flutter 的 GlyphPainter 填完色**还会用 1.8 描边沿轮廓再描一遍**
 * （body 路径是中心线，视觉外沿 = 路径 ± 0.9）。这里同样补一层同色 stroke，
 * 否则字重会比 Flutter 版细一圈，看着像换了一套细体图标。
 */

/** 带页签的文件夹（iOS folder.fill）。 */
private const val GLYPH_FOLDER =
    "M3.2 9.6 C3.2 8.4 4.2 7.4 5.4 7.4 L9.2 7.4 " +
        "C9.9 7.4 10.5 7.7 11 8.2 L11.8 9 L18.6 9 " +
        "C19.8 9 20.8 10 20.8 11.2 L20.8 17.4 " +
        "C20.8 18.6 19.8 19.6 18.6 19.6 L5.4 19.6 " +
        "C4.2 19.6 3.2 18.6 3.2 17.4 Z"

/**
 * 8 齿齿轮 + 中间镂空（gearshape.fill）。
 *
 * 外圈是「齿顶 9.3 / 齿根 6.9、齿宽 44%、肩部 12%」生成的折线（64 点，
 * 已做共线点简化）；内圈是**逆时针**圆 —— 顺时针外圈 + 逆时针内圈靠
 * nonZero 填充规则才会成洞，同向画就是一块实心饼。PathParser 出来的
 * 路径默认就是 nonZero，缩放不改变 winding，这里不用额外处理。
 */
private const val GLYPH_GEAR =
    "M12 2.7 L14.4 3 L15.1 3.2 L15.2 3.6 L15 5.8 L16.3 6.6 L16.6 6.6 " +
        "L18.1 5.6 L18.6 5.4 L20.1 7.4 L20.4 8 L20.2 8.3 L18.5 9.7 " +
        "L18.9 11.3 L19.1 11.4 L20.8 11.8 L21.3 12 L21 14.4 L20.8 15.1 " +
        "L20.4 15.2 L18.2 15 L17.4 16.3 L17.4 16.6 L18.4 18.1 L18.6 18.6 " +
        "L16.6 20.1 L16 20.4 L15.7 20.2 L14.3 18.5 L12.7 18.9 L12.6 19.1 " +
        "L12.2 20.8 L12 21.3 L9.6 21 L8.9 20.8 L8.8 20.4 L9 18.2 L7.7 17.4 " +
        "L7.4 17.4 L5.9 18.4 L5.4 18.6 L3.9 16.6 L3.6 16 L3.8 15.7 " +
        "L5.5 14.3 L5.1 12.7 L4.9 12.6 L3.2 12.2 L2.7 12 L3 9.6 L3.2 8.9 " +
        "L3.6 8.8 L5.8 9 L6.6 7.7 L6.6 7.4 L5.6 5.9 L5.4 5.4 L7.4 3.9 " +
        "L8 3.6 L8.3 3.8 L9.7 5.5 L11.3 5.1 L11.4 4.9 L11.8 3.2 Z " +
        "M15.2 12 C15.2 10.3 13.7 8.8 12 8.8 C10.3 8.8 8.8 10.3 8.8 12 " +
        "C8.8 13.7 10.3 15.2 12 15.2 C13.7 15.2 15.2 13.7 15.2 12 Z"

/** Flutter `_glyphBooks` 的三个 GlyphBox：左, 上, 右, 下（圆角 0.3）。 */
private val GLYPH_BOOKS = floatArrayOf(
    2.8f, 5.6f, 6.6f, 20.0f,
    10.2f, 3.6f, 14.0f, 20.0f,
    17.6f, 7.0f, 21.4f, 20.0f,
)

/** 24×24 的 SVG path → 缩放到当前画布（`k` = 每格多少 px）。 */
private fun svgToPath(d: String, k: Float): Path =
    PathParser().parsePathString(d).toPath().apply {
        transform(Matrix().apply { scale(k, k) })
    }

private fun DrawScope.drawDockGlyph(tab: DockTab, tint: Color) {
    val k = size.minDimension / 24f
    val outline = Stroke(
        width = 1.8f * k,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )
    // 路径在这套引擎里是中心线，fill + 同色 stroke 才是 Flutter 的视觉外沿
    fun paint(path: Path) {
        drawPath(path, tint)
        drawPath(path, tint, style = outline)
    }
    when (tab) {
        DockTab.BOOKS -> {
            val p = Path()
            var i = 0
            while (i < GLYPH_BOOKS.size) {
                p.addRoundRect(
                    RoundRect(
                        Rect(
                            GLYPH_BOOKS[i] * k,
                            GLYPH_BOOKS[i + 1] * k,
                            GLYPH_BOOKS[i + 2] * k,
                            GLYPH_BOOKS[i + 3] * k
                        ),
                        CornerRadius(0.3f * k)
                    )
                )
                i += 4
            }
            paint(p)
        }

        DockTab.FILES -> {
            /*
             * v98：原来是「带页签的文件夹」，对应的是「文件」这个页签。
             * 现在页面变成了「资源库」（多台服务器的卡片墙），继续画文件夹会误导 ——
             * 对齐 iOS 用的 `square.stack.3d.up.fill`：一块实心方片 + 后面两层只描线，
             * 由远到近画，前面那块填色自然把后面压掉一半，层次就出来了。
             * 后两层如果也填色，三块会糊成一整坨。
             */
            for (i in 2 downTo 1) {
                val off = i * 1.9f * k
                val back = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(
                                4.2f * k + off, 8.6f * k - off,
                                16.2f * k + off, 20.2f * k - off
                            ),
                            CornerRadius(2.4f * k)
                        )
                    )
                }
                drawPath(back, tint, style = outline)
            }
            paint(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(4.2f * k, 8.6f * k, 16.2f * k, 20.2f * k),
                            CornerRadius(2.4f * k)
                        )
                    )
                }
            )
        }

        DockTab.SETTINGS -> paint(svgToPath(GLYPH_GEAR, k))
    }
}

