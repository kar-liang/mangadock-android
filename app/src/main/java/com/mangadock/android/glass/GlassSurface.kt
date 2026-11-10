package com.mangadock.android.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v37 玻璃材质，照 SenPlayer 的做法搬过来。
 *
 * 出处：SenPlayer 的 APK 里自带 `assets/licenses/senplayer-glass-NOTICE.txt`，
 * 写明玻璃用的是 Kyant0 的 **AndroidLiquidGlass / Backdrop**（外加 `Shapes`），
 * 并注明改写的 `LiquidTabBar.kt` / `GlassMotion.kt` 来自上游 LiquidBottomTabs
 * 与 DampedDragAnimation 示例。dex 里能扒到两类 AGSL：
 *
 *  ① 合成：`uniform shader content; uniform shader noise; tintColor`
 *     → 模糊内容 + noise 做 soft-light + tint 叠上去
 *  ② 折射：圆角矩形 SDF，沿法线把采样点往里推（`refractionHeight` /
 *     `refractionAmount` / `depthEffect`），边缘那几 dp 像玻璃棱镜
 *
 * 这次只搬**看得见的那一层**：内容录制 + GPU 真模糊 + tint + 上沿高光。
 * ②先不做 —— 它只在边缘几个 dp 内可见，却要多挂一个 image shader、整块图层
 * 再重采样一遍。v13 就是被「整页软件位图 + AGSL 折射」拖死的（每帧重栅格化
 * 整屏再多次采样，输入延迟高到不跟手）。
 *
 * 和 v13 那次失败的根本区别：**录的是硬件 GraphicsLayer（RenderNode），不是
 * 软件 Bitmap**。内容只渲进 RenderNode 一次，模糊是 GPU 在这个节点上做的，
 * 不回 CPU、不做像素回读。
 */
class GlassSource {
    /** 内容录进哪一层。由 [Modifier.glassSource] 在每帧绘制时填充。 */
    var layer: GraphicsLayer? = null

    /** 这一层在根坐标里的原点 —— 玻璃节点靠它把自己换算到同一坐标系。 */
    var origin: Offset = Offset.Zero
}

/** 建一个玻璃源，挂在要「被玻璃看到」的那棵子树上。 */
@Composable
fun rememberGlassSource(): GlassSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassSource().also { it.layer = layer } }
}

/**
 * 把这一层画的内容录进 [GlassSource]，再原样贴回屏幕。
 *
 * 结尾是 `drawLayer(layer)` 而不是再 `drawContent()`：录一次、贴一次，内容只画一遍。
 * 写成 `drawContent()` 的话内容会画两遍（一遍进层，一遍上屏）。
 */
fun Modifier.glassSource(source: GlassSource): Modifier =
    this
        .onGloballyPositioned { source.origin = it.positionInRoot() }
        .drawWithContent {
            val layer = source.layer
            if (layer != null && size.width > 0f && size.height > 0f) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else {
                this@drawWithContent.drawContent()
            }
        }

/**
 * 玻璃面。
 *
 * 画法（从下往上）：
 *  ① 采样 [source] 里**同一块区域**、已经模糊过的内容
 *  ② tint（半透明染色）
 *  ③ 上沿亮边 + 1dp 内描边 —— 玻璃的「厚度」全靠这道边，
 *     没有它就是个半透明色块（这是看了 SenPlayer 深色截图才定下来的：
 *     它胶囊上沿那条细白边比模糊本身更决定「像不像玻璃」）
 */
@Composable
fun GlassSurface(
    source: GlassSource?,
    shape: Shape,
    tint: Color,
    rim: Color,
    modifier: Modifier = Modifier,
    blur: Dp = 26.dp,
    /**
     * 要不要画那圈 1dp 内描边 + 上沿高光。
     *
     * 胶囊（底栏）必须画 —— 悬空的一块半透明色块靠这两道边才有「厚度」。
     * 顶栏是**通栏到底**的一条，两条竖边和上沿都在屏幕外，画上去只会
     * 在屏幕左右边缘露出两道白线，反而像贴歪了，所以那里要关掉。
     */
    edges: Boolean = true,
    /**
     * 上沿高光强度，0f = 完全不画。
     *
     * v48c：深色模式下这道白雾在纯黑底上会被放大成**一条硬亮带**，横贯胶囊
     * 上 28%、下边界笔直，看起来像又贴了一层底栏（老大在深色截图里一眼看出来了）。
     * 浅色底上白雾本来就看不见，保持 1f；深色只留 10% 意思一下，玻璃厚度交给
     * 那圈 1dp 内描边就够了。
     */
    highlight: Float = 1f,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val blurPx = with(density) { blur.toPx() }
    var selfAt by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .clip(shape)
            .onGloballyPositioned { selfAt = it.positionInRoot() }
    ) {
        val layer = source?.layer
        if (layer != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        /*
                         * 模糊挂在这个节点上，节点又被父级 clip 成胶囊形状 ——
                         * 所以糊的只有胶囊底下那一块，不会把整屏都糊掉。
                         * TileMode.Clamp：胶囊边缘的采样点落到图层外面时取边上的像素，
                         * 否则边缘会因为采到透明而发暗，看起来像描了一圈灰边。
                         */
                        /*
                         * clip = true 是性能上的关键：下面 drawWithContent 里画的是
                         * **整屏**那一层（1080×1920），节点自己只有胶囊大小。
                         * 不裁的话 Skia 给 renderEffect 建的离屏缓冲会按内容边界来，
                         * 等于每帧把整屏模糊一遍 —— 实测 783 项目录甩动 90th 从 12ms
                         * 涨到 18ms，主因就是这里。裁到节点大小后只糊那一块。
                         */
                        clip = true
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    }
                    .drawWithContent {
                        val o = selfAt - source.origin
                        translate(left = -o.x, top = -o.y) { drawLayer(layer) }
                    }
            )
        }

        // ② tint：只染一层色，底下模糊过的画面还要透出来
        Box(Modifier.matchParentSize().background(tint))

        // ③ 描边 + 上沿高光
        if (edges) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to rim,
                                0.45f to rim.copy(alpha = rim.alpha * 0.30f),
                                1f to rim.copy(alpha = rim.alpha * 0.14f)
                            ),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        /*
                         * 上沿高光只在最上面一小条，别铺满上半个胶囊。
                         * 原来是 85%×rim 铺 55% 高度，底栏 tint 还厚的时候看不出来；
                         * tint 改成近透明后，这层白雾会把刚透出来的内容重新盖成白塑料。
                         *
                         * 末端必须用 rim.copy(alpha = 0f)（透明白），不能用
                         * Color.Transparent —— 那是**透明黑**，插值中段会把白雾拉灰，
                         * 在深色底上正好形成一条上亮下暗的硬分界线。
                         */
                        if (highlight > 0f) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to rim.copy(alpha = rim.alpha * 0.28f * highlight),
                                    0.55f to rim.copy(alpha = rim.alpha * 0.10f * highlight),
                                    1f to rim.copy(alpha = 0f)
                                ),
                                topLeft = Offset.Zero,
                                size = Size(size.width, size.height * 0.28f)
                            )
                        }
                    }
            )
        }

        content()
    }
}
