package com.mangadock.android.ui.prism

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.glass.GlassSurface

/**
 * v87：顶栏统一的**圆形玻璃按钮**。
 *
 * 老大 22:20 的要求：左上返回 / 右上排序（以及以后所有同类顶栏按钮）
 * 全部统一成相同大小的圆形、材质与底栏一致。
 *
 * 材质就是底栏那套 `GlassSurface`（同一份内容层、同一个 RenderNode，blur 6dp
 * 限制在这 44dp 的小圆里）；`edges = false` 是因为 GlassSurface 画的是矩形描边，
 * clip 成圆之后只剩四个切点，圆形亮边在这里自己用 border 补一圈。
 *
 * `glass = null` 是退化路径（不采样，画中性实心圆片）——只留给预览 / 单测。
 * 真页面都必须给一份**本页自己的** GlassSource，否则采到别的页面色块。
 *
 * 按压反馈走 [PrismElasticPress]，和底栏图标同一套 spring，无 ripple。
 */
@Composable
fun PrismCircleButton(
    onClick: () -> Unit,
    glass: GlassSource?,
    dark: Boolean,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 44.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    PrismElasticPress(modifier = modifier) {
        GlassSurface(
            source = glass,
            shape = CircleShape,
            // 比底栏略厚：按钮背后常是纯色留白，太透会连图标一起糊掉。
            tint = when {
                glass == null && dark -> Color(0xCC000000)
                glass == null -> Color(0xEBFFFFFF)
                dark -> Color(0x3D000000)
                else -> Color(0x38FFFFFF)
            },
            rim = Color.Transparent,
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            blur = 6.dp,
            edges = false,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = if (dark) 0.32f else 0.65f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

/**
 * 返回箭头。v87 起替掉细字符「‹」：20dp 描边折线，比原来大一圈也更清晰，
 * 和 app 里其它 Canvas 手绘图标同风格（不引 material-icons-extended）。
 */
@Composable
fun BackChevron(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.60f, h * 0.16f)
                lineTo(w * 0.34f, h * 0.50f)
                lineTo(w * 0.60f, h * 0.84f)
            },
            color = tint,
            style = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * 排序图标（v89）：替掉详情页右上那颗字符「⇅」。
 *
 * 字符的问题老大 23:34 说得准：字体笔画只有 ~1dp、字面高度远小于 22sp，
 * 放在 44dp 玻璃圆钮里又细又小，跟 [BackChevron] / [LockGlyph] 那种
 * 2dp+ 圆头描边的手绘图标完全不是一套语言。
 *
 * 这里改成同一套 Canvas 笔画：22dp、2.2dp 圆头圆角描边，
 * 三条递减横线表示「列表」，右侧箭头表示当前方向。
 * [ascending] 直接接详情页的排序状态 —— 正序 ↑ / 倒序 ↓，
 * 图标本身就能看出当前是哪种顺序，不用只靠点一下的提示。
 */
@Composable
fun SortGlyph(
    tint: Color,
    ascending: Boolean,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 22.dp,
) {
    Canvas(modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val lineW = 2.2.dp.toPx()
        val stroke = Stroke(
            width = lineW,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        // 三条长度递减的横线：列表 / 顺序。右端最多到 0.50w，
        // 给右边的箭头留够 0.18w 的呼吸，不然 2.2dp 圆头会和箭头尖粘成一坨。
        val ys = floatArrayOf(h * 0.28f, h * 0.50f, h * 0.72f)
        val ends = floatArrayOf(w * 0.50f, w * 0.36f, w * 0.22f)
        for (i in ys.indices) {
            drawLine(
                tint,
                Offset(w * 0.14f, ys[i]),
                Offset(ends[i], ys[i]),
                strokeWidth = lineW,
                cap = StrokeCap.Round,
            )
        }
        // 右侧方向箭头：正序朝上、倒序朝下
        val ax = w * 0.80f
        val head = Path().apply {
            if (ascending) {
                moveTo(w * 0.70f, h * 0.36f)
                lineTo(ax, h * 0.22f)
                lineTo(w * 0.90f, h * 0.36f)
            } else {
                moveTo(w * 0.70f, h * 0.64f)
                lineTo(ax, h * 0.78f)
                lineTo(w * 0.90f, h * 0.64f)
            }
        }
        drawLine(
            tint,
            Offset(ax, if (ascending) h * 0.80f else h * 0.20f),
            Offset(ax, if (ascending) h * 0.22f else h * 0.78f),
            strokeWidth = lineW,
            cap = StrokeCap.Round,
        )
        drawPath(head, tint, style = stroke)
    }
}

/** 一把小锁：和隐私书架锁面板同一笔画，v87 提升为公共图标供顶栏入口复用。 */
@Composable
fun LockGlyph(tint: Color, modifier: Modifier = Modifier, glyphSize: Dp = 20.dp) {
    Canvas(modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        // 锁梁
        val p = Path()
        p.moveTo(w * 0.30f, h * 0.46f)
        p.lineTo(w * 0.30f, h * 0.34f)
        p.quadraticTo(w * 0.30f, h * 0.14f, w * 0.50f, h * 0.14f)
        p.quadraticTo(w * 0.70f, h * 0.14f, w * 0.70f, h * 0.34f)
        p.lineTo(w * 0.70f, h * 0.46f)
        drawPath(p, tint, style = stroke)
        // 锁体
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.20f, h * 0.45f),
            size = Size(w * 0.60f, h * 0.42f),
            cornerRadius = CornerRadius(w * 0.10f),
            style = stroke,
        )
    }
}
