package com.mangadock.android.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 用 prism_ui 那套 AGSL 玻璃画一块**面板**（底栏胶囊 / 阅读器顶栏 / 弹窗都行）。
 *
 * 与 [GlassSurface] 的区别：
 *  · [GlassSurface] = 模糊 + 平涂 tint + 手画描边 —— 只“像”玻璃；
 *  · 这里 = [PrismLiquidGlass] 的真光学：边缘折射（凹面把背景往内挤）、
 *    按法线调制的上下沿亮边、侧面暗线、色散、clash 自动加厚。
 *
 * 两个必须知道的约定：
 *  1. `uContent` 只能采到**节点自身**的内容（Compose 的 RenderEffect 语义），
 *     所以 [source] 必须是「整屏内容层」（`Modifier.glassSource(...)` 录的那份），
 *     这里按 `selfAt - source.origin` 平移贴进来，等价于 Flutter 的 backdrop capture。
 *  2. Flutter 的 haze 是「同一张图集里另一块预先糊好的 band」，采样两次再 mix。
 *     Compose 只有一个 uContent，所以改成**在源图层里预混**：
 *     先画清晰的一份，再叠一份模糊的、alpha = haze（0.72）。
 *     因为混合权重是常数、两次采样用的是同一个位移场，
 *     `mix(S(p+shift), H(p+shift), w)` 与 `(mix(S,H,w))(p+shift)` 完全相等 ⇒ 数学等价。
 *
 * @param haze 0 = 全清晰；0.72 = prism_ui `pill` 的雾面比例
 * @param hazeBlur 预模糊的 sigma（prism_ui 的 `hazeBlur`，15.6dp）
 */
@Composable
fun PrismGlassBar(
    source: GlassSource?,
    style: LiquidGlassStyle,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 50),
    haze: Float = 0f,
    hazeBlur: Dp = 15.6.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val densityF = density.density
    val hazePx = with(density) { hazeBlur.toPx() }
    /*
     * v84：采样余量固定 0 —— 这里曾经是 `hazePx * 2.5f`（≈39dp），把节点向外扩
     * 一圈来「预糊」边缘。**那是底栏「上半截透明、下半截磨砂」的真正原因**：
     *
     *   host 节点比胶囊大 2*pad，但它的祖先 `BoxWithConstraints` 带着
     *   `clip(pill)`；Skia 给 `renderEffect` 建的离屏缓冲按「节点 bounds ∩ 祖先 clip」
     *   来分配，于是缓冲只有胶囊那么大，而 shader 的 `p`、`uSize` 仍按扩展后的
     *   节点算 —— 坐标整体偏了 pad，胶囊靠下的 2/3 采样落在缓冲外，被 clamp 成
     *   边缘像素，只剩 tint，看起来就是一块不透明白。
     *
     *   实测：把 pad 归零、内容换成纯红 → 上下立刻一致；恢复真实内容也一致。
     *
     * 而且 pill 本来就不需要这圈余量：折射是**正**的（凹陷面把采样点往内挤），
     * 采样点始终落在胶囊内部，永远读得到真实背景；只有 lens（负折射、向外读）
     * 才需要外扩，那在 GlassDock 里是另一块单独的面板。
     *
     * 代价：雾面那层的 BlurEffect 在胶囊边缘只能靠 TileMode.Clamp 拉边像素。
     * 实测 15.6dp 的 σ 下看不出来（那圈本来就被 rim shine 和 hairline 压住）。
     */
    val padPx = 0f
    // 采样平移用的节点原点（pad 为 0 时就是胶囊左上角）
    var nodeAt by remember { mutableStateOf(Offset.Zero) }
    // RuntimeShader 是**设备侧**编译：gradle 过不代表 Skia 过。构造失败就静默退回不画玻璃，
    // 别让底栏整块消失（和 GlassDock 里 lens 的处理保持一致）。
    val shader = remember { runCatching { RuntimeShader(PRISM_GLASS_AGSL) }.getOrNull() }
    val layer = source?.layer

    BoxWithConstraints(
        modifier.clip(shape)
    ) {
        val padDp = with(density) { padPx.toDp() }
        Box(
            Modifier
                // pad = 0 时等价于铺满胶囊；留着这对 offset/requiredSize 是为了
                // 万一以后某处真需要外扩（那时必须同时保证祖先不 clip，见上面的注释）
                .offset(x = -padDp, y = -padDp)
                .requiredSize(
                    width = maxWidth + padDp * 2,
                    height = maxHeight + padDp * 2,
                )
                .onGloballyPositioned { nodeAt = it.positionInRoot() }
                .graphicsLayer {
                    // clip = true 是性能红线：下面画的是整屏那一层（1080×1920），
                    // 不裁的话 Skia 会按内容边界给 renderEffect 建缓冲，等于每帧糊整屏。
                    clip = true
                    val s = shader
                    if (s != null && size.width > 0f && size.height > 0f) {
                        PrismLiquidGlass.applyTo(
                            s, style, size.width, size.height, densityF, padPx
                        )
                        renderEffect = RenderEffect
                            .createRuntimeShaderEffect(s, "uContent")
                            .asComposeRenderEffect()
                    }
                }
        ) {
            if (layer != null) {
                // ① 清晰的一份
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            val o = nodeAt - (source?.origin ?: Offset.Zero)
                            translate(left = -o.x, top = -o.y) { drawLayer(layer) }
                        }
                )
                // ② 雾面的一份：模糊挂在只有胶囊大小的节点上，只糊这一块
                if (haze > 0f) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                clip = true
                                alpha = haze.coerceIn(0f, 1f)
                                renderEffect = BlurEffect(hazePx, hazePx, TileMode.Clamp)
                            }
                            .drawBehind {
                                val o = nodeAt - (source?.origin ?: Offset.Zero)
                                translate(left = -o.x, top = -o.y) { drawLayer(layer) }
                            }
                    )
                }
            } else {
                // 没有内容层（预览 / 单测）：给一块底色，shader 仍会照常出边线
                Box(Modifier.matchParentSize().background(Color(0xFF101014)))
            }
        }

        content()
    }
}
