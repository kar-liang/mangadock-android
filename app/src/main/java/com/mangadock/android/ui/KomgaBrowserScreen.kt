package com.mangadock.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mangadock.android.komga.KomgaClient
import com.mangadock.android.komga.KomgaThumb
import com.mangadock.android.pages.coverPalette

/**
 * v98：Komga 浏览页。两级，和 WebDAV 那边的「目录 → 文件」是一个位置：
 *
 *   系列（封面网格）── 点一个 ──▶ 单话列表 ── 点一话 ──▶ 阅读器
 *
 * 导航状态不放在这里，而是由 MainActivity 拿着（[series]）——
 * 顶栏的返回钮、系统返回键都在那边，两处各存一份必然对不上。
 *
 * ## 只拉一页
 *
 * 一次 500 个系列，不做分页。Komga 的单页上限就是 500，绝大多数库一屏就装得下；
 * 真有几千个系列的用户，用 Komga 自己的搜索更合适 —— 这里加搜索框属于为了
 * 极端情况把主流程做复杂。
 */
@Composable
fun KomgaBrowserPage(
    base: String,
    auth: String?,
    series: KomgaClient.Series?,
    onOpenSeries: (KomgaClient.Series) -> Unit,
    onOpenBook: (KomgaClient.Series, KomgaClient.Book, List<KomgaClient.Book>) -> Unit,
    topInset: Dp = 0.dp,
    /** 下拉刷新计数，抬一下重新拉当前这一层 */
    reloadTick: Int = 0
) {
    if (series == null) {
        SeriesGrid(base, auth, onOpenSeries, topInset, reloadTick)
    } else {
        BookList(base, auth, series, onOpenBook, topInset, reloadTick)
    }
}

// ------------------------------------------------------------------ 系列网格

@Composable
private fun SeriesGrid(
    base: String,
    auth: String?,
    onOpen: (KomgaClient.Series) -> Unit,
    topInset: Dp,
    reloadTick: Int
) {
    val c = MangaTheme.colors
    var list by remember(base) { mutableStateOf<List<KomgaClient.Series>>(emptyList()) }
    var error by remember(base) { mutableStateOf<String?>(null) }
    var loading by remember(base) { mutableStateOf(true) }

    LaunchedEffect(base, auth, reloadTick) {
        loading = true
        error = null
        when (val r = KomgaClient.series(base, auth)) {
            is KomgaClient.Res.Ok -> list = r.v
            is KomgaClient.Res.Fail -> {
                error = r.message
                // 刷新失败别把已经拉到的列表清空 —— 断网时还能翻已缓存的封面
                if (list.isEmpty()) list = emptyList()
            }
        }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        if (list.isEmpty() && !loading) {
            Hint(error ?: "这个库里还没有系列")
            return@Box
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 6.dp + topInset, bottom = 100.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(list, key = { it.id }) { s -> SeriesCard(s, auth, onOpen = { onOpen(s) }) }
        }
    }
}

@Composable
private fun SeriesCard(s: KomgaClient.Series, auth: String?, onOpen: () -> Unit) {
    val c = MangaTheme.colors
    val ctx = LocalContext.current
    // 封面按 URL 走磁盘缓存（KomgaThumb），produceState 只管把位图搬上屏
    val cover by produceState<Bitmap?>(null, s.thumbUrl) {
        value = KomgaThumb.load(ctx, s.thumbUrl, auth)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(10.dp))
                .background(coverPalette(s.title)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = cover
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium
                )
            } else {
                Text(
                    s.title.take(1),
                    style = MangaType.display,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            s.title,
            style = MangaType.bodyMedium,
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${s.booksCount} 话",
            style = MangaType.labelSmall,
            color = c.sub,
            maxLines = 1
        )
    }
}

// ------------------------------------------------------------------ 单话列表

@Composable
private fun BookList(
    base: String,
    auth: String?,
    series: KomgaClient.Series,
    onOpen: (KomgaClient.Series, KomgaClient.Book, List<KomgaClient.Book>) -> Unit,
    topInset: Dp,
    reloadTick: Int
) {
    val c = MangaTheme.colors
    var books by remember(series.id) { mutableStateOf<List<KomgaClient.Book>>(emptyList()) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var loading by remember(series.id) { mutableStateOf(true) }

    LaunchedEffect(series.id, auth, reloadTick) {
        loading = true
        error = null
        when (val r = KomgaClient.books(base, auth, series.id)) {
            is KomgaClient.Res.Ok -> books = r.v
            is KomgaClient.Res.Fail -> error = r.message
        }
        loading = false
    }

    if (books.isEmpty() && !loading) {
        Hint(error ?: "这部漫画里没有能读的话")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp + topInset, bottom = 100.dp)
    ) {
        items(books, key = { it.id }) { b ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .clickable { onOpen(series, b, books) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        b.title,
                        style = MangaType.titleSmall,
                        color = c.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("${b.pages} 页", style = MangaType.bodySmall, color = c.sub)
                }
                Text("阅读", style = MangaType.bodyMedium, color = c.accent)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    val c = MangaTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MangaType.bodyMedium, color = c.sub)
    }
}
