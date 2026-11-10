package com.mangadock.android.ui.prism

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.ui.MangaTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * FlClash `popup.dart` 的 1:1 镜像（菜单弹出的 UI / 动画）。
 *
 * 源码已物理搬到 `_refs/flclash_ui/popup.dart`（2980 行）。搬的是这些，不是新设计：
 *  - `:32-126` 全部几何与时长常量（见 [PrismPopupSpec]）
 *  - `:128-162` `CommonPopupRoute` 字段（placement / avoid / modal / sourceImage）
 *  - `:1192-1274` `_PopupLayoutDelegate.getPositionForChild` + `_yBelowPoint`
 *  - `:704-755` `_openingOf`：双弹簧 platter morph（400/.754 + 324/.75 延迟 .07s）
 *  - `:681-701` 无 sourceImage 的退出：200ms / easeOut / 缩到 0.8 / 向锚点淡出
 *  - `:1732-1778` `CommonPopupMenuItem` / `CommonPopupMenuSection` 数据模型
 *  - `:2258-2358` `_buildRow` 行规格
 *  - `:2418,2458` 分隔线 17dp / 1dp
 *  - `liquid_glass.dart:172-190,444-479` `LiquidGlassStyle.menu` / `menuShadows` /
 *    `LiquidGlassRim.menu`
 *
 * 框架差异（明确列出，不假装完全等价）：
 *  - `RoundedSuperellipseBorder` 由 material_ui 包提供，Compose 无对应物。
 *    这里复用工程 v51 已按 prism_ui 搬过的超椭圆角参数（`GlassDock.kt:103-131`，n=2）。
 *  - `BoxShadow(blurRadius 29, offset(0,8), alpha .118)` 用 Android `BlurMaskFilter`
 *    精确绘制；`saturation / brightness / contrast` 属于玻璃 AGSL，
 *    [PrismPopupSpec] 保留全部原值，当前 backdrop 由 `GlassSurface` 提供。
 *  - `Glyph` 用工程自绘矢量/文字代替，尺寸间距仍按源码 20/16/12/8dp。
 */

// ---------------------------------------------------------------- 常量

/** `popup.dart:32-126` 与 `liquid_glass.dart:172-190,444-479` 原值。 */
object PrismPopupSpec {
    const val SCREEN_MARGIN = 16f
    const val ANCHOR_OVERLAP = 8f
    const val AVOID_GAP = 8f
    const val CARD_INSET = 8f
    const val ITEM_RADIUS = 16f          // AppCorner.md
    const val CARD_RADIUS = ITEM_RADIUS + CARD_INSET // 24
    const val ITEM_ICON_SIZE = 20f
    const val SUBMENU_ARROW_SIZE = 16f
    const val ITEM_PADDING_H = 12f
    const val ITEM_PADDING_V = 12f
    const val ITEM_ARROW_PADDING_RIGHT = 8f
    const val ITEM_ICON_GAP = 12f
    const val ITEM_ARROW_GAP = 8f
    const val DIVIDER_HEIGHT = CARD_INSET * 2 + 1f // 17
    const val MENU_MIN_WIDTH = 160f
    const val MENU_MAX_WIDTH = 280f
    const val DEFAULT_MAX_VISIBLE_ITEMS = 7
    const val ROW_EXTENT = 44f           // _itemPadding.vertical(24) + subheadline.leading(20)
    const val SECTION_TITLE_LEFT = 12f
    const val SECTION_TITLE_TOP = 8f
    const val SECTION_TITLE_BOTTOM = 2f

    const val MAX_CONTENT_BLUR = 8f
    const val OPEN_CONTENT_BLUR = 4f
    const val OPEN_BLUR_BUMP = 1.9f
    const val UNSEEN_BLUR = 0.1f
    const val PLATTER_IN_CONTROL = 0.41f
    const val DROPLET_BLEND = 28f
    const val DROPLET_SHARE = 0.25f
    const val RIM_DEPTH = 8f
    const val DISMISS_SCALE = 0.8f
    const val CORNERS_DELAY = 0.07f
    const val CORNERS_REACH_PER_WIDTH = 0.48f

    // LiquidGlassStyle.menu (liquid_glass.dart:172-190)
    const val MENU_BLUR = 12f
    const val MENU_TINT_LIGHT = 0.55f
    const val MENU_TINT_DARK = 0.63f
    const val MENU_SATURATION_LIGHT = 2f
    const val MENU_SATURATION_DARK = 1.75f
    const val MENU_BRIGHTNESS_LIGHT = 1.1f
    const val MENU_BRIGHTNESS_DARK = 1.8f
    const val MENU_CONTRAST_DARK = -0.53f

    // menuShadows (liquid_glass.dart:446-455)
    const val SHADOW_ALPHA = 0.118f
    const val SHADOW_BLUR = 29f
    const val SHADOW_OFFSET_Y = 8f
    const val SHADOW_DARK_STRENGTH = 1.5f

    // LiquidGlassRim.menu (liquid_glass.dart:476-479): side / corner / flat / shine
    val RIM_LIGHT = floatArrayOf(0.25f, 0.15f, 0.09f, 0.16f)
    val RIM_DARK = floatArrayOf(0.27f, 0.10f, 0.02f, 0.22f)
}

// ---------------------------------------------------------------- 数据模型

/** `popup.dart:1757-1761` */
data class PrismPopupSection(val title: String? = null)

/** `popup.dart:1732-1755`（glyph 用 String 代替源码的 Glyph 枚举）。 */
data class PrismPopupItem(
    val label: String,
    val subtitle: String? = null,
    val glyph: String? = null,
    val onPressed: (() -> Unit)? = null,
    val danger: Boolean = false,
    val checked: Boolean? = null,
    val section: PrismPopupSection? = null,
    val subItems: List<PrismPopupItem> = emptyList(),
)

/** `popup.dart:30` */
enum class PrismPlacement { OVER_ANCHOR_END, OVER_ANCHOR_START, BELOW_POINT }

data class PrismPopupInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object { val Zero = PrismPopupInsets(0f, 0f, 0f, 0f) }
}

// ---------------------------------------------------------------- 布局（1:1）

/** `popup.dart:1258-1274` `_yBelowPoint`，avoid 优先「行上方 → 行下方」。 */
private fun yBelowPoint(
    anchor: Rect, avoid: Rect?, height: Float, minY: Float, maxY: Float,
): Float {
    if (avoid != null) {
        val above = avoid.top - PrismPopupSpec.AVOID_GAP - height
        if (above >= minY) return above
        val below = avoid.bottom + PrismPopupSpec.AVOID_GAP
        if (below <= maxY) return below
    }
    if (anchor.bottom > maxY && anchor.top - height >= minY) return anchor.top - height
    return anchor.bottom
}

/** `popup.dart:1223-1256` `getPositionForChild`，含 safeInsets + 16 与双轴 clamp。 */
fun prismPopupPosition(
    anchor: Rect,
    avoid: Rect?,
    screen: Size,
    child: Size,
    placement: PrismPlacement = PrismPlacement.BELOW_POINT,
    safe: PrismPopupInsets = PrismPopupInsets.Zero,
): Offset {
    val m = PrismPopupSpec.SCREEN_MARGIN
    val insets = PrismPopupInsets(safe.left + m, safe.top + m, safe.right + m, safe.bottom + m)
    val maxX = screen.width - insets.right - child.width
    val maxY = screen.height - insets.bottom - child.height
    val (x, y) = when (placement) {
        PrismPlacement.OVER_ANCHOR_END -> Offset(
            anchor.right - child.width,
            anchor.top - PrismPopupSpec.ANCHOR_OVERLAP,
        )
        PrismPlacement.OVER_ANCHOR_START -> Offset(
            anchor.left,
            anchor.top - PrismPopupSpec.ANCHOR_OVERLAP,
        )
        PrismPlacement.BELOW_POINT -> Offset(
            anchor.left,
            yBelowPoint(anchor, avoid, child.height, insets.top, maxY),
        )
    }
    val minY = if (placement == PrismPlacement.BELOW_POINT) insets.top
    else maxOf(safe.top, minOf(insets.top, y))
    return Offset(
        x.coerceIn(insets.left, maxOf(insets.left, maxX)),
        y.coerceIn(minY, maxOf(minY, maxY)),
    )
}

/**
 * `AppShape.all(radius)` 的圆角超椭圆 Path。角参数沿用工程 v51 已按
 * `prism_shape.dart` / `AppShape.full` 搬过的 `GlassDock.kt:103-131`（n=2 → 圆弧，
 * 实测与 FlClash 参考图一致）；半径不是 1000 的极端值，四角各自按 radius 画。
 */
fun prismSuperellipsePath(size: Size, radiusPx: Float, n: Float = 2f): Path {
    val w = size.width
    val h = size.height
    val r = minOf(radiusPx, w * 0.5f, h * 0.5f).coerceAtLeast(0f)
    val e = 2f / n
    val steps = 12
    val q = (PI / 2.0).toFloat()

    /** 超椭圆参数：`sign(v) * |v|^e`，n=2 时就是圆弧。 */
    fun se(t: Float, x: Boolean): Float {
        val v = if (x) cos(t) else sin(t)
        return (if (v >= 0f) 1f else -1f) * abs(v).pow(e)
    }

    return Path().apply {
        moveTo(r, 0f)
        lineTo(w - r, 0f)
        // 四个角各自以 (r,r)/(w-r,r)/(w-r,h-r)/(r,h-r) 为圆心。
        // ⚠️ 不能沿用 GlassDock.superellipseCapsulePath 的 `cy = h/2`：
        // 那份只适用于 r == h/2 的胶囊；菜单 r=24dp << h/2，照搬会把
        // 角画到中线去，形成斜边和左右两侧凸出的假半圆。
        for (i in 1..steps) {
            val t = -q + q * i / steps
            lineTo(w - r + r * se(t, true), r + r * se(t, false))
        }
        lineTo(w, h - r)
        for (i in 1..steps) {
            val t = q * i / steps
            lineTo(w - r + r * se(t, true), h - r + r * se(t, false))
        }
        lineTo(r, h)
        for (i in 1..steps) {
            val t = q + q * i / steps
            lineTo(r + r * se(t, true), h - r + r * se(t, false))
        }
        lineTo(0f, r)
        for (i in 1..steps) {
            val t = PI.toFloat() + q * i / steps
            lineTo(r + r * se(t, true), r + r * se(t, false))
        }
        close()
    }
}

private fun prismShape(radiusPx: Float): Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = androidx.compose.ui.graphics.Outline.Generic(prismSuperellipsePath(size, radiusPx))
}

// ---------------------------------------------------------------- 菜单本体

/**
 * @param anchor 被长按那一格的 bounds（root 坐标，px）；`context_menu.dart:87-104`
 *   用 `point = globalToLocal(globalPosition)` + `avoid = rowRect`。
 * @param avoid  需要避开的 rect（就是被长按的行），对应 `avoid: true`。
 */
@Composable
fun PrismPopupCard(
    anchor: Rect,
    items: List<PrismPopupItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    avoid: Rect? = anchor,
    placement: PrismPlacement = PrismPlacement.BELOW_POINT,
    glass: GlassSource? = null,
) {
    val c = MangaTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // `:95-106` 两条弹簧，按时间求值（源码 `_open.x(time)` / `_corners.x(time-0.07)`）
    val openSim = remember { PrismSpringSim(PrismSprings.POPUP_OPEN, 0f, 1f, 0f) }
    val cornersSim = remember { PrismSpringSim(PrismSprings.POPUP_CORNERS, 1f, 0f, 0f) }
    val openProgress = remember { Animatable(0f) }
    val dismiss = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        openProgress.animateTo(
            1f, tween(PrismDuration.POPUP_OPEN, easing = LinearEasing),
        )
    }

    fun close(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            // `:681-701` 无 sourceImage → 200ms / easeOut / 缩到 0.8 向锚点淡出
            dismiss.animateTo(1f, tween(PrismDuration.POPUP_SHRINK, easing = PrismCurves.easeOut))
            action()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screen = Size(
            with(density) { maxWidth.toPx() },
            with(density) { maxHeight.toPx() },
        )
        var cardSize by remember { mutableStateOf(Size.Zero) }
        val position = prismPopupPosition(
            anchor = anchor,
            avoid = avoid,
            screen = screen,
            child = cardSize,
            placement = placement,
        )

        // `:168` barrierColor = null，`barrierDismissible = true`：全屏点击层不变暗
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { close(onDismiss) }
        )

        val time = openProgress.value * PrismDuration.POPUP_OPEN / 1000f
        val p = openSim.x(time).coerceIn(0f, 1.2f)
        // `:704-755` _openingOf：platter 从锚点长大，高度由「卡片宽」变到「卡片高」
        val cardW = cardSize.width.coerceAtLeast(1f)
        val cardH = cardSize.height.coerceAtLeast(1f)
        val anchorShortest = minOf(anchor.width, anchor.height).coerceAtLeast(1f)
        val startScale = PrismPopupSpec.PLATTER_IN_CONTROL * anchorShortest / cardW
        val platterScale = startScale + (1f - startScale) * p
        val platterHeight0 = cardW
        val platterHeight = (platterHeight0 + (cardH - platterHeight0) * p) * platterScale
        val centerX = anchor.center.x + (position.x + cardW / 2f - anchor.center.x) * p
        val centerY = anchor.center.y + (position.y + cardH / 2f - anchor.center.y) * p
        val cornerGrowth = cornersSim.x(max(0f, time - PrismPopupSpec.CORNERS_DELAY))
        // `popup.dart:726-730` 的 corners 在**逻辑像素(dp)** 域算；cardW 是 px。
        // 之前把 px 当 dp 又乘了一次 density，radius 被 coerce 到 h/2 → 卡片变胶囊。
        val cardWDp = cardW / density.density
        val radiusDp = PrismPopupSpec.CARD_RADIUS +
            cardWDp * PrismPopupSpec.CORNERS_REACH_PER_WIDTH * cornerGrowth
        val radiusPx = (radiusDp * density.density).coerceIn(0f, platterHeight / 2f)

        val scaleX = platterScale
        val scaleY = platterHeight / cardH
        val tx = centerX - (position.x + cardW / 2f)
        val ty = centerY - (position.y + cardH / 2f)

        // `:857-877` 内容模糊 = sqrt((4*(1-settled))^2 + (1.9*riseAndFall(t,.069,2.1))^2)
        val settled = openProgress.value
        val bump = PrismPopupSpec.OPEN_BLUR_BUMP * prismRiseAndFall(time, 0.069f, 2.1f)
        val contentBlurDp = sqrt(
            (PrismPopupSpec.OPEN_CONTENT_BLUR * (1f - settled)).let { it * it } + bump * bump,
        )

        val shrink = PrismCurves.easeOut.transform(dismiss.value)
        val exitScale = 1f + (PrismPopupSpec.DISMISS_SCALE - 1f) * shrink
        val exitAlpha = 1f - shrink
        val originX = ((anchor.center.x - position.x) / cardW).coerceIn(0f, 1f)
        val originY = ((anchor.center.y - position.y) / cardH).coerceIn(0f, 1f)

        val shape = prismShape(radiusPx)
        val shadowAlpha = PrismPopupSpec.SHADOW_ALPHA *
            (if (c.dark) PrismPopupSpec.SHADOW_DARK_STRENGTH else 1f)
        val shadowBlurPx = with(density) { PrismPopupSpec.SHADOW_BLUR.dp.toPx() }
        val shadowOffsetPx = with(density) { PrismPopupSpec.SHADOW_OFFSET_Y.dp.toPx() }

        Box(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .onSizeChanged { cardSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .graphicsLayer {
                    this.scaleX = scaleX * exitScale
                    this.scaleY = scaleY * exitScale
                    translationX = tx
                    translationY = ty
                    alpha = exitAlpha
                    // 打开态必须绕中心缩放（platter 的 translation 是按中心算的），
                    // 只有退出时才把原点挪到锚点，否则打开态会被拽出斜边/错位。
                    transformOrigin = if (closing) TransformOrigin(originX, originY)
                    else TransformOrigin.Center
                }
                // `liquid_glass.dart:446-455` menuShadows：单条宽阴影、下移 8dp、暗色 ×1.5
                .drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = android.graphics.Color.argb(
                                (shadowAlpha * 255).toInt().coerceIn(0, 255), 0, 0, 0,
                            )
                            maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
                        }
                        val path = prismSuperellipsePath(size, radiusPx).asAndroidPath()
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.translate(0f, shadowOffsetPx)
                        canvas.nativeCanvas.drawPath(path, paint)
                        canvas.nativeCanvas.restore()
                    }
                }
        ) {
            /*
             * v84：菜单改成**不透明卡片**，和 Flutter 版 `lib/quick_menu.dart` 同一档
             * （`scheme.surface` + 18dp 圆角 + 一层宽阴影）。
             *
             * 原因：原来这里是 `GlassSurface`（blur 12dp + 55% 白），在书架页这种
             * 彩色封面背景上，上半截能透出封面、下半截背后是浅色封面就整块发白，
             * 同一张卡片出现「一半透明一半磨砂」的观感 —— 老大截图里那三条菜单
             * 都是这个问题。玻璃只有在背后是深色/高对比内容时才好看，菜单这种
             * 需要读文字的面板不该赌背景。
             *
             * 换成实色后：任何背景上都是同一张干净卡片，也不会再每帧做离屏模糊。
             */
            Box(
                Modifier
                    .widthIn(
                        min = PrismPopupSpec.MENU_MIN_WIDTH.dp,
                        max = PrismPopupSpec.MENU_MAX_WIDTH.dp,
                    )
                    .clip(shape)
                    .background(c.surface)
                    /*
                     * v91：补上对话框那圈 1dp 描边。
                     *
                     * 两者 v84 起都是 `c.surface` 实色卡片，但对话框有描边、菜单没有 ——
                     * 同一套面板里菜单卡在浅色页面上边缘会糊进背景，看着像少了一层。
                     * 颜色与 PrismDialog 完全一致（深色白 8% / 浅色黑 5%）。
                     */
                    .border(
                        width = 1.dp,
                        color = if (c.dark) Color.White.copy(alpha = 0.08f)
                        else Color.Black.copy(alpha = 0.05f),
                        shape = shape,
                    )
            ) {
                Column(
                    Modifier
                        // `popup.dart:2555-2568` `IntrinsicWidth(child: _buildContent)` +
                        // `_resolveLevels():2012-2014` 的 minWidth 160 / maxWidth 280。
                        // 源码按「最长标签的固有宽度」自适应，不是撑满上限。
                        // 少了这一步，`PrismPopupRow.fillMaxWidth()` 会把卡片顶到
                        // 280dp —— 实测三行短标签被拉成 264dp 宽，比 FlClash 宽一大截。
                        .width(IntrinsicSize.Min)
                        .widthIn(
                            min = PrismPopupSpec.MENU_MIN_WIDTH.dp,
                            max = PrismPopupSpec.MENU_MAX_WIDTH.dp,
                        )
                        .padding(PrismPopupSpec.CARD_INSET.dp)
                        .graphicsLayer {
                            alpha = settled
                            if (contentBlurDp > PrismPopupSpec.UNSEEN_BLUR) {
                                val px = contentBlurDp * density.density
                                renderEffect = BlurEffect(px, px, TileMode.Clamp)
                            } else {
                                // graphicsLayer 块不会自动复位：不清掉会一直挂着
                                // 最后一帧的模糊，菜单文字永久发虚。
                                renderEffect = null
                            }
                        }
                ) {
                    items.forEachIndexed { index, item ->
                        if (index > 0 && item.section != null) {
                            item.section.title?.let { title ->
                                Text(
                                    title,
                                    Modifier.padding(
                                        start = PrismPopupSpec.SECTION_TITLE_LEFT.dp,
                                        top = PrismPopupSpec.SECTION_TITLE_TOP.dp,
                                        end = PrismPopupSpec.SECTION_TITLE_LEFT.dp,
                                        bottom = PrismPopupSpec.SECTION_TITLE_BOTTOM.dp,
                                    ),
                                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                                    color = c.sub,
                                )
                            }
                            // `:56,2418,2458` Divider(height 17, thickness 1)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(PrismPopupSpec.DIVIDER_HEIGHT.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = PrismPopupSpec.CARD_INSET.dp)
                                        .height(1.dp)
                                        .background(c.divider)
                                )
                            }
                        }
                        PrismPopupRow(item, close = { close(it) })
                    }
                }
            }
        }
    }
}

/** `popup.dart:2258-2358` `_buildRow` 行规格，逐值镜像。 */
@Composable
fun PrismPopupRow(item: PrismPopupItem, close: (() -> Unit) -> Unit) {
    val c = MangaTheme.colors
    val enabled = item.onPressed != null
    val fg = when {
        item.danger -> c.danger
        else -> c.ink
    }.copy(alpha = if (enabled) 1f else 0.30f)
    val arrow = when {
        item.subItems.isNotEmpty() -> "›"
        item.checked == true -> "✓"
        else -> null
    }
    val rightPad = if (arrow != null) PrismPopupSpec.ITEM_ARROW_PADDING_RIGHT
    else PrismPopupSpec.ITEM_PADDING_H

    Box(
        Modifier
            .fillMaxWidth()
            .height(PrismPopupSpec.ROW_EXTENT.dp)
            .clip(RoundedCornerShape(PrismPopupSpec.ITEM_RADIUS.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // `NoSplash.splashFactory` + highlightColor transparent
            ) {
                item.onPressed?.let { action -> close(action) }
            }
            .padding(
                start = PrismPopupSpec.ITEM_PADDING_H.dp,
                top = PrismPopupSpec.ITEM_PADDING_V.dp,
                bottom = PrismPopupSpec.ITEM_PADDING_V.dp,
                end = rightPad.dp,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.glyph != null) {
                Text(
                    item.glyph,
                    style = TextStyle(fontSize = PrismPopupSpec.ITEM_ICON_SIZE.sp),
                    color = fg,
                )
                androidx.compose.foundation.layout.Spacer(
                    Modifier.width(PrismPopupSpec.ITEM_ICON_GAP.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), // typography.dart:16
                    color = fg,
                )
                if (item.subtitle != null) {
                    Text(
                        item.subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        color = c.sub.copy(alpha = if (enabled) 1f else 0.30f),
                    )
                }
            }
            if (arrow != null) {
                androidx.compose.foundation.layout.Spacer(
                    Modifier.width(PrismPopupSpec.ITEM_ARROW_GAP.dp)
                )
                Text(
                    arrow,
                    style = TextStyle(fontSize = PrismPopupSpec.SUBMENU_ARROW_SIZE.sp),
                    color = fg,
                )
            }
        }
    }
}

