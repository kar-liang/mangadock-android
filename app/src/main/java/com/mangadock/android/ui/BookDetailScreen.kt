@file:OptIn(ExperimentalFoundationApi::class)
package com.mangadock.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import com.mangadock.android.TopBar
import com.mangadock.android.TopBarContentH
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.glass.glassSource
import com.mangadock.android.glass.rememberGlassSource
import com.mangadock.android.ui.prism.PrismCircleButton
import com.mangadock.android.ui.prism.SortGlyph
import com.mangadock.android.ui.prism.PrismDialog
import com.mangadock.android.ui.prism.PrismDialogAction
import com.mangadock.android.ui.prism.PrismDialogPlacement
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.bangumi.BangumiCharacter
import com.mangadock.android.bangumi.BangumiClient
import com.mangadock.android.bangumi.BangumiSubject
import com.mangadock.android.bookshelf.BookItem
import com.mangadock.android.bookshelf.CoverStore
import com.mangadock.android.pages.BooksLayout
import com.mangadock.android.pages.coverPalette
import com.mangadock.android.pages.humanSize
import com.mangadock.android.pages.isImageFile
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.reader.NextChapter
import com.mangadock.android.reader.ReaderSession
import com.mangadock.android.reader.ReadingProgress
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.webdav.DavEntry
import com.mangadock.android.webdav.DavResult
import com.mangadock.android.webdav.DirCache
import com.mangadock.android.webdav.WebDavClient
import com.mangadock.android.download.DownloadStore
import kotlinx.coroutines.launch

// v21：全部走语义色层，深色 / 动态取色自动跟上
private val INK: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.ink
private val SUB: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.sub
private val CARD: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.surface
private val PAGE_BG: Color
    @Composable @ReadOnlyComposable get() = MangaTheme.colors.bg

private val ARCHIVE_EXT = setOf("cbz", "zip", "cbr", "rar", "7z")
private val OPENABLE_EXT = setOf("cbz", "zip")

/**
 * v19 漫画详情页。按老大给的参考图：返回圆钮 + 居中标题 + 右侧正序/倒序，
 * 下面是「章节 (N)」和一行行白卡片章节。
 *
 * 关键设计：**完全不碰文件页的 davPath / tab**。它自己按 book.path 去 PROPFIND，
 * 复用 [DirCache] 走「本地缓存先上屏 → 后台刷新」，所以从书架点进来不会把
 * 文件页的当前目录改掉，返回栈也干净。
 *
 * 头部信息卡从 v23 起**同时承载 Bangumi 元数据**（评分 / 简介 / 标签），
 * v24 起连「更换 / 手动关联 / 取消关联 / 恢复自动匹配」入口也挂在同一张卡底部。
 * 只有一张卡：书名、封面、章节数、进度、元信息全在 `DetailHeader` 里，
 * 别再另起一层卡片 —— v23 第一版犯过这个错，会出现两张卡重复书名和封面。
 *
 * v26：目录型章节也进「下一话」队列。[inherited] 是从父目录带下来的兄弟话队列
 * （一人之下这种「一章一个文件夹」的库靠它串起来）；[onOpenFolder] 会把本级的
 * 队列继续往下传，所以从任意一级点进去都能一路「下一话」到底。
 */
@Composable
fun BookDetailScreen(
    book: BookItem,
    settings: AppSettings,
    dirCache: DirCache,
    progress: (String) -> ReadingProgress?,
    onBack: () -> Unit,
    /** (子目录, 父目录的兄弟话队列) */
    onOpenFolder: (BookItem, List<NextChapter>) -> Unit,
    onOpenSession: (ReaderSession) -> Unit,
    onNotice: (String) -> Unit,
    /** 父目录传下来的兄弟话队列；从书架直接进来时为空 */
    inherited: List<NextChapter> = emptyList(),
    /** v66：FlClash CommonDialog 的 backdrop 玻璃，与三页/顶栏/底栏/菜单共用同一层 */
    glass: GlassSource? = null,
    modifier: Modifier = Modifier
) {
    var entries by remember(book.path) { mutableStateOf<List<DavEntry>>(emptyList()) }
    var loading by remember(book.path) { mutableStateOf(true) }
    var error by remember(book.path) { mutableStateOf<String?>(null) }
    var offline by remember(book.path) { mutableStateOf(false) }
    var tick by remember(book.path) { mutableIntStateOf(0) }
    var ascending by remember(book.path) { mutableStateOf(true) }
    /** v89：排序切换的轻提示。v91 从系统 Toast 换成全站共用的 PillToast。 */
    var sortHint by remember { mutableStateOf<String?>(null) }

    /**
     * v92：元数据选择框的开关提到**页面根层级**。
     *
     * 它原来住在 `DetailHeader` 里，而 `DetailHeader` 是 LazyColumn 的一个
     * `item(key = "info")` —— `Box(fillMaxSize)` 在 item 内只拿到那一条卡片的高度，
     * 所以 v91 的 `PrismDialogPlacement.Top` 只是「贴在那张卡的顶部」，
     * 真机 dump 出来标题仍在 y≈1694/1920（屏幕 88%），看起来还是从下面冒出来。
     * 只有挂在页面根 Box 上，弹窗的 fillMaxSize 才是真正整屏。
     */
    var showPicker by remember(book.path) { mutableStateOf(false) }
    /** v24：手动关联 / 取消关联后靠它重跑一次解析，而不是新建一张卡。 */
    var linkTick by remember(book.path) { mutableIntStateOf(0) }

    // ---- v28 离线下载 ----
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { DownloadStore.init(ctx) }
    val dlVersion by DownloadStore.version.collectAsState()
    val dlProgressMap by DownloadStore.progress.collectAsState()
    /** 点了 ✓ 之后要确认删哪一话 */
    var downloadMenu by remember(book.path) { mutableStateOf<DownloadTarget?>(null) }

    fun enqueueOne(key: String) {
        val entry = entries.firstOrNull { it.path == key } ?: return
        val isDir = entry.isDir
        val job = DownloadStore.Job(
            key = key,
            title = entry.name.substringBeforeLast('.'),
            url = if (isDir) null else WebDavClient.fileUrl(settings.webdavUrl, key),
            dirPath = if (isDir) key else null
        )
        DownloadStore.enqueue(ctx, listOf(job), settings)
    }

    /** 这一话现在的下载状态文案 + 点击行为。null 表示没这一话的概念（图片文件夹）。 */
    fun dlStateFor(key: String): Triple<String?, Boolean, (() -> Unit)?> {
        val rec = DownloadStore.record(ctx, key)
        if (rec != null) return Triple("已下载", true) { downloadMenu = DownloadTarget(key, rec.title, true) }
        val p = dlProgressMap[key]
        if (p != null) return Triple("${(p * 100).toInt()}%", false) { DownloadStore.cancel(key) }
        if (DownloadStore.isQueued(key)) return Triple("排队中", false) { DownloadStore.cancel(key) }
        return Triple("下载", false) { enqueueOne(key) }
    }

    LaunchedEffect(
        book.path, tick,
        settings.webdavUrl, settings.webdavUser, settings.webdavPass
    ) {
        if (!settings.webdavConfigured) {
            entries = emptyList()
            loading = false
            error = "还没连接 WebDAV，去设置里填服务器地址。"
            return@LaunchedEffect
        }
        val ck = dirCache.key(settings.webdavUrl, settings.webdavUser, book.path)
        val cached = dirCache.read(ck)
        if (cached != null) {
            entries = cached
            loading = true
            error = null
            offline = false
        } else {
            // 没缓存时不能沿用上一本漫画的列表，否则路径和内容会对不上
            entries = emptyList()
            loading = true
            error = null
            offline = false
        }
        when (
            val r = WebDavClient.list(
                settings.webdavUrl, settings.webdavUser, settings.webdavPass, book.path
            )
        ) {
            is DavResult.Ok -> {
                entries = r.entries
                loading = false
                error = null
                offline = false
                dirCache.write(ck, r.entries)
            }

            is DavResult.Fail -> {
                loading = false
                if (cached != null) {
                    offline = true
                    error = null
                } else {
                    entries = emptyList()
                    offline = false
                    error = r.message
                }
            }
        }
    }

    val visible = remember(entries) { entries.filter { !it.name.startsWith(".") } }
    val archives = remember(visible) {
        visible.filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXT }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
    }
    val folders = remember(visible) {
        visible.filter { it.isDir }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
    }
    val images = remember(visible) {
        visible.filter { !it.isDir && isImageFile(it.name) }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
    }
    val orderedArchives = if (ascending) archives else archives.asReversed()
    val orderedFolders = if (ascending) folders else folders.asReversed()

    /**
     * 有序章节队列：阅读器靠它维持「下一话」，不再依赖文件页当前目录。
     *
     * v26：**子目录也算一话**。之前的队列只收 cbz/zip，于是「一章一个文件夹」的库
     * （一人之下 841 个章节目录）永远出不来「下一话」。现在子目录用 [NextChapter.dirPath]
     * 进队，页数等阅读器里 PROPFIND 之后再知道 —— 详情页不该为了翻页把 841 个目录全列一遍。
     */
    val ownQueue: List<NextChapter> = remember(orderedArchives, orderedFolders, settings.webdavUrl) {
        val fromFiles = orderedArchives.mapNotNull { e ->
            val ext = e.name.substringAfterLast('.', "").lowercase()
            if (ext !in OPENABLE_EXT) return@mapNotNull null
            val u = WebDavClient.fileUrl(settings.webdavUrl, e.path) ?: return@mapNotNull null
            NextChapter(title = e.name.substringBeforeLast('.'), key = e.path, cbzUrl = u)
        }
        val fromDirs = orderedFolders.map { e ->
            NextChapter(title = e.name, key = e.path, dirPath = e.path)
        }
        // 混着放（有的库既有 cbz 又有目录章节）时按名字自然序排，保持「第 1 话 → 第 2 话」
        (fromFiles + fromDirs).sortedWith { a, b -> naturalOrder(a.title, b.title) }
    }
    /** 本级有子目录/章节就用自己的；纯章节页（目录里只有图片）才吃父级传下来的队列。 */
    val queue: List<NextChapter> = if (ownQueue.isNotEmpty()) ownQueue else inherited

    /** 用某个 key 起一个会话：从队列里找它后面的那话，找不到就没有「下一话」。 */
    fun sessionAt(
        title: String,
        key: String,
        url: String?,
        dirPath: String? = null,
        pages: List<DavEntry> = emptyList()
    ): ReaderSession {
        val i = queue.indexOfFirst { it.key == key }
        return ReaderSession(
            title = title,
            key = key,
            pages = pages,
            cbzUrl = url,
            dirPath = dirPath,
            startIndex = progress(key)?.index ?: 0,
            next = if (i >= 0) queue.getOrNull(i + 1) else null,
            chapters = queue
        )
    }

    fun openArchive(e: DavEntry) {
        val ext = e.name.substringAfterLast('.', "").lowercase()
        if (ext !in OPENABLE_EXT) {
            onNotice("「${e.name}」\n\n这种压缩格式还不支持，转成 cbz 或 zip 就能直接读。")
            return
        }
        val u = WebDavClient.fileUrl(settings.webdavUrl, e.path)
        if (u == null) {
            onNotice("服务器地址拼不出文件 URL，去设置里检查一下。")
            return
        }
        onOpenSession(sessionAt(e.name.substringBeforeLast('.'), e.path, u))
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // v87：详情页自己的玻璃源。顶栏圆钮要和底栏一样采**本页**内容，
    // 不能借父级的 appGlassSource（那层录的是三页，详情页盖上去之后
    // 按钮会采到别的页面的封面色块 —— 老大 13:30 截图指出过）。
    val detailGlass = rememberGlassSource()

    Box(modifier.fillMaxSize().background(PAGE_BG)) {
        LazyColumn(
            Modifier.fillMaxSize().glassSource(detailGlass),
            contentPadding = PaddingValues(
                top = statusTop + TopBarContentH + 12.dp,
                bottom = 28.dp
            )
        ) {
            item(key = "info") {
                DetailHeader(
                    book = book,
                    settings = settings,
                    chapters = archives.size,
                    folders = folders.size,
                    images = images.size,
                    offline = offline,
                    loading = loading,
                    progress = progress(book.path),
                    onReadImages = {
                        // 目录里直接放图片（不是压缩包、也不是子目录章节）：本身就是一话
                        onOpenSession(sessionAt(book.title, book.path, null, pages = images))
                    },
                    // v92：弹窗开关与重跑计数都在页面根层级，这里只传回调
                    linkTick = linkTick,
                    onRequestPicker = { showPicker = true },
                    onRelink = { linkTick++ },
                    glass = glass
                )
            }

            error?.let { msg ->
                item(key = "err") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MangaTheme.colors.dangerBg)
                            .padding(12.dp)
                    ) {
                        Text(msg, style = MangaType.bodySmall, color = MangaTheme.colors.danger, maxLines = 4)
                    }
                }
            }

            item(key = "sec-chapters") {
                SectionRow(
                    "章节 (${archives.size})",
                    onRefresh = { tick++ },
                    onDownloadAll = {
                        // 一次把整本排进队列（已下过的会自己跳过）
                        val jobs = orderedArchives.mapNotNull { e ->
                            val url = WebDavClient.fileUrl(settings.webdavUrl, e.path) ?: return@mapNotNull null
                            DownloadStore.Job(
                                key = e.path,
                                title = e.name.substringBeforeLast('.'),
                                url = url,
                                dirPath = null
                            )
                        }
                        if (jobs.isEmpty()) onNotice("这些章节不能离线下载")
                        else {
                            DownloadStore.enqueue(ctx, jobs, settings)
                            onNotice("已加入下载队列：${jobs.size} 话")
                        }
                    }
                )
            }

            if (orderedArchives.isEmpty() && images.isEmpty() && folders.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 30.dp)
                            .clickable { tick++ },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                loading -> "读取中…"
                                error != null -> "点这里重试"
                                else -> "这个目录里没有可读的章节"
                            },
                            style = MangaType.bodyMedium,
                            color = SUB
                        )
                    }
                }
            }

            // 章节行是 Lazy item：有些库一本漫画几百话，不能一次性全组合出来
            items(
                count = orderedArchives.size,
                key = { i -> "ch:${orderedArchives[i].path}" }
            ) { i ->
                val e = orderedArchives[i]
                val dl = dlStateFor(e.path)
                ChapterCard(
                    name = e.name.substringBeforeLast('.'),
                    meta = "${humanSize(e.size)}${progressSuffix(progress(e.path))}",
                    folder = false,
                    onClick = { openArchive(e) },
                    dlText = dl.first,
                    dlAccent = dl.second,
                    onDlClick = dl.third
                )
            }

            if (orderedFolders.isNotEmpty()) {
                item(key = "sec-folders") { SectionRow("子目录 (${folders.size})") {} }
                items(
                    count = orderedFolders.size,
                    key = { i -> "dir:${orderedFolders[i].path}" }
                ) { i ->
                    val e = orderedFolders[i]
                    val dl = dlStateFor(e.path)
                    ChapterCard(
                        name = e.name,
                        // 目录可能就是一话（里面直接放图），有进度就显示出来
                        meta = "目录" + progressSuffix(progress(e.path)),
                        folder = true,
                        dlText = dl.first,
                        dlAccent = dl.second,
                        onDlClick = dl.third,
                        onClick = {
                            // 把本级的兄弟话队列一起传下去，子目录里才有「下一话」
                            onOpenFolder(
                                BookItem(
                                    key = e.path,
                                    title = e.name,
                                    path = e.path,
                                    isCbz = false
                                ),
                                queue
                            )
                        }
                    )
                }
            }
        }

        // ---- 顶栏：盖在内容之上（v76 完整 FloatingHeader）----
        // v75 只换了组件、没换结构：Column 里顶栏占固定高度，内容从它下面开始，
        // 看不到「列表从状态栏底下滚上来、被 scrim 压成淡影」的效果。
        // 老大 13:23 给的主界面文件页截图就是那个效果，现在改成 Box：
        //   LazyColumn 整屏铺满 + topInset，TopBar 最后画、浮在内容上。
        // 对话框比顶栏更靠后画，仍压在顶栏之上。
        TopBar(
            statusTop = statusTop,
            title = book.title,
            // v76：老大给的效果图就是主界面那种 28sp 大标题浮在内容上。
            // v75 曾经收成常规字号（怕返回钮挤扁），实际 Row 里标题是 weight(1f)，
            // 被压缩的只有文字不会挤到按钮，长书名走省略号即可。
            large = true,
            onBack = onBack,
            // v87：传本页自己的玻璃源，返回 / 排序两颗圆钮都是底栏同款材质。
            glass = detailGlass,
            // v87：右上「正序 ⇅」胶囊 → 与返回钮同尺寸、同材质的 44dp 圆形按钮。
            // v89：字符「⇅」换成 SortGlyph（22dp / 2.2dp 圆头描边的手绘图标），
            // 和返回箭头、锁图标同一套笔画，不再是又细又小的字体符号；
            // 箭头方向直接跟 ascending 走，正序 ↑ / 倒序 ↓ 一眼能看出。
            // 切换提示不走 onNotice（那是整页压暗的 PrismDialog），
            // v91：也不用系统 Toast —— 改用和主界面同一个 PillToast，
            // 全站只有一种轻提示。
            trailing = {
                PrismCircleButton(
                    onClick = {
                        ascending = !ascending
                        sortHint = if (ascending) "已切换为正序" else "已切换为倒序"
                    },
                    glass = detailGlass,
                    dark = MangaTheme.colors.dark,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    SortGlyph(
                        tint = MangaTheme.colors.ink,
                        ascending = ascending,
                    )
                }
            }
        )

        // 删除离线下载的确认（下载是可恢复的，但仍然问一句，避免误触删掉几十兆）
        downloadMenu?.let { target ->
            val scope2 = rememberCoroutineScope()
            // v66：M3 AlertDialog -> FlClash CommonDialog 1:1 镜像（destructive 红色末项）
            PrismDialog(
                title = "删除下载",
                onDismissRequest = { downloadMenu = null },
                glass = glass,
                destructive = true,
                actions = listOf(
                    PrismDialogAction("取消") { downloadMenu = null },
                    PrismDialogAction("删除") {
                        val t = target
                        downloadMenu = null
                        scope2.launch {
                            DownloadStore.remove(ctx, t.key)
                            onNotice("已删除「${t.title}」的离线文件")
                        }
                    },
                ),
            ) { _ ->
                Text("「${target.title}」的离线文件会被删掉，服务器上的原文件不受影响。")
            }
        }

        // v92：元数据选择框。挂在页面根 Box 上（不是 LazyColumn item 里），
        // PrismDialogPlacement.Top 才真的是「浮在页面上方」。
        if (showPicker) {
            BangumiLinkDialog(
                initialQuery = book.title,
                settings = settings,
                onDismiss = { showPicker = false },
                onPick = { id ->
                    BangumiClient.link(ctx, book.path, id)
                    showPicker = false
                    linkTick++
                },
                glass = glass
            )
        }

        // v91：排序切换提示，样式与主界面完全一致（详情页盖住底栏，40dp 就够）。
        PillToast(sortHint, onDismiss = { sortHint = null }, bottomPadding = 40.dp)
    }
}

private fun progressSuffix(p: ReadingProgress?): String = when {
    p == null -> ""
    p.finished -> " · 已读完"
    else -> " · 第 ${p.index + 1}/${p.total} 页"
}

/**
 * 头部信息卡：封面 + 书名 + 章节数 / 进度 / 直接阅读，v23 起 Bangumi 元数据
 * **并进这同一张卡**（别再另起一层，会出现两张卡重复书名和封面）。
 *
 * 所有加载都在这个 item 内部：只有进入组合树的可见卡片才发请求，书架不预取。
 * 拿不到 Bangumi 数据时只是少一段元信息，卡片照常显示，不摆空卡 / loading / 死按钮。
 */
@Composable
private fun DetailHeader(
    book: BookItem,
    settings: AppSettings,
    chapters: Int,
    folders: Int,
    images: Int,
    offline: Boolean,
    loading: Boolean,
    progress: ReadingProgress?,
    onReadImages: () -> Unit,
    /** v92：外部（页面根层级）维护的重跑计数，关联/取消关联后 +1 */
    linkTick: Int,
    /** v92：请求打开元数据选择框。开关已上移到页面根层级，这里只发信号 */
    onRequestPicker: () -> Unit,
    /** v92：取消关联 / 恢复自动匹配后重跑解析 */
    onRelink: () -> Unit,
    glass: GlassSource? = null
) {
    val ctx = LocalContext.current
    var bangumi by remember(book.path) {
        mutableStateOf(BangumiUiState(loading = settings.bangumiEnabled))
    }
    LaunchedEffect(
        book.path, book.title,
        settings.bangumiEnabled, settings.bangumiBase,
        linkTick
    ) {
        if (!settings.bangumiEnabled) {
            bangumi = BangumiUiState()
            return@LaunchedEffect
        }
        bangumi = BangumiUiState(loading = true)
        val got = BangumiClient.subject(ctx, book.title, book.path, settings)
        bangumi = BangumiUiState(subject = got)
    }
    val s = bangumi.subject
    // 封面统一走 CoverStore（v25）：自定义 > Bangumi > WebDAV 首图 > 首字色块。
    // s?.id 进 key 是为了元数据一解析出来就换上正式封面。
    val localCover by produceState<ImageBitmap?>(
        initialValue = null,
        book.key, s?.id, linkTick,
        settings.webdavUrl, settings.webdavUser, settings.webdavPass,
        settings.bangumiEnabled, settings.bangumiBase
    ) {
        value = CoverStore.load(ctx, book, settings)?.asImageBitmap()
    }
    // 角色栏：跟元数据同源，拿不到就整段不渲染，不占位、不摆 loading
    val characters by produceState<List<BangumiCharacter>>(
        initialValue = emptyList(),
        s?.id, settings.bangumiEnabled, settings.bangumiBase
    ) {
        value = s?.let { BangumiClient.characters(ctx, it.id, settings) } ?: emptyList()
    }
    var expanded by remember(book.path) { mutableStateOf(false) }
    val bmp = localCover

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CARD)
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(84.dp)
                    .aspectRatio(BooksLayout.COVER_RATIO)
                    .clip(RoundedCornerShape(9.dp))
                    .background(coverPalette(book.title)),
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
                        book.title.trim().take(1).ifEmpty { "漫" },
                        fontSize = 30.sp,
                        color = Color.White.copy(alpha = 0.92f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MangaType.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = INK,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("$chapters 章")
                        if (folders > 0) append(" · $folders 个子目录")
                        if (offline) append(" · 离线缓存")
                        else if (loading) append(" · 刷新中…")
                    },
                    style = MangaType.bodySmall,
                    color = SUB
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        progress == null -> "还没读过"
                        progress.finished -> "已读完"
                        else -> "读到第 ${progress.index + 1} / ${progress.total} 页"
                    },
                    style = MangaType.bodySmall,
                    color = settings.accentColor
                )
                if (images > 0) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(settings.accentColor.copy(alpha = 0.12f))
                            .clickable { onReadImages() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "直接阅读（$images 页）",
                            style = MangaType.bodySmall,
                            color = settings.accentColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (s != null) {
            BangumiMeta(s, settings.accentColor, expanded) { expanded = !expanded }
            if (characters.isNotEmpty()) {
                BangumiCharacters(characters, settings)
            }
        }

        // v24：手动关联入口也在这张卡里。解析没结束时不摆 loading、不摆死按钮。
        if (settings.bangumiEnabled && !bangumi.loading) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s != null) "元数据来自 Bangumi" else "没自动对上这本书",
                    style = MangaType.labelSmall,
                    color = SUB,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                BangumiAction(
                    if (s != null) "更换" else "手动关联",
                    settings.accentColor
                ) { onRequestPicker() }
                if (s != null) {
                    Spacer(Modifier.width(8.dp))
                    BangumiAction("取消关联", settings.accentColor) {
                        BangumiClient.mute(ctx, book.path)
                        onRelink()
                    }
                } else if (BangumiClient.mapping(ctx, book.path) != null) {
                    Spacer(Modifier.width(8.dp))
                    BangumiAction("恢复自动匹配", settings.accentColor) {
                        BangumiClient.unlink(ctx, book.path)
                        onRelink()
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** 卡片底部的小动作片，沿用卡内已有的 accent 淡底样式。 */
@Composable
private fun BangumiAction(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            style = MangaType.labelSmall,
            color = accent,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class BangumiUiState(
    val loading: Boolean = false,
    val subject: BangumiSubject? = null
)

private data class BangumiCandidates(
    val loading: Boolean = false,
    val keyword: String = "",
    val items: List<BangumiSubject>? = null
)

/**
 * v24：手动搜索 / 指定 Bangumi 条目，对齐 iOS `Views/BangumiLinkView.swift`。
 *
 * 关键差别：**不做自动标题过滤**。自动匹配里那条「归一化后互相包含」的门槛是为了
 * 防止张冠李戴，但手动场景恰好就是「默认匹配对不上」，所以 12 条候选原样给用户挑。
 * 关键词可以随便改，失败也能点「搜索」原地重试（submitTick 强制重跑）。
 */
@Composable
private fun BangumiLinkDialog(
    initialQuery: String,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    glass: GlassSource? = null
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf(initialQuery) }
    var submitted by remember { mutableStateOf(initialQuery.trim()) }
    var submitTick by remember { mutableIntStateOf(0) }
    val state by produceState(
        initialValue = BangumiCandidates(loading = true, keyword = submitted),
        submitted, submitTick
    ) {
        value = BangumiCandidates(
            keyword = submitted,
            items = BangumiClient.candidates(ctx, submitted, settings)
        )
    }
    fun submit() {
        val q = query.trim()
        if (q.isNotEmpty()) {
            submitted = q
            submitTick++
        }
    }

    // v66：M3 AlertDialog -> FlClash InputDialog / CommonDialog 1:1 镜像。
    // hasFields = true：标题走 body 字重、内容左右内边距 16（不是 30）。
    PrismDialog(
        title = "关联 Bangumi 条目",
        onDismissRequest = onDismiss,
        glass = glass,
        hasFields = true,
        // v91：输入框 + 12 条候选，居中会正好压住屏幕中间，改成浮在页面上方。
        placement = PrismDialogPlacement.Top,
        actions = listOf(PrismDialogAction("关闭", onDismiss)),
    ) { _ ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("漫画名", style = MangaType.bodySmall) },
                    textStyle = MangaType.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { submit() }) {
                    Text("搜索", style = MangaType.bodyLarge, color = settings.accentColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.loading -> Text(
                    "搜索中…",
                    style = MangaType.bodySmall,
                    color = SUB
                )

                state.items == null -> Text(
                    "连不上接口。检查代理，或在设置里填一个可用的接口地址后重试。",
                    style = MangaType.bodySmall,
                    color = MangaTheme.colors.danger
                )

                state.items!!.isEmpty() -> Text(
                    "没搜到条目，换个关键词试试。",
                    style = MangaType.bodySmall,
                    color = SUB
                )

                // 候选最多 12 条，用普通 Column 按内容收拢；只有超高时才滚动。
                // LazyColumn 会撑满 heightIn 的上限，条目少时留出一块尴尬空白。
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.items!!.forEach { candidate ->
                        BangumiCandidateRow(candidate, settings) {
                            onPick(candidate.id)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v25：候选行从「两行文字」升级成「封面 + 名称 + 评分/平台/日期 + 简介」。
 *
 * 之前只有标题和 id，用户得靠编号猜是哪一本；封面是判断「对不对」最快的信号，
 * 所以封面必须显示，缩略图走 `images.small/grid`，别拉 800px 原图。
 */
@Composable
private fun BangumiCandidateRow(
    s: BangumiSubject,
    settings: AppSettings,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(
        initialValue = null,
        s.thumbUrl, settings.bangumiBase
    ) {
        value = s.thumbUrl?.let {
            BangumiClient.image(ctx, it, settings, targetW = 200)
        }
    }
    // 一行放不下「评分 + 人数 + 平台 + 日期 + id」，按重要性拆：
    // 日期跟着标题走，id 不进 UI（只是内部映射用的，官方 App 也不展示）。
    val meta = buildString {
        s.score?.let { append("★ ${"%.1f".format(it)}") }
        s.ratingTotal?.let {
            if (isNotEmpty()) append(" · ")
            append("$it 人评分")
        }
        s.platform?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
    }.ifEmpty { "暂无评分" }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(48.dp)
                .aspectRatio(BooksLayout.COVER_RATIO)
                .clip(RoundedCornerShape(6.dp))
                .background(coverPalette(s.displayTitle)),
            contentAlignment = Alignment.Center
        ) {
            val b = thumb
            if (b != null) {
                Image(
                    b,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    s.displayTitle.trim().take(1).ifEmpty { "漫" },
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.displayTitle,
                    style = MangaType.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = INK,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                s.date?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MangaType.labelSmall, color = SUB, maxLines = 1)
                }
            }
            s.name.takeIf { it.isNotBlank() && it != s.displayTitle }?.let {
                Text(
                    it,
                    style = MangaType.labelSmall,
                    color = SUB,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                meta,
                style = MangaType.labelSmall,
                color = SUB,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
            if (!s.summary.isNullOrBlank()) {
                Text(
                    s.summary,
                    style = MangaType.labelSmall,
                    color = MangaTheme.colors.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 18.sp, color = MangaTheme.colors.chevron)
    }
}

@Composable
private fun SectionRow(
    title: String,
    onDownloadAll: (() -> Unit)? = null,
    onRefresh: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MangaType.bodyMedium,
            color = SUB,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (title.startsWith("章节")) {
            if (onDownloadAll != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDownloadAll() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("下载整本", style = MangaType.bodySmall, color = SUB)
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRefresh() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("刷新", style = MangaType.bodySmall, color = SUB)
            }
        }
    }
}

/** 参考图里的白卡片行：左图标 / 两行文字 / 右箭头。 */
@Composable
private fun ChapterCard(
    name: String,
    meta: String,
    folder: Boolean,
    onClick: () -> Unit,
    /** v28 离线下载：null = 不显示，状态文案由调用方算好 */
    dlText: String? = null,
    dlAccent: Boolean = false,
    onDlClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(CARD)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChapterIcon(folder)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MangaType.bodyLarge,
                color = INK,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                meta,
                style = MangaType.labelSmall,
                color = SUB,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        if (dlText != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onDlClick != null) Modifier.clickable { onDlClick() } else Modifier)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    dlText,
                    style = MangaType.labelSmall,
                    color = if (dlAccent) MangaTheme.colors.ink else SUB
                )
            }
        } else {
            Text("›", fontSize = 20.sp, color = MangaTheme.colors.chevron)
        }
    }
}

@Composable
private fun ChapterIcon(folder: Boolean) {
    // Canvas 的 DrawScope 不是 @Composable 上下文，先把颜色取出来
    val icon = MangaTheme.colors.sub
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path()
        if (folder) {
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
        } else {
            p.moveTo(s * 0.16f, s * 0.10f)
            p.lineTo(s * 0.64f, s * 0.10f)
            p.lineTo(s * 0.88f, s * 0.34f)
            p.lineTo(s * 0.88f, s * 0.90f)
            p.lineTo(s * 0.16f, s * 0.90f)
            p.close()
            p.moveTo(s * 0.64f, s * 0.10f)
            p.lineTo(s * 0.64f, s * 0.34f)
            p.lineTo(s * 0.88f, s * 0.34f)
            drawLine(
                icon,
                Offset(s * 0.30f, s * 0.52f),
                Offset(s * 0.74f, s * 0.52f),
                stroke.width, StrokeCap.Round
            )
            drawLine(
                icon,
                Offset(s * 0.30f, s * 0.68f),
                Offset(s * 0.66f, s * 0.68f),
                stroke.width, StrokeCap.Round
            )
        }
        drawPath(p, icon, style = stroke)
    }
}

/**
 * v25：角色栏，对齐 iOS `BangumiCharactersRow.swift`。
 *
 * 横向最多 20 个（再多要滑很久），只组合可见的几个；头像用 Bangumi 的
 * `grid` 裁脸方图（v87，见 BangumiClient 的角色解析），和封面共用同一个
 * 代理 client + 磁盘缓存。拿不到角色数据时外层直接不调用本函数，
 * 不占位、不摆 loading。
 */
@Composable
private fun BangumiCharacters(list: List<BangumiCharacter>, settings: AppSettings) {
    Spacer(Modifier.height(12.dp))
    Text(
        "角色",
        style = MangaType.labelSmall,
        color = SUB,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list.take(20), key = { it.id }) { c ->
            BangumiCharacterCard(c, settings)
        }
    }
}

@Composable
private fun BangumiCharacterCard(c: BangumiCharacter, settings: AppSettings) {
    val ctx = LocalContext.current
    val portrait by produceState<ImageBitmap?>(
        initialValue = null,
        c.portraitUrl
    ) {
        value = c.portraitUrl?.let {
            // v88：iOS 方案用 medium/large 原图，解码宽度回到默认 400，
            // 别再降采样到 160 —— 64dp 在 2.5~3x 屏上是 160~192px，
            // 之前那个 160 正是「糊」的另一半原因。
            BangumiClient.image(ctx, it, settings)
        }
    }
    Column(
        Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(64.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(MangaTheme.colors.chip),
            contentAlignment = Alignment.Center
        ) {
            val p = portrait
            if (p != null) {
                Image(
                    p,
                    contentDescription = null,
                    // v87：万一某个角色只给到立绘，也优先保留上半身/脸，
                    // 不要居中裁成一条躯干。
                    alignment = Alignment.TopCenter,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    c.name.trim().take(1).ifEmpty { "角" },
                    style = MangaType.bodySmall,
                    color = SUB
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            c.name,
            style = MangaType.labelSmall,
            color = INK,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        c.relation?.let {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(settings.accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(it, style = MangaType.micro, color = settings.accentColor, maxLines = 1)
            }
        }
    }
}

/**
 * v23：并进头部信息卡下半段的 Bangumi 元数据，对齐 iOS `Views/BangumiCard.swift`。
 *
 * 书名 / 封面由外层那张卡统一提供，这里只补评分、日期、作者、简介、标签，
 * **不重复书名、不再画第二张卡**。拿不到数据时外层干脆不调用本函数。
 * 底部「元数据来自 Bangumi」+ 更换 / 关联入口由外层统一渲染，两态不重复。
 */
@Composable
private fun BangumiMeta(
    s: BangumiSubject,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val hasTopLine = s.score != null || s.ratingTotal != null || !s.date.isNullOrBlank()
    if (hasTopLine) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.score != null) {
                Text(
                    "★ ${"%.1f".format(s.score)}",
                    style = MangaType.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.width(6.dp))
            }
            if (s.ratingTotal != null) {
                Text("${s.ratingTotal} 人评分", style = MangaType.bodySmall, color = SUB)
                Spacer(Modifier.width(8.dp))
            }
            if (!s.date.isNullOrBlank()) {
                Text(s.date!!, style = MangaType.bodySmall, color = SUB)
            }
        }
    }
    if (!s.author.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "作者 ${s.author}",
            style = MangaType.bodySmall,
            color = SUB,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (!s.summary.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            s.summary!!,
            style = MangaType.bodyMedium,
            color = MangaTheme.colors.body,
            maxLines = if (expanded) 100 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onToggle() }
        )
    }
    if (s.tags.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            s.tags.forEach { tag ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(tag, style = MangaType.labelSmall, color = accent, maxLines = 1)
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** 「已下载」点一下要确认删掉哪一话。 */
private data class DownloadTarget(val key: String, val title: String, val downloaded: Boolean)
