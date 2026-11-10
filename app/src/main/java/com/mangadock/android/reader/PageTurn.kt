package com.mangadock.android.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.mangadock.android.settings.PageAnimKind
import com.mangadock.android.ui.MangaMotion
import kotlin.math.abs
import kotlin.math.sign

/**
 * 甩动的判定线（px/s）。手指快速一划时位移可能不到一页的四分之一，
 * 但用户的意图很明确 —— 只看位移会让人"甩了没反应"。
 */
private const val FLING_V = 900f

/**
 * v19 分页模式的三种翻页风格。
 *
 * 性能红线：这里**一律不生成整页 Bitmap**。平滑用 AnimatedContent 换组合树，
 * 跟手 / 仿真用 `graphicsLayer` 的 translationX / rotationY —— 都是 RenderNode
 * 层的硬件变换，动画期间只有 2 份可见页面在组合，不会每帧重栅格化画面。
 * 动画一结束就把多余的那份页面撤掉，不留常驻透明层。
 *
 * 三种风格共用一套手势约定：
 *  - 左 / 右三分之一点按翻页，中间点按切换浮动条；
 *  - LTR 往左拖是下一页，RTL 往右拖是下一页。
 */
@Composable
fun PagedView(
    index: Int,
    total: Int,
    step: Int,
    dual: Boolean,
    rtl: Boolean,
    widthPx: Float,
    targetW: Int,
    loader: PageLoader,
    ink: Color,
    /** 仿真卷页时纸背的颜色 —— 跟阅读背景一致才像纸 */
    paper: Color,
    anim: PageAnimKind,
    scope: String,
    onCommit: (Int) -> Unit,
    onAtEnd: () -> Unit,
    onChromeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    /**
     * 末页索引，**按 step 对齐**。
     *
     * 双页模式下「一页」其实是两页，能停留的位置只有 0/2/4…。老算法直接取
     * `total - 1`，于是 total=4 时末位是 3：从 spread 2（第 3、4 页）再点「下一页」
     * 会翻到 index 3 —— 单独显示第 4 页，而第 4 页刚才已经和第 3 页一起出现过了。
     * 短章节（2~8 页）里这种"最后一页重复出现一次"特别明显，看着像卡住。
     */
    val last = run {
        val s = step.coerceAtLeast(1)
        val n = (total - 1).coerceAtLeast(0)
        (n / s) * s
    }

    /** 纯计算：算出目标页，到头就返回 null，不产生任何副作用。 */
    fun targetOf(dir: Int): Int? {
        val n = (index + dir * step).coerceIn(0, last)
        return if (n == index) null else n
    }

    /** 点按 / 甩动要用的版本：最后一页还往前翻就把结尾卡交出去。 */
    fun request(dir: Int): Int? {
        val n = targetOf(dir)
        if (n == null && dir > 0 && index >= last) onAtEnd()
        return n
    }

    Box(modifier) {
        when (anim) {
            // ---------------------------------------------------------- 平滑
            PageAnimKind.SMOOTH -> {
                var turnDir by remember { mutableIntStateOf(1) }
                AnimatedContent(
                    targetState = index,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        // RTL 的「下一页在左边」，视觉方向要反过来
                        val vis = if (rtl) -turnDir else turnDir
                        (fadeIn(animationSpec = tween(165, easing = MangaMotion.standardDecelerate)) +
                            slideInHorizontally(
                                animationSpec = tween(165, easing = MangaMotion.emphasizedDecelerate)
                            ) { vis * it / 7 }) togetherWith
                            (fadeOut(animationSpec = tween(105, easing = MangaMotion.standardAccelerate)) +
                                slideOutHorizontally(
                                    animationSpec = tween(105, easing = MangaMotion.emphasizedAccelerate)
                                ) { -vis * it / 7 })
                    },
                    label = "page-smooth"
                ) { idx ->
                    PageGroup(idx, total, dual, rtl, targetW, loader, ink, scope, Modifier.fillMaxSize())
                }
                TapSwipeGesture(
                    rtl = rtl,
                    index = index,
                    onTurn = { d ->
                        val n = request(d)
                        if (n != null) {
                            turnDir = d
                            onCommit(n)
                        }
                    },
                    onChromeToggle = onChromeToggle,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ---------------------------------------------------------- 跟手
            PageAnimKind.FOLLOW -> {
                /*
                 * awaitEachGesture 的接收者是 @RestrictsSuspension，里面不能调
                 * Animatable.animateTo 这类任意 suspend 函数。所以拆成两半：
                 *  - 手指实时偏移：普通 float State，直接赋值，graphicsLayer 里读；
                 *  - 松手后的回弹 / 翻出：丢给 LaunchedEffect 里的 Animatable 跑。
                 * 两者都只改 RenderNode 的 translationX，不触发整棵组合树重组。
                 */
                val live = remember { mutableFloatStateOf(0f) }
                val settle = remember { Animatable(0f) }
                var settling by remember { mutableStateOf(false) }
                var neighbor by remember { mutableStateOf<Int?>(null) }
                var settleSeq by remember { mutableIntStateOf(0) }
                var settleFrom by remember { mutableFloatStateOf(0f) }
                var settleTo by remember { mutableFloatStateOf(0f) }
                var settleMs by remember { mutableIntStateOf(165) }
                var settleCommit by remember { mutableStateOf<Int?>(null) }

                LaunchedEffect(settleSeq) {
                    if (settleSeq == 0) return@LaunchedEffect
                    settle.snapTo(settleFrom)
                    settling = true
                    settle.animateTo(settleTo, tween<Float>(settleMs))
                    settleCommit?.let { onCommit(it) }
                    settle.snapTo(0f)
                    live.floatValue = 0f
                    settling = false
                    settleCommit = null
                    neighbor = null
                }

                val nIdx = neighbor
                Box(Modifier.fillMaxSize()) {
                    /*
                     * 这里两个子节点必须挂固定 key。
                     *
                     * `if (nIdx != null)` 一插一拔，下面那一页在 Box 里的「位置」就从 1
                     * 变成 0，而 Compose 是按调用位置认身份的 —— 主页面会被当成新节点
                     * 整个销毁重建，PageImage 的 produceState 跟着重启、正在跑的解码被
                     * 取消，于是永远停在「载入中」（跟手模式实测踩到，平滑/仿真没有这个
                     * `if`，所以一直没暴露）。
                     */
                    if (nIdx != null) {
                        key("peer") {
                            PageGroup(
                                nIdx, total, dual, rtl, targetW, loader, ink, scope,
                                Modifier.fillMaxSize().graphicsLayer {
                                    val dx = if (settling) settle.value else live.floatValue
                                    translationX = dx + if (dx < 0f) widthPx else -widthPx
                                }
                            )
                        }
                    }
                    key("cur") {
                        PageGroup(
                            index, total, dual, rtl, targetW, loader, ink, scope,
                            Modifier.fillMaxSize().graphicsLayer {
                                translationX = if (settling) settle.value else live.floatValue
                            }
                        )
                    }
                }

                Box(
                    Modifier.fillMaxSize().pointerInput(rtl, index, step, total) {
                        val w = size.width.toFloat()
                        val edge = 6.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var acc = 0f
                            var lastX = down.position.x
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                tracker.addPosition(ch.uptimeMillis, ch.position)
                                acc += ch.position.x - lastX
                                lastX = ch.position.x
                                if (abs(acc) > edge) {
                                    ch.consume()
                                    val fwd = if (rtl) acc > 0f else acc < 0f
                                    val n = targetOf(if (fwd) 1 else -1)
                                    // 到头了只给一点橡皮筋位移，不露出空白页
                                    val lim = if (n == null) 36.dp.toPx() else w
                                    live.floatValue = acc.coerceIn(-lim, lim)
                                    neighbor = n
                                }
                            }

                            val dx = live.floatValue
                            // 松手瞬间的速度：位移不够但甩得够快也算翻页
                            val vx = tracker.calculateVelocity().x
                            if (abs(dx) <= edge) {
                                // 没拖动 = 纯点按，也让页面滑出去而不是瞬间换图
                                live.floatValue = 0f
                                neighbor = null
                                val d = when {
                                    down.position.x < w * 0.3f -> if (rtl) 1 else -1
                                    down.position.x > w * 0.7f -> if (rtl) -1 else 1
                                    else -> 0
                                }
                                if (d == 0) {
                                    onChromeToggle()
                                } else {
                                    val n = request(d)
                                    if (n != null) {
                                        neighbor = n
                                        settleFrom = 0f
                                        settleTo = if (d > 0) {
                                            if (rtl) w else -w
                                        } else {
                                            if (rtl) -w else w
                                        }
                                        settleCommit = n
                                        settleMs = 190
                                        settleSeq++
                                    }
                                }
                            } else {
                                val n = neighbor
                                // 位移过四分之一 或 同向甩得够快 —— 两个条件满足一个就翻
                                val flung = abs(vx) > FLING_V && sign(vx) == sign(dx)
                                val commit = n != null && (abs(dx) > w * 0.26f || flung)
                                // 末页继续往前拖：纸只挪一点就弹回，等于什么反馈都没有，
                                // 用户会以为卡死了。拖了一段或同向甩得够快就把结尾卡交出去
                                // （点按路径一直在这么做，拖动路径漏了）。
                                val fwd = if (rtl) dx > 0f else dx < 0f
                                // 注意 dx 在末页被限在 36dp 内（见上面的 lim），拿页宽百分比
                                // 当阈值永远够不到，只能按 dp 判。
                                if (n == null && fwd &&
                                    (abs(dx) > 24.dp.toPx() || flung)
                                ) onAtEnd()
                                settleFrom = dx
                                settleTo = if (!commit) 0f else if (dx < 0f) -w else w
                                settleCommit = if (commit) n else null
                                // 甩出去的补间短一点，手指快的时候动画不能拖泥带水
                                settleMs = when {
                                    !commit -> 130
                                    abs(vx) > FLING_V * 1.6f -> 120
                                    else -> 165
                                }
                                settleSeq++
                            }
                        }
                    }
                )
            }

            // ---------------------------------------------------------- 仿真卷页
            /*
             * v26 重做。原来那版是「整页绕书脊 rotationY 82° 再淡出」，
             * 本质就是扇门，没有纸的厚度、没有折痕、没有纸背，所以被老大一眼看出很假。
             *
             * 现在按社区 pagecurl 那一类实现的核心几何来做（oleksandrbalan/pagecurl
             * 与经典 harism PageCurl 都是这个思路）：
             *
             *   折线在 foldX，被掀起的那条带子 [foldX, w] 翻折到折线左边，
             *   露出下面那张（下一页）。翻折出来的部分 = **纸背**：
             *   铺一层纸色 + 极淡的透印（当前页镜像，alpha≈7%），
             *   再加一条横向明暗渐变假装圆筒的受光，折线处压一道深色折痕，
             *   下面那页贴着折痕落一层软阴影。
             *
             * 和它们不同的是**不把整页画到 Bitmap 上**：这里只多画一份 PageGroup
             * （本来动画期间就有两份可见页面）+ 两个 Canvas 渐变，
             * 依旧是 RenderNode 层的绘制，性能红线不动。
             */
            PageAnimKind.CURL -> {
                val live = remember { mutableFloatStateOf(0f) }
                val settle = remember { Animatable(0f) }
                var settling by remember { mutableStateOf(false) }
                var dir by remember { mutableIntStateOf(1) }
                var pending by remember { mutableStateOf<Int?>(null) }
                var seq by remember { mutableIntStateOf(0) }
                var settleFrom by remember { mutableFloatStateOf(0f) }
                var settleTo by remember { mutableFloatStateOf(0f) }
                var settleMs by remember { mutableIntStateOf(300) }
                var settleCommit by remember { mutableStateOf<Int?>(null) }

                LaunchedEffect(seq) {
                    if (seq == 0) return@LaunchedEffect
                    settle.snapTo(settleFrom)
                    settling = true
                    settle.animateTo(
                        settleTo,
                        tween(settleMs, easing = MangaMotion.curl)
                    )
                    settleCommit?.let { onCommit(it) }
                    settle.snapTo(0f)
                    live.floatValue = 0f
                    settling = false
                    settleCommit = null
                    pending = null
                }

                val q = if (settling) settle.value else live.floatValue
                val revealed = pending
                // 纸背颜色：**不能直接用主题纸色**。阅读背景是纯黑时，「掀起来的那张纸」
                // 和背景同色，两页之间就成了一条黑缝，看不出这是纸（v36 抽帧实测确认）。
                // 纸就是纸 —— 深色背景下把它往白里提，浅色背景下保留主题纸色，只提一点点
                // 免得和白色页面糊成一片。
                val paperBack = if (paper.luminance() < 0.4f) {
                    lerp(Color.White, paper, 0.10f)
                } else {
                    lerp(paper, Color.White, 0.07f)
                }
                // 从左边掀还是从右边掀：LTR 下一页从右边；RTL 下一页从左边；
                // 往回翻刚好相反。翻页方向只影响几何，不影响下面的纸背画法。
                val fromLeft = (dir > 0) == rtl
                val foldX = if (fromLeft) widthPx * q else widthPx * (1f - q)
                val flapL = if (fromLeft) foldX else (2f * foldX - widthPx)
                val flapR = if (fromLeft) (2f * foldX) else foldX

                Box(Modifier.fillMaxSize()) {
                    /*
                     * 层次（从下往上）：
                     *   ① 翻到的那一页（底层，铺满）
                     *   ② 折痕压在底层上的软阴影
                     *   ③ **当前页，只留还没被卷走的那一半**
                     *   ④ 纸背 + 圆柱明暗 + 外沿高光 + 折痕
                     *
                     * ③ 是 v35 补的。之前这里只在 ④ 的纸背里画了一份「镜像的当前页」当透印，
                     * 完整的当前页压根没画过 —— 于是 q 一旦离开 0，露出来的全是底层的下一页，
                     * 看起来就是「手指刚按下去，页面已经跳到下一页了」。真正的翻页必须让
                     * 当前页一直挡着，挡住的部分随折线移动越来越少。
                     */
                    key("under") {
                        PageGroup(
                            revealed ?: index, total, dual, rtl, targetW, loader, ink, scope,
                            Modifier.fillMaxSize()
                        )
                    }

                    if (revealed != null) {
                        // ② 折痕落在下面那页上的软阴影
                        Canvas(Modifier.fillMaxSize()) {
                            // 阴影宽度跟着掀起的那条纸走：纸掀得越宽，压在下面那页上的影子越宽。
                            // 老算法是个几乎和 q 无关的常数（42dp），翻小角时影子显得特别重。
                            val flapW = (flapR - flapL).coerceAtLeast(0f)
                            val sw = (flapW * 0.5f).coerceIn(10.dp.toPx(), 48.dp.toPx())
                            val a = 0.36f * (0.55f + 0.45f * q)
                            if (fromLeft) {
                                val l = (foldX - sw).coerceAtLeast(0f)
                                if (foldX - l > 0.5f) {
                                    drawRect(
                                        Brush.horizontalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = a)),
                                            startX = l, endX = foldX
                                        ),
                                        Offset(l, 0f),
                                        Size(foldX - l, size.height)
                                    )
                                }
                            } else {
                                val r = (foldX + sw).coerceAtMost(size.width)
                                if (r - foldX > 0.5f) {
                                    drawRect(
                                        Brush.horizontalGradient(
                                            listOf(Color.Black.copy(alpha = a), Color.Transparent),
                                            startX = foldX, endX = r
                                        ),
                                        Offset(foldX, 0f),
                                        Size(r - foldX, size.height)
                                    )
                                }
                            }
                        }

                        // ③ 当前页：按折线裁掉已经卷走的那半
                        key("cur") {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        val keep = foldX.coerceIn(0f, size.width)
                                        /*
                                         * 先铺一层纸，再画页面本身。
                                         *
                                         * 页面图是按 Fit 居中的：遇到窄图（竖条漫、单张竖图），
                                         * 左右会留出空隙。这些空隙如果不铺纸，就是**透明的**，
                                         * 底层那张下一页会从缝里透出来 —— 表现成「翻页时左边
                                         * 那一条黑了/换画面了」，其实是下一张从当前页的留白里
                                         * 漏出来了（v36 老大截图报的）。
                                         *
                                         * 一张纸就是铺满整个视口的，图只是印在上面，所以这里
                                         * 铺 paper 是对的，不是打补丁。
                                         */
                                        if (fromLeft) {
                                            clipRect(keep, 0f, size.width, size.height) {
                                                drawRect(paper)
                                                this@drawWithContent.drawContent()
                                            }
                                        } else {
                                            clipRect(0f, 0f, keep, size.height) {
                                                drawRect(paper)
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                    }
                            ) {
                                PageGroup(
                                    index, total, dual, rtl, targetW, loader, ink, scope,
                                    Modifier.fillMaxSize()
                                )
                            }
                        }

                        // ③ 纸背：纸色 + 透印 + 圆筒明暗，整块按翻折区域裁剪
                        Box(
                            Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    val l = flapL.coerceIn(0f, size.width)
                                    val r = flapR.coerceIn(0f, size.width)
                                    if (r - l > 0.5f) {
                                        // 用带弧的轮廓而不是矩形：外沿鼓出来一点，
                                        // 才像卷起来的纸，不像一刀切下来的色块。
                                        clipPath(flapOutline(l, r, size.height, fromLeft)) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                                }
                        ) {
                            Box(Modifier.fillMaxSize().background(paperBack))
                            // 透印：当前页镜像过去，压到很淡就是「纸太薄」的那种感觉
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = -1f
                                        translationX = 2f * foldX - widthPx
                                        alpha = 0.05f + 0.05f * (1f - q)
                                    }
                            ) {
                                PageGroup(
                                    index, total, dual, rtl, targetW, loader, ink, scope,
                                    Modifier.fillMaxSize()
                                )
                            }
                            // 圆筒受光：折痕处亮、往外迅速转暗
                            Canvas(Modifier.fillMaxSize()) {
                                val l = flapL.coerceIn(0f, size.width)
                                val r = flapR.coerceIn(0f, size.width)
                                if (r - l > 0.5f) {
                                    val colors = if (fromLeft) {
                                        listOf(
                                            Color.White.copy(alpha = 0.20f * (1f - q * 0.6f)),
                                            Color.Black.copy(alpha = 0.02f),
                                            Color.Black.copy(alpha = 0.22f)
                                        )
                                    } else {
                                        listOf(
                                            Color.Black.copy(alpha = 0.22f),
                                            Color.Black.copy(alpha = 0.02f),
                                            Color.White.copy(alpha = 0.20f * (1f - q * 0.6f))
                                        )
                                    }
                                    drawRect(
                                        Brush.horizontalGradient(colors, startX = l, endX = r),
                                        Offset(l, 0f),
                                        Size(r - l, size.height)
                                    )
                                }
                            }
                        }

                        // ④ 外沿的厚度高光：一条细亮线，纸才有边
                        Canvas(Modifier.fillMaxSize()) {
                            val l = flapL.coerceIn(0f, size.width)
                            val r = flapR.coerceIn(0f, size.width)
                            if (r - l > 2f) {
                                val bow = ((r - l) * 0.10f).coerceIn(0f, 30f)
                                val edge = Path()
                                if (fromLeft) {
                                    edge.moveTo(r - bow, 0f)
                                    edge.quadraticTo(r + bow, size.height / 2f, r - bow, size.height)
                                } else {
                                    edge.moveTo(l + bow, 0f)
                                    edge.quadraticTo(l - bow, size.height / 2f, l + bow, size.height)
                                }
                                drawPath(
                                    edge,
                                    Color.White.copy(alpha = 0.26f * (1f - q * 0.55f)),
                                    style = Stroke(width = 1.4.dp.toPx())
                                )
                            }
                        }

                        // ⑤ 折痕：一条细高光 + 一条深色折线，纸的「脊」才看得出来
                        Canvas(Modifier.fillMaxSize()) {
                            val rollW = (2.5f + 4f * (1f - q)).dp.toPx()
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.30f),
                                        Color.Transparent
                                    ),
                                    startX = foldX - rollW,
                                    endX = foldX + rollW
                                ),
                                Offset(foldX - rollW, 0f),
                                Size(rollW * 2f, size.height)
                            )
                            drawLine(
                                Color.White.copy(alpha = 0.34f * (1f - q * 0.5f)),
                                Offset(foldX + if (fromLeft) 0.6.dp.toPx() else -0.6.dp.toPx(), 0f),
                                Offset(
                                    foldX + if (fromLeft) 0.6.dp.toPx() else -0.6.dp.toPx(),
                                    size.height
                                ),
                                1.2.dp.toPx()
                            )
                        }
                    }

                    // ⑤ 手势：横向拖动实时掀，松手按阈值补间；点按仍按三分区
                    Box(
                        Modifier.fillMaxSize().pointerInput(rtl, index, step, total) {
                            val w = size.width.toFloat()
                            val slop = 6.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                var acc = 0f
                                var lastX = down.position.x
                                var dragged = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                    acc += ch.position.x - lastX
                                    lastX = ch.position.x
                                    if (!dragged && abs(acc) > slop) dragged = true
                                    if (dragged) {
                                        ch.consume()
                                        val fwd = if (rtl) acc > 0f else acc < 0f
                                        val d = if (fwd) 1 else -1
                                        val n = targetOf(d)
                                        dir = d
                                        // 到头了只掀一点点，露出空白比不动更难看
                                        live.floatValue = if (n == null) {
                                            (abs(acc) / w).coerceAtMost(0.10f)
                                        } else {
                                            (abs(acc) / w).coerceIn(0f, 1f)
                                        }
                                        pending = n
                                    }
                                }

                                if (!dragged) {
                                    val d = when {
                                        down.position.x < w * 0.3f -> if (rtl) 1 else -1
                                        down.position.x > w * 0.7f -> if (rtl) -1 else 1
                                        else -> 0
                                    }
                                    if (d == 0) {
                                        onChromeToggle()
                                    } else {
                                        val n = request(d)
                                        if (n != null) {
                                            dir = d
                                            pending = n
                                            settleFrom = 0f
                                            settleTo = 1f
                                            settleCommit = n
                                            // 点一下翻页：手没参与，全靠动画讲「纸被掀起来」的过程，
                                            // 所以比拖动补间更慢。420ms 是实测手感的分界点，
                                            // 再短就回到「一按就跳页」，再长会觉得卡。
                                            settleMs = 420
                                            seq++
                                        }
                                    }
                                } else {
                                    val start = live.floatValue
                                    val n = pending
                                    // 卷页的「进度」是 0~1，速度换算成每秒掀过去多少页宽
                                    val vx = tracker.calculateVelocity().x
                                    val vq = abs(vx) / w
                                    val flung = vq > 0.85f && sign(vx) == sign(acc)
                                    val commit = n != null && (start > 0.30f || flung)
                                    // 同上：末尾再往前拖也要有反馈。掀页进度被压在 0.10 以内，
                                    // 所以这里看真实手指位移 acc 而不是 start。
                                    if (n == null && dir > 0 && index >= last &&
                                        (abs(acc) > w * 0.06f || flung)
                                    ) onAtEnd()
                                    settleFrom = start
                                    settleTo = if (commit) 1f else 0f
                                    settleCommit = if (commit) n else null
                                    settleMs = when {
                                        !commit -> 210
                                        vq > 1.4f -> 260
                                        else -> 320
                                    }
                                    seq++
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 卷页时纸背的裁剪轮廓：**折线那一侧是直的，外沿带一点外凸的弧**。
 * 纯矩形会像一块色块，弧线才像卷起来的纸。弧度跟翻折宽度挂钩，
 * 掀得越多弧越明显，掀到一半以上就基本看不出来了（那时纸已经翻过去了）。
 */
private fun flapOutline(l: Float, r: Float, h: Float, fromLeft: Boolean): Path {
    val bow = ((r - l) * 0.10f).coerceIn(0f, 30f)
    val p = Path()
    if (fromLeft) {
        p.moveTo(r - bow, 0f)
        p.quadraticTo(r + bow, h / 2f, r - bow, h)
        p.lineTo(l, h)
        p.lineTo(l, 0f)
    } else {
        p.moveTo(l + bow, 0f)
        p.quadraticTo(l - bow, h / 2f, l + bow, h)
        p.lineTo(r, h)
        p.lineTo(r, 0f)
    }
    p.close()
    return p
}

/** 一「页」可能是单页，也可能是宽屏下并排的双页。三种风格共用它。 */@Composable
private fun PageGroup(
    idx: Int,
    total: Int,
    dual: Boolean,
    rtl: Boolean,
    targetW: Int,
    loader: PageLoader,
    ink: Color,
    scope: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val shown = buildList {
            add(idx)
            if (dual && idx + 1 < total) add(idx + 1)
        }
        val order = if (rtl) shown.reversed() else shown
        order.forEach { i ->
            PageImage(
                index = i,
                targetW = targetW,
                loader = loader,
                ink = ink,
                scope = scope,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/**
 * 平滑 / 仿真共用的「点按 + 甩动」手势。
 * 一个 pointerInput 同时判拖和点：两个 detector 挂同一节点会互抢 down，
 * 实测点按会完全没反应（v12 踩过）。
 */
@Composable
private fun TapSwipeGesture(
    rtl: Boolean,
    index: Int,
    onTurn: (Int) -> Unit,
    onChromeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.pointerInput(rtl, index) {
            val threshold = 56.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var acc = 0f
                var lastX = down.position.x
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    acc += ch.position.x - lastX
                    lastX = ch.position.x
                    if (abs(acc) > threshold) ch.consume()
                }
                val w = size.width.toFloat()
                if (abs(acc) > threshold) {
                    onTurn(if (if (rtl) acc > 0 else acc < 0) 1 else -1)
                } else {
                    when {
                        down.position.x < w * 0.3f -> onTurn(if (rtl) 1 else -1)
                        down.position.x > w * 0.7f -> onTurn(if (rtl) -1 else 1)
                        else -> onChromeToggle()
                    }
                }
            }
        }
    )
}
