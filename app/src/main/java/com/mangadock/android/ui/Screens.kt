@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
package com.mangadock.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.bookshelf.BangumiCoverBackfill
import com.mangadock.android.bookshelf.BooksLayoutKind
import com.mangadock.android.bookshelf.BookItem
import com.mangadock.android.bookshelf.BooksRow
import com.mangadock.android.bookshelf.CoverStore
import com.mangadock.android.pages.BookCard
import com.mangadock.android.pages.BooksLayout
import com.mangadock.android.pages.FilesLayout
import com.mangadock.android.pages.FilesRow
import com.mangadock.android.pages.FilesState
import com.mangadock.android.pages.coverPalette
import com.mangadock.android.pages.filesRows
import com.mangadock.android.pages.humanSize
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.settings.RowView
import com.mangadock.android.settings.SItem
import com.mangadock.android.settings.SKey
import com.mangadock.android.settings.SettingIcon
import com.mangadock.android.settings.SettingsPageId
import com.mangadock.android.settings.SettingsSpec
import com.mangadock.android.settings.viewOf
import com.mangadock.android.webdav.DavEntry

/**
 * 三个主页面全部走 Compose 原生 Lazy 列表。
 *
 * 之前是「手写 pointerInput 拖动 + 整页 Canvas 重绘 + 透明点击层」：
 *   - 没有惯性，松手就停，怎么甩都「一滑不到底」；
 *   - 文件页 783 项每帧全部 measure/drawText 一遍，还画一张 4 万 dp 高的白卡片；
 *   - 滚动时整棵组合树跟着重组、几十个点击层跟着重排。
 * 改成 LazyColumn / LazyVerticalGrid 后，滚动只平移 RenderNode、条目按需组合，
 * 惯性 fling 由 ScrollableState 提供，跟条漫阅读器是同一套机制。
 */

// v21：这三个原来写死的颜色改成「跟着主题走的只读属性」，调用点不用改。
private val INK: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.ink
private val SUB: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.sub
private val CARD: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.surface
private val DIVIDER: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.divider

/** 悬浮底栏占掉屏幕底部 24 + 56 = 80dp，列表尾巴再多留一点。 */
private val BOTTOM_PAD = 94.dp
/** 卡片圆角。参考 SenPlayer / Streama 都是超椭圆大圆角（24~28dp），12dp 显得很"工程"。 */
private val CARD_RADIUS = 22.dp

// ------------------------------------------------------------------ 设置页

private data class SettingGroup(val title: String, val rows: List<SItem.Row>)

/** 把一页的行按 Section 切成几张卡片。第一段没有 Section 就是一张无标题卡片。 */
private fun settingsGroups(page: SettingsPageId): List<SettingGroup> {
    val out = ArrayList<SettingGroup>()
    var title = ""
    var cur = ArrayList<SItem.Row>()
    SettingsSpec.items(page).forEach { item ->
        when (item) {
            is SItem.Section -> {
                if (cur.isNotEmpty()) {
                    out += SettingGroup(title, cur)
                    cur = ArrayList()
                }
                title = item.title
            }
            is SItem.Row -> cur += item
            is SItem.Nav -> Unit
        }
    }
    if (cur.isNotEmpty()) out += SettingGroup(title, cur)
    return out
}

/**
 * 设置页（v26 起是二级菜单）。
 *
 * ROOT 只画 6 个分类入口，右侧带当前值摘要；点进子页才是一行行真正的设置。
 * 子页的返回由顶栏的返回钮 / 系统返回键负责（见 MainActivity 的 BackHandler）。
 */
@Composable
fun SettingsPage(
    settings: AppSettings,
    page: SettingsPageId,
    davStatus: String,
    cacheSize: String,
    downloadSize: String,
    syncStatus: String,
    privacyCount: Int,
    /** v83：应用更新那行右侧的状态（当前版本 / 检查结果 / 下载百分比），运行时算 */
    updateStatus: String = "",
    listState: LazyListState,
    onKey: (SKey) -> Unit,
    onSelect: (SKey, Int) -> Unit,
    onOpenPage: (SettingsPageId) -> Unit,
    /**
     * v68：选择类设置项改成 FlClash `CommonPopupMenu` 那种**锚定弹出菜单**，
     * 需要把被点击那一行的 root 坐标 Rect 传上去（等价于 ui/list.dart:370-390
     * 的 `CommonPopupBox(targetBuilder/popupBuilder)`）。默认仍走 onKey，老调用点不用改。
     */
    onPickerAt: (SKey, Rect) -> Unit = { k, _ -> onKey(k) },
    /** v37：顶栏浮在列表上，首条要往下让出顶栏这一截 */
    topInset: Dp = 0.dp
) {
    if (page == SettingsPageId.ROOT) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp + topInset, bottom = BOTTOM_PAD)
        ) {
            item(key = "grp:root") {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(CARD_RADIUS))
                        .background(CARD)
                ) {
                    SettingsSpec.ROOT.forEachIndexed { i, p ->
                        // 分隔线从图标右边起，别从卡片边缘一路横到底
                        if (i > 0) ListDivider(start = 64.dp)
                        NavRow(
                            label = p.title,
                            summary = SettingsSpec.summary(p, settings, cacheSize, davStatus),
                            icon = p.icon,
                            tint = Color(p.tint)
                        ) { onOpenPage(p) }
                    }
                }
            }
            item(key = "root-foot") {
                Text(
                    "共 ${SettingsSpec.ROOT.size} 类设置，点进去改",
                    style = MangaType.bodySmall,
                    color = SUB,
                    modifier = Modifier.padding(start = 32.dp, top = 14.dp)
                )
            }
        }
        return
    }

    val groups = remember(page) { settingsGroups(page) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp + topInset, bottom = BOTTOM_PAD)
    ) {
        groups.forEachIndexed { gi, g ->
            if (g.title.isNotBlank()) {
                item(key = "sec:$gi:${g.title}") {
                    Text(
                        g.title,
                        style = MangaType.bodySmall,
                        color = SUB,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
            }
            item(key = "grp:$gi") {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = if (g.title.isBlank()) 10.dp else 0.dp)
                        .clip(RoundedCornerShape(CARD_RADIUS))
                        .background(CARD)
                ) {
                    g.rows.forEachIndexed { i, row ->
                        if (i > 0) ListDivider()
                        // 缓存大小 / 隐私空间数量是运行时算出来的，覆盖数据层的占位值
                        val v = row.key?.let {
                            when (it) {
                                SKey.CACHE -> RowView(value = cacheSize)
                                SKey.DOWNLOADS -> RowView(value = downloadSize)
                                SKey.SYNC_STATUS -> RowView(value = syncStatus)
                                SKey.UPDATE -> RowView(value = updateStatus.ifBlank { null })
                                else -> viewOf(it, settings, davStatus, privacyCount)
                            }
                        }
                        if (v?.ramp != null) {
                            ReadBgRow(row, v, onKey, onSelect)
                        } else {
                            SettingRow(row, v, settings.accentColor, onKey, onPickerAt)
                        }
                    }
                }
            }
        }
    }
}

/** 根页的一个分类入口：标题 + 当前值摘要 + 箭头。 */
@Composable
private fun NavRow(
    label: String,
    summary: String,
    icon: SettingIcon,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clickable { onClick() }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingBadge(icon, tint)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MangaType.titleSmall, color = INK)
        Spacer(Modifier.weight(1f))
        Text(
            summary,
            style = MangaType.bodyMedium,
            color = SUB,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 170.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 20.sp, color = MangaTheme.colors.chevron)
    }
}

/**
 * 分类图标：34dp 彩色圆角方块 + 19dp 白色几何图形。
 *
 * 规格照着 SenPlayer 的设置页量的（那边是 32dp 方块 / 10dp 圆角）。
 * 全部用圆、圆角矩形、弧手绘，不引 material-icons-extended —— 离线构建加依赖
 * 就是一堵墙，而且这几个符号自己画反而笔画粗细一致。
 */
@Composable
private fun SettingBadge(icon: SettingIcon, tint: Color) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(19.dp)) { drawSettingGlyph(icon, size.minDimension, tint) }
    }
}

private fun DrawScope.drawSettingGlyph(icon: SettingIcon, s: Float, tint: Color) {
    val w = Color.White
    val cx = size.width / 2f
    val cy = size.height / 2f
    when (icon) {
        // 调色板：一个圆 + 三个「颜料孔」。孔用方块底色画，看起来才是挖空的
        SettingIcon.PALETTE -> {
            drawCircle(w, s * 0.38f, Offset(cx, cy))
            drawCircle(tint, s * 0.085f, Offset(cx - s * 0.14f, cy - s * 0.12f))
            drawCircle(tint, s * 0.085f, Offset(cx + s * 0.04f, cy - s * 0.18f))
            drawCircle(tint, s * 0.085f, Offset(cx + s * 0.17f, cy + s * 0.01f))
        }
        // 摊开的两页。缝要留够（0.10s ≈ 1.9dp），不然 19dp 下会糊成一块
        SettingIcon.BOOK -> {
            val bw = s * 0.36f
            val bh = s * 0.62f
            val r = CornerRadius(s * 0.07f, s * 0.07f)
            drawRoundRect(w, Offset(cx - bw - s * 0.05f, cy - bh / 2f), Size(bw, bh), r)
            drawRoundRect(w, Offset(cx + s * 0.05f, cy - bh / 2f), Size(bw, bh), r)
        }
        // 四宫格
        SettingIcon.GRID -> {
            val q = s * 0.36f
            val r = CornerRadius(s * 0.08f, s * 0.08f)
            for (dx in listOf(-1f, 1f)) {
                for (dy in listOf(-1f, 1f)) {
                    drawRoundRect(
                        w,
                        Offset(cx + dx * s * 0.40f - q / 2f, cy + dy * s * 0.40f - q / 2f),
                        Size(q, q), r
                    )
                }
            }
        }
        // 两台叠起来的机架
        SettingIcon.SERVER -> {
            val bw = s * 0.74f
            val bh = s * 0.23f
            val r = CornerRadius(s * 0.06f, s * 0.06f)
            drawRoundRect(w, Offset(cx - bw / 2f, cy - s * 0.31f), Size(bw, bh), r)
            drawRoundRect(w, Offset(cx - bw / 2f, cy + s * 0.08f), Size(bw, bh), r)
        }
        // 两个半环
        SettingIcon.SYNC -> {
            val rr = s * 0.30f
            val st = Stroke(width = s * 0.13f, cap = StrokeCap.Round)
            drawArc(w, 35f, 130f, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = st)
            drawArc(w, 215f, 130f, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = st)
        }
        // 四角星
        SettingIcon.STAR -> {
            val p = Path().apply {
                moveTo(cx, cy - s * 0.44f)
                quadraticTo(cx + s * 0.07f, cy - s * 0.07f, cx + s * 0.44f, cy)
                quadraticTo(cx + s * 0.07f, cy + s * 0.07f, cx, cy + s * 0.44f)
                quadraticTo(cx - s * 0.07f, cy + s * 0.07f, cx - s * 0.44f, cy)
                quadraticTo(cx - s * 0.07f, cy - s * 0.07f, cx, cy - s * 0.44f)
                close()
            }
            drawPath(p, w)
        }
        // 硬盘：一个圆角框 + 两个指示灯
        SettingIcon.DRIVE -> {
            val bw = s * 0.78f
            val bh = s * 0.54f
            drawRoundRect(
                w,
                Offset(cx - bw / 2f, cy - bh / 2f),
                Size(bw, bh),
                CornerRadius(s * 0.11f, s * 0.11f),
                style = Stroke(width = s * 0.13f)
            )
            drawCircle(w, s * 0.075f, Offset(cx - s * 0.15f, cy + s * 0.08f))
            drawCircle(w, s * 0.075f, Offset(cx + s * 0.04f, cy + s * 0.08f))
        }
    }
}

@Composable
private fun SettingRow(
    row: SItem.Row,
    v: RowView?,
    accent: Color,
    onKey: (SKey) -> Unit,
    onPickerAt: (SKey, Rect) -> Unit = { k, _ -> onKey(k) }
) {
    val key = row.key
    // v68：锚定菜单要的就是「被点那一行的 bounds」，等价于 FlClash 的
    // CommonPopupBox 把 targetBuilder 的按钮当 anchor。root 坐标与
    // PrismPopupCard 的 anchor 参数同一坐标系。
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val click = if (key != null) Modifier.clickable {
        onPickerAt(key, rowBounds)
    } else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .height(row.height.dp)
            .onGloballyPositioned { rowBounds = it.boundsInRoot() }
            .then(click)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // label 不拉伸，剩余空间全部让给右侧的值/控件，长 URL 才不会被挤成省略号
        Text(row.label, style = MangaType.titleSmall, color = INK)
        Spacer(Modifier.weight(1f))

        v?.seg?.let { labels ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(MangaTheme.colors.chip)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                labels.forEachIndexed { i, label ->
                    val on = i == v.segIndex
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (on) CARD else Color.Transparent)
                            .padding(horizontal = 11.dp, vertical = 4.dp)
                            .clickable { key?.let { onKey(it) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MangaType.bodyMedium,
                            color = if (on) INK else MangaTheme.colors.body,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        v?.swatch?.let { c ->
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(c)
            )
        }

        v?.toggle?.let { on ->
            Switch(
                checked = on,
                onCheckedChange = { key?.let { onKey(it) } },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CARD,
                    // v88：开关填充跟用户选的主题色（SettingRow 的 accent =
                    // settings.accentColor）。原来用 GREEN = colors.switchOn，
                    // 它在动态取色下是 scheme.primary / 系统蓝，跟底栏和图标
                    // 用的 ThemeAccent 对不上（老大截图里开关蓝、设置图标紫）。
                    checkedTrackColor = accent,
                    uncheckedThumbColor = CARD,
                    uncheckedTrackColor = MangaTheme.colors.chip,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        val text = v?.value ?: row.value
        if (text != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MangaType.titleSmall,
                color = SUB,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

/** 阅读背景：四个大色块，直接点色块就选中，不用再开一层弹窗。 */
@Composable
private fun ReadBgRow(
    row: SItem.Row,
    v: RowView,
    onKey: (SKey) -> Unit,
    onSelect: (SKey, Int) -> Unit
) {
    val cols = v.ramp.orEmpty()
    val key = row.key
    Column(
        Modifier
            .fillMaxWidth()
            .height(row.height.dp)
            .then(if (key != null) Modifier.clickable { onKey(key) } else Modifier)
            .padding(horizontal = 16.dp)
    ) {
        Text(row.label, style = MangaType.titleSmall, color = INK, modifier = Modifier.padding(top = 12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cols.forEachIndexed { i, c ->
                val on = i == v.rampIndex
                val luma = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
                val tick = if (luma > 0.6f) MangaTheme.colors.body else CARD
                Box(
                    Modifier
                        .weight(1f)
                        .height(76.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(c)
                        .border(0.5.dp, MangaTheme.colors.divider, RoundedCornerShape(11.dp))
                        .clickable { key?.let { onSelect(it, i) } },
                    contentAlignment = Alignment.Center
                ) {
                    if (on) {
                        Text("✓", fontSize = 22.sp, color = tick, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 文件页

@Composable
fun FilesPage(
    settings: AppSettings,
    state: FilesState,
    joined: Boolean,
    listState: LazyListState,
    onReload: () -> Unit,
    onJoin: () -> Unit,
    onRead: () -> Unit,
    onGoSettings: () -> Unit,
    onRow: (FilesRow) -> Unit,
    onRowLong: (DavEntry, Rect) -> Unit,
    /** v37：顶栏浮在列表上，首条要往下让出顶栏这一截 */
    topInset: Dp = 0.dp
) {
    if (!settings.webdavConfigured) {
        EmptyPage(
            book = false,
            title = "还没有连接 WebDAV",
            sub = "填好服务器地址、账号和漫画根路径就能浏览了",
            action = "去设置 WebDAV",
            accent = settings.accentColor,
            onAction = onGoSettings
        )
        return
    }

    val rows = remember(state) { filesRows(state) }
    val ptrState = rememberPullToRefreshState()
    // 下拉刷新：指示器跟着 state.loading 转。这里不再"每次切到这个 tab 就偷偷刷一遍"
    // —— 那会让列表在没有改动的时候也闪一下，和书架的静默行为对不上。
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onReload,
        modifier = Modifier.fillMaxSize(),
        state = ptrState,
        // 指示器往下让出顶栏：顶栏是浮在内容上的一层玻璃，不让位的话
        // 转圈会被那层雾盖住，下拉时看起来像"没反应"
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter).offset(y = topInset),
                isRefreshing = state.loading,
                state = ptrState
            )
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp + topInset, bottom = BOTTOM_PAD)
        ) {
            state.error?.let { msg ->
                item(key = "err") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MangaTheme.colors.dangerBg)
                            .padding(12.dp)
                    ) {
                        Text(
                            msg,
                            style = MangaType.bodySmall,
                            color = MangaTheme.colors.danger,
                            maxLines = 3
                        )
                    }
                }
            }

            item(key = "header") {
                PathHeader(state, joined, settings.accentColor, onReload, onJoin)
            }

            if (state.images.isNotEmpty()) {
                item(key = "read") { ReadEntry(state, settings.accentColor, onRead) }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    val msg = when {
                        state.images.isNotEmpty() -> "这个目录里只有图片，直接开始阅读"
                        state.loadedOnce -> "这个目录是空的"
                        else -> "点这里重试"
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 34.dp)
                            .clickable { onReload() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(msg, style = MangaType.bodyMedium, color = SUB)
                    }
                }
            }

            // 只有可见的十几行会被组合，783 项也不会拖慢一帧
            items(count = rows.size, key = { i -> rowKey(rows[i]) }) { i ->
                val shape = when {
                    rows.size == 1 -> RoundedCornerShape(CARD_RADIUS)
                    i == 0 -> RoundedCornerShape(
                        topStart = CARD_RADIUS, topEnd = CARD_RADIUS,
                        bottomStart = 0.dp, bottomEnd = 0.dp
                    )
                    i == rows.size - 1 -> RoundedCornerShape(
                        topStart = 0.dp, topEnd = 0.dp,
                        bottomStart = CARD_RADIUS, bottomEnd = CARD_RADIUS
                    )
                    else -> RoundedCornerShape(0.dp)
                }
                FileListRow(
                    row = rows[i],
                    accent = settings.accentColor,
                    shape = shape,
                    divider = i > 0,
                    onClick = { onRow(rows[i]) },
                    onLongClick = { at ->
                        val r = rows[i]
                        if (r is FilesRow.Entry) onRowLong(r.e, at)
                    }
                )
            }
        }
    }
}

private fun rowKey(row: FilesRow): String = when (row) {
    is FilesRow.Up -> "up:${row.target}"
    is FilesRow.Entry -> "e:${row.e.path}"
}

@Composable
private fun PathHeader(
    state: FilesState,
    joined: Boolean,
    accent: Color,
    onReload: () -> Unit,
    onJoin: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(FilesLayout.HEADER_H.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (state.path == "/") "根目录" else state.path,
            style = MangaType.bodyMedium,
            color = SUB,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { onReload() }
        )
        val status = when {
            state.offline -> "离线缓存 · ${state.entries.size} 项"
            state.loading && state.loadedOnce -> "刷新中…"
            state.loading -> "读取中…"
            state.error != null -> "连接失败"
            state.loadedOnce -> "${state.entries.size} 项"
            else -> ""
        }
        if (status.isNotEmpty()) {
            Text(status, style = MangaType.bodyMedium, color = SUB, modifier = Modifier.padding(end = 10.dp))
        }
        Box(
            Modifier
                .width(FilesLayout.JOIN_BTN_W.dp)
                .height(FilesLayout.JOIN_BTN_H.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (joined) MangaTheme.colors.chipSoft else accent.copy(alpha = 0.12f))
                .clickable { onJoin() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (joined) "已加入" else "＋ 加入",
                style = MangaType.bodySmall,
                color = if (joined) SUB else accent,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ReadEntry(state: FilesState, accent: Color, onRead: () -> Unit) {
    val resume = state.readIndex >= 0
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(CARD)
            .clickable { onRead() }
            .height((FilesLayout.READ_H - 8f).dp)
            .padding(horizontal = 20.dp)
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.10f)
                    lineTo(size.width * 0.86f, size.height * 0.5f)
                    lineTo(size.width * 0.22f, size.height * 0.90f)
                    close()
                }
                drawPath(p, accent)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                if (resume) "继续阅读" else "开始阅读",
                style = MangaType.titleSmall,
                color = INK,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (resume) "第 ${state.readIndex + 1} / ${state.images.size} 页"
                else "共 ${state.images.size} 页",
                style = MangaType.bodyMedium,
                color = SUB
            )
        }
    }
}

@Composable
private fun FileListRow(
    row: FilesRow,
    accent: Color,
    shape: Shape,
    divider: Boolean,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(CARD)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(onClick = onClick, onLongClick = { onLongClick(bounds) })
    ) {
        if (divider) ListDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(FilesLayout.ROW_H.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (kind, tint, name, meta) = when (row) {
                is FilesRow.Up -> Quad(
                    RowIconKind.UP, accent, "返回上一级", ""
                )
                is FilesRow.Entry -> Quad(
                    if (row.e.isDir) RowIconKind.FOLDER else RowIconKind.FILE,
                    if (row.e.isDir) accent else SUB,
                    row.e.name,
                    if (row.e.isDir) "" else humanSize(row.e.size)
                )
            }
            RowIcon(kind, tint)
            Spacer(Modifier.width(14.dp))
            Text(
                name,
                style = MangaType.titleSmall,
                color = INK,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(meta, style = MangaType.bodyMedium, color = SUB)
            }
        }
    }
}

/** 四个字段的小元组，省得写 data class。 */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private enum class RowIconKind { UP, FOLDER, FILE }

@Composable
private fun RowIcon(kind: RowIconKind, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path()
        when (kind) {
            RowIconKind.UP -> {
                p.moveTo(s * 0.50f, s * 0.20f)
                p.lineTo(s * 0.16f, s * 0.52f)
                p.lineTo(s * 0.84f, s * 0.52f)
                p.close()
                p.moveTo(s * 0.50f, s * 0.44f)
                p.lineTo(s * 0.50f, s * 0.86f)
            }
            RowIconKind.FILE -> {
                p.moveTo(s * 0.12f, s * 0.16f)
                p.lineTo(s * 0.60f, s * 0.16f)
                p.lineTo(s * 0.88f, s * 0.44f)
                p.lineTo(s * 0.88f, s * 0.84f)
                p.lineTo(s * 0.12f, s * 0.84f)
                p.close()
                p.moveTo(s * 0.60f, s * 0.16f)
                p.lineTo(s * 0.60f, s * 0.44f)
                p.lineTo(s * 0.88f, s * 0.44f)
            }
            RowIconKind.FOLDER -> {
                p.moveTo(s * 0.08f, s * 0.80f)
                p.lineTo(s * 0.08f, s * 0.26f)
                p.quadraticTo(s * 0.08f, s * 0.18f, s * 0.18f, s * 0.18f)
                p.lineTo(s * 0.38f, s * 0.18f)
                p.quadraticTo(s * 0.44f, s * 0.18f, s * 0.48f, s * 0.26f)
                p.lineTo(s * 0.54f, s * 0.34f)
                p.lineTo(s * 0.92f, s * 0.34f)
                p.lineTo(s * 0.92f, s * 0.80f)
                p.quadraticTo(s * 0.92f, s * 0.86f, s * 0.84f, s * 0.86f)
                p.lineTo(s * 0.16f, s * 0.86f)
                p.quadraticTo(s * 0.08f, s * 0.86f, s * 0.08f, s * 0.80f)
                p.close()
            }
        }
        drawPath(p, tint, style = stroke)
    }
}

// ------------------------------------------------------------------ 书架页

@Composable
fun BooksPage(
    settings: AppSettings,
    rows: List<BooksRow>,
    gridState: LazyGridState,
    /** v31：0 网格 / 1 列表（见 [BooksLayoutKind]） */
    layout: Int = 0,
    onOpen: (BookItem) -> Unit,
    onLongPress: (BookItem, Rect) -> Unit,
    onGo: () -> Unit,
    /** 换 / 删自定义封面后抬一下，迫使卡片重新取图 */
    coverTick: Int = 0,
    /** 网格顶上那条说明（隐私空间用），为空就不画 */
    note: String? = null,
    /** 下拉刷新中：指示器转起来，数据到了再落下 */
    refreshing: Boolean = false,
    /** 手动刷新：清封面缓存 + 重试 Bangumi 补匹配。null = 不提供下拉 */
    onRefresh: (() -> Unit)? = null,
    emptyTitle: String = "书架还是空的",
    emptySub: String? = null,
    emptyAction: String = "去挑漫画",
    /** v37：顶栏浮在列表上，首条要往下让出顶栏这一截 */
    topInset: Dp = 0.dp
) {
    if (rows.isEmpty()) {
        EmptyPage(
            book = true,
            title = emptyTitle,
            sub = emptySub ?: if (settings.webdavConfigured) {
                "去「文件」里把想看的漫画加进来"
            } else {
                "先连上 WebDAV 服务器，再加漫画到书架"
            },
            action = emptyAction,
            accent = settings.accentColor,
            onAction = onGo
        )
        return
    }

    // 列表和网格各记各的滚动位置：切布局时不该被弹回顶部
    val listState = rememberLazyListState()
    val ptrState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { onRefresh?.invoke() },
        modifier = Modifier.fillMaxSize(),
        state = ptrState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter).offset(y = topInset),
                isRefreshing = refreshing,
                state = ptrState
            )
        }
    ) {
        if (BooksLayoutKind.of(layout) == BooksLayoutKind.LIST) {
            // 列表：一行一本，封面缩到 46×62，书名和进度各占一行。
            // 分组标题同样独占一行，靠 minHeight 让它在列表里也有「段」的感觉。
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BooksLayout.PAD_X.dp,
                    end = BooksLayout.PAD_X.dp,
                    top = 8.dp + topInset,
                    bottom = BOTTOM_PAD
                )
            ) {
                if (note != null) {
                    item(key = "note") { PrivacyNote(note) }
                }
                items(
                    count = rows.size,
                    key = { i ->
                        when (val r = rows[i]) {
                            is BooksRow.Section -> "sec:${r.id}"
                            is BooksRow.Card -> "card:${r.card.item.key}"
                        }
                    }
                ) { i ->
                    when (val r = rows[i]) {
                        is BooksRow.Section -> Text(
                            r.title,
                            style = MangaType.bodySmall,
                            color = SUB,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                        )

                        is BooksRow.Card -> BookListRow(
                            card = r.card,
                            settings = settings,
                            coverTick = coverTick,
                            onOpen = onOpen,
                            onLongPress = onLongPress
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(BooksLayout.COLS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BooksLayout.PAD_X.dp,
                    end = BooksLayout.PAD_X.dp,
                    top = 8.dp + topInset,
                    bottom = BOTTOM_PAD
                ),
                horizontalArrangement = Arrangement.spacedBy(BooksLayout.GAP_X.dp),
                verticalArrangement = Arrangement.spacedBy(BooksLayout.GAP_Y.dp)
            ) {
                if (note != null) {
                    item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
                        PrivacyNote(note)
                    }
                }
                items(
                    count = rows.size,
                    key = { i ->
                        when (val r = rows[i]) {
                            is BooksRow.Section -> "sec:${r.id}"
                            is BooksRow.Card -> "card:${r.card.item.key}"
                        }
                    },
                    // 分组标题独占整行；卡片仍占 1 列。标题本身也是 Lazy item，不额外叠一层布局
                    span = { i ->
                        if (rows[i] is BooksRow.Section) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    }
                ) { i ->
                    when (val r = rows[i]) {
                        is BooksRow.Section -> Text(
                            r.title,
                            style = MangaType.bodySmall,
                            color = SUB,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp)
                        )

                        is BooksRow.Card -> BookCoverCard(
                            card = r.card,
                            settings = settings,
                            coverTick = coverTick,
                            onOpen = onOpen,
                            onLongPress = onLongPress
                        )
                    }
                }
            }
        }
    }
}

/**
 * 书架列表行（v31）：小封面 + 书名 + 进度。
 *
 * 封面只有 46×62，1:1.35 左右，和网格卡片的 2:3 不一样 —— 硬套 2:3 在这个尺寸上
 * 看着太细长。取图和卡片走**同一个** `CoverStore.load`，所以换图 / 补匹配同样生效。
 */
@Composable
private fun BookListRow(
    card: BookCard,
    settings: AppSettings,
    coverTick: Int,
    onOpen: (BookItem) -> Unit,
    onLongPress: (BookItem, Rect) -> Unit
) {
    val ctx = LocalContext.current
    val bgVersion by BangumiCoverBackfill.version.collectAsState()
    val cover by produceState<ImageBitmap?>(
        initialValue = null,
        card.item.key,
        coverTick,
        bgVersion,
        settings.webdavUrl,
        settings.webdavUser,
        settings.webdavPass,
        settings.bangumiEnabled,
        settings.bangumiBase,
    ) {
        value = CoverStore.load(ctx, card.item, settings)?.asImageBitmap()
        BangumiCoverBackfill.enqueue(ctx, card.item, settings)
    }
    val bmp = cover
    // 长按菜单要贴着这一格展开，所以得记住它在根坐标里的位置
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(CARD)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(
                onClick = { onOpen(card.item) },
                onLongClick = { onLongPress(card.item, bounds) }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(46.dp)
                .height(62.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(coverPalette(card.item.title)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    card.item.title.trim().take(1).ifEmpty { "漫" },
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                card.item.title,
                style = MangaType.bodyMedium,
                color = INK,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                card.progress,
                style = MangaType.bodySmall,
                color = SUB,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("›", fontSize = 18.sp, color = SUB, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 书架卡片（v19 按老大的参考图）：竖版封面 + 下方单行书名。
 * 进度不再单独占一行，改成封面右下角小角标；类型角标缩到左上，别盖住封面主体。
 */
@Composable
private fun BookCoverCard(
    card: BookCard,
    settings: AppSettings,
    coverTick: Int,
    onOpen: (BookItem) -> Unit,
    onLongPress: (BookItem, Rect) -> Unit
) {
    val ctx = LocalContext.current
    // 补匹配成功一本就抬一次，卡片重新取封面（只影响已匹配的那些）
    val bgVersion by BangumiCoverBackfill.version.collectAsState()
    // produceState 在 IO 线程里跑 CoverStore.load，取不到就一直显示占位首字，
    // 不会因为一张封面阻塞网格滚动；key 只认书 / 凭据 / 封面变更，切主题色不重跑。
    val cover by produceState<ImageBitmap?>(
        initialValue = null,
        card.item.key,
        coverTick,
        bgVersion,
        settings.webdavUrl,
        settings.webdavUser,
        settings.webdavPass,
        settings.bangumiEnabled,
        settings.bangumiBase,
    ) {
        value = CoverStore.load(ctx, card.item, settings)?.asImageBitmap()
        // 顺手排队补 Bangumi 匹配：单例串行队列，同一本只排一次；
        // 拿到封面后靠 bgVersion 触发上面这个 produceState 重跑。
        BangumiCoverBackfill.enqueue(ctx, card.item, settings)
    }
    val bmp = cover
    // 长按菜单贴着这一格展开，记住它在根坐标里的位置
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Column(
        Modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(
                onClick = { onOpen(card.item) },
                onLongClick = { onLongPress(card.item, bounds) }
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(BooksLayout.COVER_RATIO)
                .clip(RoundedCornerShape(BooksLayout.CARD_RADIUS.dp))
                .background(coverPalette(card.item.title)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    card.item.title.trim().take(1).ifEmpty { "漫" },
                    fontSize = 38.sp,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Bold
                )
            }

            card.badge?.let { badge ->
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        badge,
                        style = MangaType.micro,
                        color = Color.White.copy(alpha = 0.95f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Text(
            card.item.title,
            style = MangaType.bodyMedium,
            color = INK,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp)
        )
    }
}

// ------------------------------------------------------------------ 公共小块

@Composable
private fun ListDivider(start: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(0.5.dp)
            .background(DIVIDER)
    )
}

@Composable
private fun EmptyPage(
    book: Boolean,
    title: String,
    sub: String,
    action: String,
    accent: Color,
    onAction: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .clickable { onAction() }
            .padding(bottom = BOTTOM_PAD),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(32.dp)) {
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val p = Path()
                if (book) {
                    p.moveTo(size.width * 0.08f, size.height * 0.14f)
                    p.quadraticTo(
                        size.width * 0.30f, size.height * 0.04f,
                        size.width * 0.50f, size.height * 0.16f
                    )
                    p.quadraticTo(
                        size.width * 0.70f, size.height * 0.04f,
                        size.width * 0.92f, size.height * 0.14f
                    )
                    p.lineTo(size.width * 0.92f, size.height * 0.88f)
                    p.quadraticTo(
                        size.width * 0.70f, size.height * 0.78f,
                        size.width * 0.50f, size.height * 0.90f
                    )
                    p.quadraticTo(
                        size.width * 0.30f, size.height * 0.78f,
                        size.width * 0.08f, size.height * 0.88f
                    )
                    p.close()
                    drawLine(
                        accent,
                        Offset(size.width * 0.50f, size.height * 0.16f),
                        Offset(size.width * 0.50f, size.height * 0.90f),
                        stroke.width, StrokeCap.Round
                    )
                } else {
                    p.moveTo(size.width * 0.08f, size.height * 0.78f)
                    p.lineTo(size.width * 0.08f, size.height * 0.22f)
                    p.quadraticTo(
                        size.width * 0.08f, size.height * 0.12f,
                        size.width * 0.18f, size.height * 0.12f
                    )
                    p.lineTo(size.width * 0.40f, size.height * 0.12f)
                    p.quadraticTo(
                        size.width * 0.48f, size.height * 0.12f,
                        size.width * 0.52f, size.height * 0.22f
                    )
                    p.lineTo(size.width * 0.58f, size.height * 0.30f)
                    p.lineTo(size.width * 0.92f, size.height * 0.30f)
                    p.lineTo(size.width * 0.92f, size.height * 0.78f)
                    p.quadraticTo(
                        size.width * 0.92f, size.height * 0.88f,
                        size.width * 0.84f, size.height * 0.88f
                    )
                    p.lineTo(size.width * 0.16f, size.height * 0.88f)
                    p.quadraticTo(
                        size.width * 0.08f, size.height * 0.88f,
                        size.width * 0.08f, size.height * 0.78f
                    )
                    p.close()
                }
                drawPath(p, accent, style = stroke)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MangaType.titleMedium, color = INK, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MangaType.bodyMedium, color = SUB)
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(action, style = MangaType.bodyLarge, color = accent, fontWeight = FontWeight.Medium)
        }
    }
}
