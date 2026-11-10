package com.mangadock.android.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.glass.GlassSurface
import com.mangadock.android.glass.LiquidGlassStyle
import com.mangadock.android.glass.PrismGlassBar
import com.mangadock.android.glass.glassSource
import com.mangadock.android.glass.rememberGlassSource
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.ui.MangaType
import com.mangadock.android.ui.MangaTheme
import com.mangadock.android.download.DownloadStore
import com.mangadock.android.ui.MangaMotion
import com.mangadock.android.settings.PageDirKind
import com.mangadock.android.pages.isImageFile
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.webdav.DavEntry
import com.mangadock.android.webdav.DavResult
import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

/**
 * 一次阅读会话。三种来源：
 *  - 图片文件夹：[pages] 有值，[cbzUrl] / [dirPath] 为空
 *  - CBZ / ZIP：  [cbzUrl] 有值（页数要等包下来才知道）
 *  - 目录章节（v26）：[dirPath] 有值，页数等 PROPFIND 回来才知道。
 *    这条是给「一章一个文件夹」的库用的（比如一人之下 841 个章节目录）——
 *    打开时才知道有哪些页，所以不能像图片文件夹那样在进阅读器之前就把 pages 算好。
 */
data class ReaderSession(
    val title: String,
    /** 进度存在哪个 key 下，用目录路径或 cbz 的路径 */
    val key: String,
    val pages: List<DavEntry> = emptyList(),
    val cbzUrl: String? = null,
    /** v26：这一话是个目录，进阅读器时按需 PROPFIND */
    val dirPath: String? = null,
    val startIndex: Int = 0,
    /** 当前目录里排在这本后面的下一话，没有就为 null */
    val next: NextChapter? = null,
    /**
     * v19：这一话所属的**有序章节队列**。详情页和文件页各自拉自己的目录，
     * 靠这份队列维持「下一话」链，不再依赖文件页当前的 dav.entries。
     */
    val chapters: List<NextChapter> = emptyList(),
    /**
     * v98：这一话走哪套认证。
     *
     * null = 用全局那台 WebDAV 的账号密码（老路径原样不动）；非 null = 用它。
     * Komga 的页面图片必须带自己的 Basic 头，不能借用 WebDAV 的。
     */
    val authOverride: String? = null
)

/** 结尾卡上「下一话」要用的信息。只带数据，不带 lambda，方便 MainActivity 重组会话。 */
data class NextChapter(
    val title: String,
    val key: String,
    val cbzUrl: String? = null,
    /** v26：目录型章节 —— 页数要等阅读器里 PROPFIND 之后才知道 */
    val dirPath: String? = null,
    val pages: List<DavEntry> = emptyList(),
    /** v98：Komga 的话要带自己的认证头，理由见 [ReaderSession.authOverride] */
    val authOverride: String? = null
)

/** 把 [NextChapter] 变成一次新的阅读会话，并沿用原来的章节队列。 */
fun NextChapter.toSession(
    next: NextChapter? = null,
    chapters: List<NextChapter> = emptyList()
): ReaderSession = ReaderSession(
    title = title,
    key = key,
    pages = pages,
    cbzUrl = cbzUrl,
    dirPath = dirPath,
    startIndex = 0,
    next = next,
    chapters = chapters,
    authOverride = authOverride
)

/** 两种阅读模式。iOS 那边叫「分页 / 条漫」，这里同名。 */
enum class ReaderMode(val label: String) {
    PAGED("分页"), WEBTOON("条漫")
}

/** 一页怎么读出来。目录和 CBZ 在这一层之后就没区别了。给 PageTurn.kt 复用，所以是 internal。 */
internal typealias PageLoader = suspend (index: Int, targetW: Int) -> ImageDecoder.Decoded?

/**
 * 阅读器。不需要玻璃采样，所以这里是常规 Compose，不是 Canvas。
 *
 * 手势约定（分页）：
 * - 点左 / 右三分之一翻页，点中间切换浮动条
 * - 从右往左读（RTL）时，"下一页在左边"，所以点左边是下一页、往右拖是下一页
 *
 * 条漫：上下滚动，滚到哪记到哪。两种模式的进度是同一份页号。
 */
@Composable
fun ReaderScreen(
    session: ReaderSession,
    settings: AppSettings,
    onProgress: (index: Int, total: Int) -> Unit,
    onExit: () -> Unit,
    onNextChapter: (NextChapter) -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val bg = settings.readBgKind.solid
    val luma = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val ink = if (luma > 0.55f) Color(0xFF111111) else Color(0xFFEDEDF0)
    val dim = ink.copy(alpha = 0.55f)

    val auth = remember(settings.webdavUser, settings.webdavPass, session.authOverride) {
        // v98：Komga 的话自带一套头，别串用 WebDAV 的
        session.authOverride ?: WebDavClient.authHeader(settings.webdavUser, settings.webdavPass)
    }

    // ---- 取页清单：CBZ 要下包，目录章节要 PROPFIND，图片文件夹直接就有 ----
    val needsFetch = session.cbzUrl != null || session.dirPath != null
    var pages by remember(session.key) { mutableStateOf(session.pages) }
    var total by remember(session.key) {
        mutableIntStateOf(if (needsFetch) 0 else session.pages.size)
    }
    var preparing by remember(session.key) { mutableStateOf(needsFetch) }
    var zipFile by remember(session.key) { mutableStateOf<File?>(null) }
    var zipNames by remember(session.key) { mutableStateOf<List<String>>(emptyList()) }
    /** v28：这一话已经离线下载成散图，就地读本地文件 */
    var localPages by remember(session.key) { mutableStateOf<List<File>>(emptyList()) }
    var prepError by remember(session.key) { mutableStateOf<String?>(null) }

    LaunchedEffect(session.cbzUrl, session.dirPath) {
        val url = session.cbzUrl
        val dir = session.dirPath
        if (url == null && dir == null) return@LaunchedEffect

        // 离线优先：下载过就完全不碰网络（断网也能看，这是下载的意义）
        val rec = DownloadStore.record(ctx, session.key)
        if (rec != null) {
            preparing = true
            prepError = null
            if (rec.isArchive) {
                val f = DownloadStore.archiveFile(rec)
                if (f.isFile) {
                    val names = CbzStore.listPages(f)
                    zipFile = f
                    zipNames = names
                    total = names.size
                    if (names.isEmpty()) prepError = "这个下载包读不出来"
                } else {
                    prepError = "下载文件不见了，重新下一遍吧"
                }
            } else {
                val files = DownloadStore.pagesDir(rec)
                    .listFiles()
                    ?.filter { it.isFile }
                    ?.sortedWith { a, b -> naturalOrder(a.name, b.name) }
                    .orEmpty()
                localPages = files
                total = files.size
                if (files.isEmpty()) prepError = "下载的图片不见了，重新下一遍吧"
            }
            if (prepError == null) {
                preparing = false
                return@LaunchedEffect
            }
        }

        preparing = true
        prepError = null
        if (url != null) {
            val f = CbzStore.ensureLocal(ctx, url, auth)
            if (f == null) {
                prepError = CbzStore.consumeError() ?: "压缩包打不开"
            } else {
                CbzStore.consumeError()   // 兜底成功时别让上一次的失败原因留着
                val names = CbzStore.listPages(f)
                zipFile = f
                zipNames = names
                total = names.size
                if (names.isEmpty()) prepError = "包里没有图片"
            }
        } else if (dir != null) {
            // v26：目录型章节，进来才列目录。走的是和文件页同一套 PROPFIND。
            when (
                val r = WebDavClient.list(
                    settings.webdavUrl, settings.webdavUser, settings.webdavPass, dir
                )
            ) {
                is DavResult.Ok -> {
                    val imgs = r.entries
                        .filter { !it.isDir && isImageFile(it.name) }
                        .sortedWith { a, b -> naturalOrder(a.name, b.name) }
                    pages = imgs
                    total = imgs.size
                    if (imgs.isEmpty()) prepError = "这一话里没有图片"
                }

                is DavResult.Fail -> prepError = "这一话打不开：${r.message}"
            }
        }
        preparing = false
    }

    /** 画质优先 → 像素上限放宽，解码时宁可多占内存也不把宽度压到屏宽以下。 */
    val maxPixels = if (settings.highQuality) {
        ImageDecoder.HQ_MAX_PIXELS
    } else {
        ImageDecoder.MAX_PIXELS
    }

    // 统一的取页入口：调用方只管「第几页」，不管它从目录来还是从 zip 里来
    val loader: PageLoader = { i, w ->
        if (localPages.isNotEmpty()) {
            val f = localPages.getOrNull(i)
            if (f != null) ReaderPageLoader.loadFile(f, w, maxPixels) else null
        } else if (session.cbzUrl != null) {
            val f = zipFile
            val name = zipNames.getOrNull(i)
            if (f != null && name != null) CbzStore.loadPage(f, name, w, maxPixels) else null
        } else {
            val e = pages.getOrNull(i)
            // v98：Komga 的页是**绝对地址**（带 api/v1 前缀、按 bookId 走），
            // 不能往 WebDAV 根路径上拼；WebDAV 的仍然是相对路径。
            val u = e?.let {
                if (it.path.startsWith("http://") || it.path.startsWith("https://")) it.path
                else WebDavClient.fileUrl(settings.webdavUrl, it.path)
            }
            if (u != null) ReaderPageLoader.load(u, auth, w, maxPixels) else null
        }
    }

    /**
     * 起始页。
     *
     * **不能**在 remember 里直接 coerceIn(0, total-1)：CBZ 的 total 要等包下完才有，
     * 那一刻还是 0，会把"上次读到第 2 页"钳成第 1 页（实测踩过）。
     * 所以先记 0，等 total 到位再定位一次。
     */
    var index by remember(session.key) { mutableIntStateOf(0) }
    var seeked by remember(session.key) { mutableStateOf(false) }
    val last = (total - 1).coerceAtLeast(0)
    LaunchedEffect(total) {
        if (!seeked && total > 0) {
            index = session.startIndex.coerceIn(0, total - 1)
            seeked = true
        }
    }
    var chrome by remember { mutableStateOf(true) }
    /**
     * v96：章节列表是否打开。
     *
     * 独立于 [chrome]：顶栏点中间会整条收起，但列表是用户主动打开的浮层，
     * 不该跟着一起消失（也不能让点画面的手势把它关掉）。
     * `remember(session.key)` 让换话时自动归位。
     */
    var chapterSheet by remember(session.key) { mutableStateOf(false) }
    /**
     * 阅读模式。**初始值必须看设置**：v25 之前这里写死 `ReaderMode.PAGED`，
     * 于是设置里把「翻页方向」改成「上下滚动」完全没效果 —— 老大当场发现的那个 bug。
     *
     * 优先级：这本书上次手动切的模式 > 全局翻页方向。新加的书没有记录，
     * 自然就跟着全局走（设成上下滚动 = 新书默认条漫）。
     */
    val modeStore = remember { ReadModeStore(ctx) }
    /**
     * 记录挂在「这一套章节队列」上，而不是单话：一部漫画几十上百话，
     * 每话各存一条的话，切到下一话就丢掉刚选的模式了。
     * 有队列就用队列第一话的 key，没有（图片文件夹 / 单本 cbz）就用自己。
     */
    val modeKey = remember(session.key, session.chapters) {
        session.chapters.firstOrNull()?.key ?: session.key
    }
    var mode by remember {
        mutableStateOf(
            modeStore.get(modeKey) ?: if (settings.pageDirKind == PageDirKind.VERTICAL) {
                ReaderMode.WEBTOON
            } else {
                ReaderMode.PAGED
            }
        )
    }
    /** 用户手动切模式才落盘；只是跟全局默认走不写记录，免得以后改全局被旧记录挡住。 */
    fun switchMode() {
        val next = if (mode == ReaderMode.PAGED) ReaderMode.WEBTOON else ReaderMode.PAGED
        mode = next
        modeStore.set(modeKey, next)
    }
    /** 分页模式翻过最后一页就出结尾卡；条漫的结尾卡是列表最后一项 */
    var atEnd by remember(session.key) { mutableStateOf(false) }
    var prewarmed by remember(session.key) { mutableStateOf(false) }

    // 条漫一进来就把浮动条收起来，别让它半透明压在画面上；
    // 想用的时候点一下画面，再滚又自动收起。切回分页恢复常驻（原行为）。
    LaunchedEffect(mode) {
        chrome = mode == ReaderMode.PAGED
    }

    val rtl = settings.pageDirKind == PageDirKind.RTL

    // 进度：停下 500ms 才写盘，扫读时不会每页都写一遍
    LaunchedEffect(index, mode, total) {
        if (total <= 0) return@LaunchedEffect
        delay(500)
        onProgress(index, total)
    }
    // 退出时立刻落盘，别把最后一页丢了
    DisposableEffect(Unit) {
        onDispose { if (total > 0) onProgress(index, total) }
    }

    // 快读完时预热下一话：中央目录 + 第一页先落缓存，点「下一话」就不用等网络
    LaunchedEffect(index, total, session.next?.cbzUrl) {
        val url = session.next?.cbzUrl ?: return@LaunchedEffect
        if (prewarmed || total <= 0 || index < total - 3) return@LaunchedEffect
        prewarmed = true
        CbzStore.prewarm(ctx, url, auth)
    }

    // v75：阅读画面自己的玻璃源。不能复用主界面的 appGlassSource ——
    // 三页那层即使被阅读器盖住也仍在录制，两个 glassSource 写同一个 layer
    // 会让底栏 / 顶栏采样互相覆盖。
    val chromeGlass = rememberGlassSource()

    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // 宽屏且开了双页才并排；条漫永远单列
        val dual = mode == ReaderMode.PAGED && settings.dualPage && wPx > hPx
        val step = if (dual) 2 else 1
        // 画质优先：目标宽翻倍。inSampleSize 只能 2 的幂，所以这等价于「少降一档」，
        // 超分图的细线和网点能保住；代价是单页位图内存约 ×4。
        val qualityBoost = if (settings.highQuality) 2f else 1f
        val targetW = ((if (dual) wPx / 2f else wPx) * qualityBoost).toInt()

        // v75：阅读内容录进独立玻璃源，给顶栏信息条做 backdrop。
        // chrome 收起时不挂（条漫一滚就收），翻页 / 滚动路径零额外录制。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (chrome) Modifier.glassSource(chromeGlass) else Modifier)
        ) {
            when {
                preparing -> {
                    Text(
                        "正在准备「${session.title}」…",
                        color = dim,
                        style = MangaType.bodyLarge,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                prepError != null -> {
                    Text(
                        prepError ?: "",
                        color = dim,
                        style = MangaType.bodyLarge,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                atEnd && mode == ReaderMode.PAGED -> EndCard(
                    title = session.title,
                    total = total,
                    next = session.next,
                    ink = ink,
                    compact = false,
                    onNext = onNextChapter,
                    onExit = onExit,
                    onBack = { atEnd = false },
                    modifier = Modifier.fillMaxSize()
                )

                mode == ReaderMode.WEBTOON -> {
                    WebtoonReader(
                        total = total,
                        loader = loader,
                        targetW = wPx.toInt(),
                        startIndex = index,
                        ink = dim,
                        cardInk = ink,
                        title = session.title,
                        next = session.next,
                        onNext = onNextChapter,
                        onExit = onExit,
                        // 结尾卡是列表最后一项，别把它的 index 当成页号写进进度
                        onVisible = { i -> if (i in 0 until total && i != index) index = i },
                        // 条漫工具栏不常驻：一滚就收起，点一下画面才唤出
                        onScrollHide = { chrome = false },
                        onChromeTap = { chrome = !chrome },
                        scope = session.key
                    )
                }

                else -> {
                    // 预取前后几页（往回翻也要有），并且把解码结果放进页位图缓存：
                    // 动画里那一页是全新组合的，没有这份缓存就会先闪一帧「载入中」。
                    // 画质优先时单页位图可能几十 MB（3200×5080 ≈ 62MB），只预取**下一页**，
                    // 别的方向翻过去现解。宁可翻回去等 200ms，也别把堆顶到 OOM。
                    val prefetchRange = if (settings.highQuality) 1..1 else -1..2
                    LaunchedEffect(index, step, targetW, total, session.key, prefetchRange) {
                        for (k in prefetchRange) {
                            val i = index + k * step
                            if (i in 0 until total && i != index) {
                                val ck = PageBitmapCache.key(session.key, i, targetW)
                                // 已经在内存里的就别再解一遍 —— 之前这里无条件调 loader，
                                // 同一页会被解 2~3 次，白烧 CPU 还拖慢翻页。
                                if (PageBitmapCache.get(ck) != null) continue
                                val d = loader(i, targetW) ?: continue
                                // put 内部会拒收 OOM 降档图，这里不用再判断
                                PageBitmapCache.put(ck, d)
                            }
                        }
                    }

                    // key() 让换话时整套翻页状态（手指偏移 / 卷页进度）重新初始化，
                    // 不会把上一话的动画残留带进新会话。
                    key(session.key) {
                        PagedView(
                            index = index,
                            total = total,
                            step = step,
                            dual = dual,
                            rtl = rtl,
                            widthPx = wPx,
                            targetW = targetW,
                            loader = loader,
                            ink = dim,
                            paper = bg,
                            anim = settings.pageAnimKind,
                            scope = session.key,
                            onCommit = { n -> index = n; atEnd = false },
                            onAtEnd = { atEnd = true },
                            onChromeToggle = { chrome = !chrome },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // ---- 浮动条 ----
        // v77：上边距必须是**常量**。原来读 WindowInsets.statusBars，而进阅读器时
        // MainActivity 会 hide(statusBars())，系统那段隐藏动画里 insets 从 ~24dp
        // 渐变到 0，toolbarTop 跟着缩，信息条就在打开的一瞬间从下往上爬一段
        // （蓝溪镇录屏 f28~f34 连跳 7 帧，老大一眼看出来了）。
        // 固定 20dp = 51px @408dpi，让出约一个状态栏的高度，不贴屏幕上沿，
        // 挖孔屏也够不上。v77 的 12dp 老大仍嫌离顶太近，v78 调到 20dp。
        val toolbarTop = 20.dp
        val toolbarH = 38.dp
        if (chrome) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 工具栏自己吃掉点击，别让点标题栏穿透到下面的画面又把它收起来
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            ) {
                // v81：顶栏也换成 PrismGlassBar —— AGSL 真折射 + rim 高光 + 雾面，
                // 不再只是「模糊 + 平涂」。tint 保留原来 55% 黑：漫画画面又亮又杂，
                // 白字必须压在够厚的底上，所以这里只换材质、不动可读性。
                PrismGlassBar(
                    source = chromeGlass,
                    style = LiquidGlassStyle.pill(dark = true)
                        .copy(tint = Color(0x8C000000)),
                    shape = RoundedCornerShape(percent = 50),
                    haze = 0.72f,
                    hazeBlur = 15.6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = toolbarTop, start = 10.dp, end = 10.dp)
                        .height(toolbarH),
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .fillMaxHeight()
                            .pointerInput(Unit) { detectTapGestures { onExit() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        session.title,
                        Modifier.weight(1f),
                        style = MangaType.bodyMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (total > 0) {
                        Text(
                            "${index + 1} / $total",
                            style = MangaType.bodySmall.copy(color = Color.White.copy(alpha = 0.8f))
                        )
                    }
                    /*
                     * v96：章节列表按钮。
                     *
                     * 只有一话时没有意义，直接不出现（单本 cbz / 图片文件夹进来看不到它）。
                     * 尺寸 / 底色 / pill 圆角全抄旁边那颗模式按钮，两个并排放着才像一套。
                     */
                    if (session.chapters.size > 1) {
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .height(26.dp)
                                .width(34.dp)
                                .background(
                                    Color.White.copy(alpha = 0.16f),
                                    RoundedCornerShape(percent = 50)
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures { chapterSheet = true }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            ChapterGlyph(Color.White)
                        }
                    }
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .height(26.dp)
                            .width(52.dp)
                            // v77：里面这颗模式按钮也统一成 pill，别留 9dp 方角
                            .background(
                                Color.White.copy(alpha = 0.16f),
                                RoundedCornerShape(percent = 50)
                            )
                            .pointerInput(mode) {
                                detectTapGestures { switchMode() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode.label,
                            style = MangaType.labelMedium.copy(color = Color.White)
                        )
                    }
                    }   // Row
                }       // GlassSurface content
            }           // Column
        }               // if (chrome)

        // ---- 底部进度 ----
        if (total > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ink.copy(alpha = 0.10f))
                    .align(Alignment.BottomCenter)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((index + 1).toFloat() / total)
                        .fillMaxHeight()
                        .background(settings.accentColor.copy(alpha = 0.7f))
                )
            }
        }

        // ---- 章节列表（压在所有东西上面，包括顶栏和进度条）----
        if (chapterSheet) {
            ChapterSheet(
                chapters = session.chapters,
                currentKey = session.key,
                onDismiss = { chapterSheet = false },
                onPick = { ch ->
                    chapterSheet = false
                    // 点自己就只是关掉列表，不重建会话（重建会把这一话的页码冲回第一页）
                    if (ch.key != session.key) onNextChapter(ch)
                }
            )
        }
    }
}

/**
 * 条漫：整话连成一列，上下滚。
 *
 * 每页按解码出来的真实宽高比占位，没解码完给一个大概高度，
 * 这样滚动条不会因为图片陆续到货而乱跳。
 */
@Composable
private fun WebtoonReader(
    total: Int,
    loader: PageLoader,
    targetW: Int,
    startIndex: Int,
    ink: Color,
    cardInk: Color,
    title: String,
    next: NextChapter?,
    onNext: (NextChapter) -> Unit,
    onExit: () -> Unit,
    onVisible: (Int) -> Unit,
    onScrollHide: () -> Unit,
    onChromeTap: () -> Unit,
    scope: String
) {
    val listState = rememberLazyListState()
    // 进来先定位到上次读的那页。只定位一次，之后用户怎么滚都不干预。
    LaunchedEffect(Unit) {
        if (startIndex > 0) listState.scrollToItem(startIndex)
    }
    // 一滚（含 fling 惯性）就收起工具栏。isScrollInProgress 只在开始/结束翻转，
    // 不是每帧回调，所以这里对滑动性能没有影响。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { if (it) onScrollHide() }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { onVisible(it) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // 点按检测挂在同一节点上，不再盖一层透明点击层 —— 后者正是之前
            // 全 app 掉帧的元凶。子项里的按钮会先消费 down，所以点「下一话」
            // 或「点此重试」不会误触发工具栏唤出。
            .pointerInput(Unit) { detectTapGestures(onTap = { onChromeTap() }) }
    ) {
        itemsIndexed(List(total) { it }) { _, i ->
            WebtoonPage(index = i, loader = loader, targetW = targetW, ink = ink, scope = scope)
        }
        item(key = "end-card") {
            EndCard(
                title = title,
                total = total,
                next = next,
                ink = cardInk,
                compact = true,
                onNext = onNext,
                onExit = onExit,
                onBack = {},
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
        }
    }
}

@Composable
private fun WebtoonPage(
    index: Int,
    loader: PageLoader,
    targetW: Int,
    ink: Color,
    scope: String
) {
    var retry by remember { mutableIntStateOf(0) }
    val bmpKey = PageBitmapCache.key(scope, index, targetW)
    val cached = remember(bmpKey) { PageBitmapCache.get(bmpKey) }
    val state by produceState<PageState>(
        initialValue = if (cached != null) PageState.Ok(cached) else PageState.Loading,
        bmpKey, retry
    ) {
        if (retry == 0 && cached != null) return@produceState
        value = PageState.Loading
        val d = loader(index, targetW)
        if (d != null) {
            // 降档图（OOM 重试）这里会被拒收，下次进这一页再重新解全分辨率
            PageBitmapCache.put(bmpKey, d)
            value = PageState.Ok(d)
        } else {
            value = PageState.Fail
        }
    }

    when (val s = state) {
        is PageState.Ok -> Image(
            // Medium = mipmap + 双线性，缩小采样时比默认 Low 更锐（细线/网点不容易糊）
            painter = remember(s.d) {
                BitmapPainter(s.d.bmp.asImageBitmap(), filterQuality = FilterQuality.Medium)
            },
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    max(0.05f, s.d.bmp.width.toFloat() / s.d.bmp.height.toFloat())
                ),
            contentScale = ContentScale.FillWidth
        )

        PageState.Loading -> Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f),
            contentAlignment = Alignment.Center
        ) { Text("第 ${index + 1} 页 · 载入中…", color = ink, style = MangaType.bodyMedium) }

        PageState.Fail -> Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clickable { retry++ },
            contentAlignment = Alignment.Center
        ) { Text("第 ${index + 1} 页打不开 · 点此重试", color = ink, style = MangaType.bodyMedium) }
    }
}

internal sealed interface PageState {
    data object Loading : PageState

    /** 带上解码档位，UI 层不用知道，但保持「全分辨率 / 降档」这条信息不丢失。 */
    data class Ok(val d: ImageDecoder.Decoded) : PageState
    data object Fail : PageState
}

@Composable
internal fun PageImage(
    index: Int,
    targetW: Int,
    loader: PageLoader,
    ink: Color,
    modifier: Modifier = Modifier,
    scope: String = ""
) {
    var retry by remember { mutableIntStateOf(0) }
    val bmpKey = PageBitmapCache.key(scope, index, targetW)
    /*
     * 这里**不能用 produceState**。
     *
     * produceState 内部是 `remember { mutableStateOf(initialValue) }` + `LaunchedEffect(keys)`：
     * keys 变了只是重启 producer，**那个 mutableStateOf 还是原来那个，value 保留着上一页的
     * Ok 位图**，initialValue 只有首次创建才生效。于是「换页后的第一帧」画的一定是旧页，
     * 等 producer 跑完才换成新页 —— 跟手翻页落脚的那一两帧会闪一下上一页
     * （v36 老大报的「翻到下一页有一瞬间几帧闪现上一页」就是它，截不到图因为只有 1~2 帧）。
     *
     * 改成 `remember(bmpKey) { mutableStateOf(...) }`：key 一变就换一个全新的状态对象，
     * 初值在**组合期同步**算出来 —— 命中内存位图第一帧就是对的页，没命中就是「载入中」，
     * 无论如何都不会再出现「返祖」的旧画面。翻转时下一页刚被邻居节点解过，几乎必中缓存，
     * 所以实际看到的就是无缝接上。
     */
    val state = remember(bmpKey, retry) {
        mutableStateOf<PageState>(
            PageBitmapCache.get(bmpKey)?.let { PageState.Ok(it) } ?: PageState.Loading
        )
    }
    LaunchedEffect(bmpKey, retry) {
        // 组合期已经命中内存位图：不重跑 loader、也不闪 Loading
        if (state.value is PageState.Ok) return@LaunchedEffect
        state.value = PageState.Loading
        val d = loader(index, targetW)
        if (d != null) {
            PageBitmapCache.put(bmpKey, d)
            state.value = PageState.Ok(d)
        } else {
            state.value = PageState.Fail
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val s = state.value) {
            is PageState.Ok -> Image(
                painter = remember(s.d) {
                    BitmapPainter(
                        s.d.bmp.asImageBitmap(),
                        filterQuality = FilterQuality.Medium
                    )
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            PageState.Loading ->
                Text("第 ${index + 1} 页 · 载入中…", color = ink, style = MangaType.bodyMedium)

            PageState.Fail -> Text(
                "第 ${index + 1} 页打不开 · 点此重试",
                color = ink,
                style = MangaType.bodyMedium,
                modifier = Modifier.clickable { retry++ }
            )
        }
    }
}

/**
 * 章末结尾卡。
 * - 分页模式：翻过最后一页后铺满整屏（compact = false）
 * - 条漫模式：作为 LazyColumn 的最后一项跟着滚出来（compact = true）
 */
@Composable
private fun EndCard(
    title: String,
    total: Int,
    next: NextChapter?,
    ink: Color,
    compact: Boolean,
    onNext: (NextChapter) -> Unit,
    onExit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sub = ink.copy(alpha = 0.6f)
    Column(
        modifier.padding(horizontal = 24.dp, vertical = if (compact) 20.dp else 0.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("本话完", style = MangaType.headlineSmall, fontWeight = FontWeight.SemiBold, color = ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "$title · 共 $total 页",
            style = MangaType.bodyMedium,
            color = sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(20.dp))

        next?.let { n ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ink.copy(alpha = 0.12f))
                    .clickable { onNext(n) }
                    .padding(horizontal = 20.dp, vertical = 11.dp)
            ) {
                Text(
                    "下一话：${n.title}",
                    style = MangaType.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!compact) {
                Text(
                    "回到最后一页",
                    style = MangaType.bodyMedium,
                    color = sub,
                    modifier = Modifier.clickable { onBack() }
                )
            }
            Text(
                "返回",
                style = MangaType.bodyMedium,
                color = sub,
                modifier = Modifier.clickable { onExit() }
            )
        }
    }
}

/**
 * v96：顶栏那颗章节按钮的图标 —— 三条横线。
 *
 * 和旁边的「分页 / 条漫」按钮同一套 26dp 高 pill，只是把文字换成图形：
 * 顶栏里已经有标题、页码、模式三段文字，再加一段汉字就挤了；
 * 而「列表 = 章节」这个符号读者不用学。
 */
@Composable
private fun ChapterGlyph(color: Color) {
    Canvas(Modifier.width(14.dp).height(10.dp)) {
        val stroke = 1.5.dp.toPx()
        val gap = (size.height - stroke) / 2f
        for (i in 0..2) {
            val y = stroke / 2f + gap * i
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * v96：阅读页的章节列表。
 *
 * 数据直接用 [ReaderSession.chapters] —— 详情页和文件页进来时都已经排好序、
 * 也已经算好每一话的 key / cbzUrl，所以这里不拉目录、不发请求，
 * 点一项就走 [onNextChapter]，和结尾卡上「下一话」完全同一条链路：
 * 进度记录、`next` 链、`key(session.key)` 的重置行为全部一致。
 *
 * 只覆盖浮层需要的那部分：scrim 吃掉点击（别穿透到画面把顶栏收起来），
 * 卡片贴在顶栏正下方，最高占屏幕 62%，剩下的交给 LazyColumn 滚。
 */
@Composable
private fun ChapterSheet(
    chapters: List<NextChapter>,
    currentKey: String,
    onDismiss: () -> Unit,
    onPick: (NextChapter) -> Unit,
) {
    val c = MangaTheme.colors
    val shape = remember { RoundedCornerShape(26.dp) }
    val pill = remember { RoundedCornerShape(percent = 50) }
    val listState = rememberLazyListState()
    val current = remember(chapters, currentKey) {
        chapters.indexOfFirst { it.key == currentKey }
    }
    // 打开时先滚到当前话前面两行，别让用户从第一话往下翻着找
    LaunchedEffect(chapters, currentKey) {
        if (current > 1) listState.scrollToItem(current - 2)
    }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                // 74dp = 顶栏 20dp 上边距 + 38dp 高 + 16dp 缝，和顶栏左右各留 10dp 对齐
                .padding(top = 74.dp, start = 10.dp, end = 10.dp)
                .fillMaxWidth()
                .heightIn(max = maxH)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(c.surface)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "章节",
                    style = MangaType.titleSmall.copy(
                        color = c.ink,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "共 ${chapters.size} 话",
                    style = MangaType.bodySmall.copy(color = c.sub)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .height(30.dp)
                        .width(56.dp)
                        .clip(pill)
                        .background(c.chip)
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("关闭", style = MangaType.labelMedium.copy(color = c.ink))
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
            LazyColumn(
                state = listState,
                // 底部留一点，最后一行不要贴着卡片圆角
                contentPadding = PaddingValues(bottom = 8.dp),
                // fill = false：只有两三话时卡片就跟着缩，不撑成一大块空白
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            ) {
                itemsIndexed(chapters, key = { _, ch -> ch.key }) { _, ch ->
                    val active = ch.key == currentKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (active) c.accentSoft else Color.Transparent)
                            .clickable { onPick(ch) }
                            .padding(horizontal = 20.dp)
                            .height(50.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ch.title,
                            Modifier.weight(1f),
                            style = MangaType.bodyMedium.copy(
                                color = if (active) c.accent else c.ink,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (active) {
                            Text(
                                "✓",
                                Modifier.padding(start = 10.dp),
                                style = MangaType.bodyMedium.copy(color = c.accent)
                            )
                        }
                    }
                }
            }
        }
    }
}
