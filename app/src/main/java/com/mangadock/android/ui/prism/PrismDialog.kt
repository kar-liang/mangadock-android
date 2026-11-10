package com.mangadock.android.ui.prism

import android.graphics.BlurMaskFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.ui.MangaTheme
import kotlinx.coroutines.launch

/**
 * FlClash `ui/dialog.dart` + `common/dialog.dart` + `ui/list.dart` 的 1:1 镜像。
 *
 * 源码已物理搬到 `_refs/flclash_ui/`：`dialog.dart`(824) / `common_dialog.dart`(314) /
 * `input.dart`(354) / `list.dart`(621) / `liquid_glass_capture.dart`。上一轮只搬了
 * `popup.dart`（长按菜单），所以设置页这些居中弹窗还是 M3 白卡片 —— 这份补的就是它。
 *
 * 搬的是这些，不是新设计：
 *  - `dialog.dart:16-38` 全部几何与动画常量（见 [PrismDialogSpec]）
 *  - `dialog.dart:40-58` `showFloatingDialog` / `_AlertRoute` 的 barrierColor、
 *    barrierDismissible、SafeArea、SurfaceScope
 *  - `dialog.dart:98-149` `AlertMotionRouteMixin`：404ms、barrier 随弹簧淡入淡出
 *  - `dialog.dart:151-204` `_AlertTransition`：scale 1.2→1，reverse 时锁住 scale 只淡出
 *  - `dialog.dart:226-319` `_AlertGlass`：LiquidGlassStyle.alert / LiquidGlassRim.alert /
 *    menuShadows / AppCorner.alert / steadyFrost / rim.paintLine
 *  - `dialog.dart:321-449` `CommonDialog`：title/content/actions 三段 padding、居中标题、
 *    headline / subheadline / body 字型、hasFields 分支
 *  - `dialog.dart:569-824` `AlertActions`：48dp 胶囊、最后一项 default 填充、
 *    destructive 红色、两按钮并排/否则堆叠且首项沉底、NoSplash
 *  - `color.dart:328-329` alertScrim = scrim alpha .2(light) / .48(dark)
 *  - `liquid_glass.dart:192-193` alert = menu.copyWith(blur: 16)
 *  - `liquid_glass.dart:471-478` LiquidGlassRim.alert 四参数
 *  - `typography.dart:13-16` headline 17/22 w600、body 17/22、subheadline 15/20
 *  - `shape.dart:16` AppCorner.alert = 34
 *  - `list.dart:370-390` 的选项类设置项：v68 起直接走 `PrismPopupCard`
 *    （和长按菜单同一套锚定弹出），不再由本文件提供；这里只保留 dialog 类。
 *
 * 框架差异（明确列出，不假装完全等价）：
 *  - FlClash 的 `CommonPopupMenu` 是**锚定在设置行当前值按钮上**的弹出菜单；MangaDock
 *    设置行点击只传 `SKey`，没有 bounds，所以这里保持「居中对话框」这一交互形态，
 *    只把弹窗本体换成 FlClash 的玻璃 alert，选项行仍按 `CommonPopupMenuItem` 规格。
 *  - `LiquidGlassRim.alert` 的 corner/flat/shine 由 AGSL 画，Compose 侧没有对应物；
 *    `side` 映射到 `GlassSurface` 的 1dp 描边 alpha，四参数原值全部保留在 Spec。
 *  - `LiquidGlassVeil` 的模糊遮罩（`liquid_glass_capture.dart:191`）未镜像，
 *    这里用源码同 alpha 的纯色 scrim。
 *  - saturation / brightness / contrast 属于玻璃 AGSL，同样由 `GlassSurface` 代管。
 */

// ---------------------------------------------------------------- 常量

/** `dialog.dart:16-38` / `color.dart:328` / `shape.dart:16` / `liquid_glass.dart` 原值。 */
object PrismDialogSpec {
    const val ACTION_GAP = 8f              // :16
    const val ACTION_INSET = 16f           // :17
    const val ACTION_HEIGHT = 48f          // :18
    const val TEXT_INSET = 30f             // :19
    const val ALERT_WIDTH = 348f           // :20
    const val CONTENT_MAX_WIDTH = 300f     // :21
    const val FIELD_INPUT_GAP = 4f         // :23
    const val PRESS_GROWTH = 4f            // :24
    const val PRESS_PULL = 3f              // :25
    const val PRESSED_FILL_SHARE = 0.4f    // :26
    const val PRESSED_DARK_DEFAULT_SHARE = 0.5f // :27
    const val ENTRY_SCALE = 1.2f           // :32

    const val CORNER_ALERT = 34f           // shape.dart:16 AppCorner.alert
    const val SCRIM_LIGHT = 0.20f          // color.dart:328
    const val SCRIM_DARK = 0.48f
    const val ALERT_BLUR = 16f             // liquid_glass.dart:193

    // menuShadows (liquid_glass.dart:446-455)，_AlertGlass 用的就是这一条
    const val SHADOW_ALPHA = 0.118f
    const val SHADOW_DARK_STRENGTH = 1.5f
    const val SHADOW_BLUR = 29f
    const val SHADOW_OFFSET_Y = 8f

    // glassFillOf (liquid_glass.dart:459-461)：浅色黑 13% / 深色白 13%
    const val GLASS_FILL_ALPHA = 0.13f

    // LiquidGlassRim.alert (liquid_glass.dart:471-474): side / corner / flat / shine
    val RIM_ALERT_LIGHT = floatArrayOf(0.35f, 0.22f, 0.12f, 0.16f)
    val RIM_ALERT_DARK = floatArrayOf(0.75f, 0.10f, 0.03f, 0.22f)

    // LiquidGlassStyle.alert = menu.copyWith(blur: 16)，tint / rim 沿用 menu 原值
    const val TINT_LIGHT = 0.55f
    const val TINT_DARK = 0.63f

    // typography.dart:13-16
    const val HEADLINE_SIZE = 17f
    const val HEADLINE_LEADING = 22f
    const val SUBHEADLINE_SIZE = 15f
    const val SUBHEADLINE_LEADING = 20f
    const val TITLE_TOP = 21f               // dialog.dart:384
    const val CONTENT_TOP = 6f              // dialog.dart:389 (hasFields -> 16)
    const val CONTENT_TOP_FIELDS = 16f
    const val CONTENT_BOTTOM = 20f
    const val CONTENT_ALPHA_LIGHT = 0.85f   // dialog.dart:364-367
}

data class PrismDialogAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * v91：弹层落点。
 *
 * 默认 [Center] —— FlClash `CommonDialog` 的原语义，适合短提示、二次确认。
 * [Top] 给**内容高的选择面板**用：「关联 Bangumi 条目」这种带输入框 + 12 条候选的
 * 卡片，居中会正好压在屏幕中间、把整页内容盖掉，老大要求浮到页面上方。
 */
enum class PrismDialogPlacement { Center, Top }

// ---------------------------------------------------------------- 形状

/** `LiquidGlassCorners.circular(AppCorner.alert)`：普通 34dp 圆角，四角各自圆心。 */
private fun prismAlertShape(radiusPx: Float): Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = Outline.Generic(prismSuperellipsePath(size, radiusPx))
}

// ---------------------------------------------------------------- 弹窗本体

/**
 * FlClash `CommonDialog` 的 1:1 镜像。
 *
 * @param close 传给 content 的「先播 404ms 退出、再执行动作」闭包，等价于源码
 *   `Navigator.pop()` 由 route 的 reverse 动画包住。选项行点中后走它才不会瞬移消失。
 */
@Composable
fun PrismDialog(
    title: String,
    onDismissRequest: () -> Unit,
    actions: List<PrismDialogAction>,
    modifier: Modifier = Modifier,
    glass: GlassSource? = null,
    hasFields: Boolean = false,
    destructive: Boolean = false,
    placement: PrismDialogPlacement = PrismDialogPlacement.Center,
    body: @Composable ColumnScope.(close: (() -> Unit) -> Unit) -> Unit,
) {
    val c = MangaTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // dialog.dart:352 `contentInset = hasFields ? _actionInset : _textInset`
    val contentInset = if (hasFields) PrismDialogSpec.ACTION_INSET
    else PrismDialogSpec.TEXT_INSET

    // dialog.dart:33-38 `_alertSpring` = SpringCurve(withDurationAndBounce(275), seconds .404)
    val curve = remember {
        PrismSpringCurve(PrismSprings.ALERT, PrismDuration.ALERT / 1000f)
    }
    val enter = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(PrismDuration.ALERT, easing = LinearEasing))
    }

    fun close(after: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            exit.animateTo(1f, tween(PrismDuration.ALERT, easing = LinearEasing))
            after()
        }
    }

    // reverseCurve = _alertSpring.flipped，且 exit.value 是 0→1 的反向进度：
    //   controller.value = 1 - exit.value
    //   flipped.transform(u) = 1 - curve(1 - u)
    //   => p = 1 - curve(exit.value)，从 1 淡出到 0。
    // 之前写成 1 - curve(1 - exit.value)，p 反而从 0 升到 1（二次淡入闪现），已修正。
    val p = if (!closing) curve.transform(enter.value)
    else 1f - curve.transform(exit.value)
    // _AlertTransitionState._heldScale：退出时锁在 reverse 开始那一刻的 scale（=1），只淡出
    val scale = if (closing) 1f else
        PrismDialogSpec.ENTRY_SCALE + (1f - PrismDialogSpec.ENTRY_SCALE) * p
    val scrimAlpha = (if (c.dark) PrismDialogSpec.SCRIM_DARK
    else PrismDialogSpec.SCRIM_LIGHT) * p.coerceIn(0f, 1f)

    // `_AlertRoute.barrierDismissible` + `AlertHostRoute`：返回键交给最内层这条
    BackHandler(enabled = true) { close(onDismissRequest) }

    // v91：Top 落点 —— 只让卡片贴到状态栏下方 12dp，scrim 仍然全屏。
    val isTop = placement == PrismDialogPlacement.Top

    Box(
        modifier.fillMaxSize(),
        contentAlignment = if (isTop) Alignment.TopCenter else Alignment.Center,
    ) {
        // dialog.dart:115-131 barrier + LiquidGlassVeil（纯色 scrim，随弹簧淡入淡出）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { close(onDismissRequest) }
        )

        val radiusPx = with(density) { PrismDialogSpec.CORNER_ALERT.dp.toPx() }
        val shape = remember(radiusPx) { prismAlertShape(radiusPx) }
        val shadowAlpha = PrismDialogSpec.SHADOW_ALPHA *
            (if (c.dark) PrismDialogSpec.SHADOW_DARK_STRENGTH else 1f)
        val shadowBlurPx = with(density) { PrismDialogSpec.SHADOW_BLUR.dp.toPx() }
        val shadowOffsetPx = with(density) { PrismDialogSpec.SHADOW_OFFSET_Y.dp.toPx() }

        Box(
            Modifier
                .width(PrismDialogSpec.ALERT_WIDTH.dp)
                // statusBarsPadding 把卡片压到状态栏下方，再留 12dp 呼吸位。
                .then(
                    if (isTop) Modifier.statusBarsPadding().padding(top = 12.dp)
                    else Modifier
                )
                .wrapContentHeight()
                .graphicsLayer {
                    this.scaleX = scale
                    this.scaleY = scale
                    this.alpha = p.coerceIn(0f, 1f)
                }
                // dialog.dart:298-317 _RenderAlertGlass.paint 的 menuShadows
                .drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = android.graphics.Color.argb(
                                (shadowAlpha * 255).toInt().coerceIn(0, 255), 0, 0, 0,
                            )
                            maskFilter = BlurMaskFilter(
                                shadowBlurPx, BlurMaskFilter.Blur.NORMAL,
                            )
                        }
                        val path = prismSuperellipsePath(size, radiusPx).asAndroidPath()
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.translate(0f, shadowOffsetPx)
                        canvas.nativeCanvas.drawPath(path, paint)
                        canvas.nativeCanvas.restore()
                    }
                }
        ) {
            // dialog.dart:415-428 `ElasticPress(pullPastEdges: true, maxGrowth: 4, maxPull: 3)`
            PrismElasticPress(
                maxGrowthDp = PrismDialogSpec.PRESS_GROWTH,
                pullPastEdges = true,
            ) {
                /*
                 * v84：对话框也换成**不透明卡片**（`c.surface` + 一圈极淡描边）。
                 *
                 * 和菜单同一个原因：玻璃在半透明时，卡片上半截和下半截透出的背景
                 * 强度不同（背景本身就是上深下浅），看起来像「只糊了一半」。
                 * 输入框、说明文字这类要读的面板，实色比玻璃可靠。
                 * 描边那 1dp 是给白色卡片在白色背景上留一条边界 —— 纯阴影在
                 * 浅色主题下不够，卡片边缘会糊进背景。
                 */
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(c.surface)
                        .border(
                            width = 1.dp,
                            color = if (c.dark) Color.White.copy(alpha = 0.08f)
                            else Color.Black.copy(alpha = 0.05f),
                            shape = shape,
                        ),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        // dialog.dart:384 titlePadding fromLTRB(30, 21, 30, 0)
                        // 无 trailing 时 SizedBox(width: infinity) + Text(center)
                        Text(
                            title,
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = PrismDialogSpec.TEXT_INSET.dp,
                                    top = PrismDialogSpec.TITLE_TOP.dp,
                                    end = PrismDialogSpec.TEXT_INSET.dp,
                                ),
                            style = TextStyle(
                                fontSize = PrismDialogSpec.HEADLINE_SIZE.sp,
                                lineHeight = PrismDialogSpec.HEADLINE_LEADING.sp,
                                // v90：标题统一 w600（hasFields 原来是 body w400）
                                fontWeight = FontWeight.W600,
                            ),
                            // dialog.dart:431-447 `_buildTitle`：源码 hasFields=true 会居中。
                            // v90：统一左对齐 —— 「关联 Bangumi 条目 / 编辑输入框」居中、
                            // 「提示 / 移出书架」左对齐，同一个 App 两种标题位置，老大指出不统一。
                            textAlign = TextAlign.Start,
                            color = c.ink,
                        )

                        // dialog.dart:385-399 contentPadding + BoxConstraints(maxWidth 288)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // dialog.dart:394-398 padding==null ->
                                // maxWidth = _alertWidth - contentInset * 2
                                .widthIn(
                                    max = (PrismDialogSpec.ALERT_WIDTH -
                                        contentInset * 2f).dp,
                                )
                                .padding(
                                    start = contentInset.dp,
                                    top = (if (hasFields) PrismDialogSpec.CONTENT_TOP_FIELDS
                                    else PrismDialogSpec.CONTENT_TOP).dp,
                                    end = contentInset.dp,
                                    bottom = PrismDialogSpec.CONTENT_BOTTOM.dp,
                                ),
                        ) {
                            // AppTextStyle.subheadline 15/20，onSurfaceVariant alpha .85(light)
                            androidx.compose.material3.ProvideTextStyle(
                                TextStyle(
                                    fontSize = PrismDialogSpec.SUBHEADLINE_SIZE.sp,
                                    lineHeight = PrismDialogSpec.SUBHEADLINE_LEADING.sp,
                                    color = c.sub.copy(
                                        alpha = if (c.dark) 1f
                                        else PrismDialogSpec.CONTENT_ALPHA_LIGHT,
                                    ),
                                ),
                            ) {
                                body { after -> close(after) }
                            }
                        }

                        // dialog.dart:378-383 actionsPadding fromLTRB(16, 0, 16, 16)
                        if (actions.isNotEmpty()) {
                            Box(
                                Modifier.padding(
                                    start = PrismDialogSpec.ACTION_INSET.dp,
                                    end = PrismDialogSpec.ACTION_INSET.dp,
                                    bottom = PrismDialogSpec.ACTION_INSET.dp,
                                ),
                            ) {
                                PrismAlertActions(
                                    actions = actions,
                                    destructive = destructive,
                                    close = { after -> close(after) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 按钮

/** `dialog.dart:569-599 AlertActions` 的并排 / 堆叠排布。 */
@Composable
private fun PrismAlertActions(
    actions: List<PrismDialogAction>,
    destructive: Boolean,
    close: (() -> Unit) -> Unit,
) {
    // dialog.dart:714-720 _sideBySide：两个且标签都放得进 (w-8)/2 时并排。
    // 中文标签 17sp 四字 + 32dp padding 远小于半宽，用「字数 <= 6」等价判定。
    val sideBySide = actions.size == 2 && actions.all { it.label.length <= 6 }
    if (sideBySide) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PrismDialogSpec.ACTION_GAP.dp),
        ) {
            actions.forEachIndexed { i, a ->
                PrismAlertAction(
                    action = a,
                    isDefault = !destructive && i == actions.lastIndex,
                    destructive = destructive && i == actions.lastIndex,
                    close = close,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        // dialog.dart:795-809：stacked 时 index = step % length (step 1..length)，
        // 首项沉底。单按钮时顺序不变。
        val order = (1..actions.size).map { it % actions.size }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PrismDialogSpec.ACTION_GAP.dp),
        ) {
            order.forEach { i ->
                PrismAlertAction(
                    action = actions[i],
                    isDefault = !destructive && i == actions.lastIndex,
                    destructive = destructive && i == actions.lastIndex,
                    close = close,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** `dialog.dart:601-667 _AlertAction` 的按钮规格。 */
@Composable
private fun PrismAlertAction(
    action: PrismDialogAction,
    isDefault: Boolean,
    destructive: Boolean,
    close: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MangaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // dialog.dart:617 glassFillOf；:623 background = isDefault ? primary : fill
    val fill = if (c.dark) Color.White.copy(alpha = PrismDialogSpec.GLASS_FILL_ALPHA)
    else Color.Black.copy(alpha = PrismDialogSpec.GLASS_FILL_ALPHA)
    /*
     * v90：按钮材质统一。
     *
     * 以前 default（单按钮「提示 / 好」「关闭」，以及双按钮的最后一个）走 `c.accent`
     * 实心块 —— 单按钮时就是一条撑满宽度的紫色大按钮；而「移出书架」这类双按钮是
     * 浅灰胶囊 + 彩色文字。同一个组件两种长相，老大一眼看出不统一。
     * 现在所有动作一律 `fill` 浅灰胶囊，主操作只靠 accent 文字 + w600 区分。
     */
    val background = fill
    // :624-628 pressedShare
    val pressedShare = when {
        isDefault && c.dark -> PrismDialogSpec.PRESSED_DARK_DEFAULT_SHARE
        !isDefault && c.dark -> 1f
        else -> PrismDialogSpec.PRESSED_FILL_SHARE
    }
    val bg = if (pressed) background.copy(alpha = background.alpha * pressedShare)
    else background
    // :618-622 foreground（v90：default 由白字改成 accent，底面已经是浅灰）
    val fg = when {
        destructive -> c.danger                        // colorScheme.destructive
        isDefault -> c.accent                          // 主操作：强调色文字
        else -> c.ink                                  // onSurface
    }

    Box(
        modifier
            .height(PrismDialogSpec.ACTION_HEIGHT.dp)
            // :644 AppShape.full -> 半高胶囊
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            // :647 NoSplash.splashFactory + overlay pressed transparent
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { close(action.onClick) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            style = TextStyle(
                fontSize = PrismDialogSpec.HEADLINE_SIZE.sp,       // body 17/22
                lineHeight = PrismDialogSpec.HEADLINE_LEADING.sp,
                // :637-641 default = emphasized body(w600)，其余 w500
                fontWeight = if (isDefault) FontWeight.W600 else FontWeight.W500,
            ),
            color = fg,
        )
    }
}
