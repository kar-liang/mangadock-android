package com.mangadock.android

import com.mangadock.android.ui.prism.BackChevron
import com.mangadock.android.ui.prism.LockGlyph
import com.mangadock.android.ui.prism.PrismCircleButton
import com.mangadock.android.ui.prism.PrismDialog
import com.mangadock.android.ui.prism.PrismDialogAction
import com.mangadock.android.ui.prism.PrismElasticPress
import com.mangadock.android.ui.prism.PrismPageTransition
import com.mangadock.android.ui.prism.PrismPopupCard
import com.mangadock.android.ui.prism.PrismPopupItem

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangadock.android.bookshelf.BookGroup
import com.mangadock.android.bookshelf.BookItem
import com.mangadock.android.bookshelf.BookSort
import com.mangadock.android.bookshelf.BooksRow
import com.mangadock.android.bookshelf.BookshelfStore
import com.mangadock.android.bookshelf.CoverStore
import com.mangadock.android.bookshelf.PrivacyStore
import com.mangadock.android.bookshelf.arrangeBooks
import com.mangadock.android.bangumi.BangumiClient
import com.mangadock.android.cache.CacheStore
import com.mangadock.android.glass.DockMetrics
import com.mangadock.android.glass.DockTab
import com.mangadock.android.download.DownloadStore
import com.mangadock.android.pages.humanSize
import androidx.compose.runtime.collectAsState
import com.mangadock.android.glass.GlassDock
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.glass.GlassSurface
import com.mangadock.android.glass.glassSource
import com.mangadock.android.glass.rememberGlassSource
import com.mangadock.android.pages.FilesRow
import com.mangadock.android.pages.FilesState
import com.mangadock.android.pages.isImageFile
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.reader.ProgressStore
import com.mangadock.android.reader.NextChapter
import com.mangadock.android.reader.ReaderScreen
import com.mangadock.android.reader.ReaderSession
import com.mangadock.android.reader.toSession
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.settings.SKey
import com.mangadock.android.settings.SKeyKind
import com.mangadock.android.settings.ServerAccount
import com.mangadock.android.settings.ServerKind
import com.mangadock.android.settings.SettingsPageId
import com.mangadock.android.settings.SettingsStore
import com.mangadock.android.settings.currentIndex
import com.mangadock.android.settings.flip
import com.mangadock.android.settings.kind
import com.mangadock.android.settings.options
import com.mangadock.android.settings.select
import com.mangadock.android.settings.text
import com.mangadock.android.settings.withAccounts
import com.mangadock.android.settings.withText
import com.mangadock.android.komga.KomgaClient
import com.mangadock.android.ui.BookDetailScreen
import com.mangadock.android.ui.AddKindDialog
import com.mangadock.android.ui.KomgaBrowserPage
import com.mangadock.android.ui.LibraryPage
import com.mangadock.android.ui.LongPressMenu
import com.mangadock.android.ui.MenuEntry
import com.mangadock.android.ui.PillToast
import com.mangadock.android.ui.MangaTheme
import com.mangadock.android.ui.MangaType
import com.mangadock.android.ui.MangaMotion
import com.mangadock.android.ui.BooksPage
import com.mangadock.android.ui.FilesPage
import com.mangadock.android.ui.PrivacyShelfScreen
import com.mangadock.android.ui.ServerFormOverlay
import com.mangadock.android.ui.ServerFormState
import com.mangadock.android.ui.SettingsPage
import com.mangadock.android.webdav.DavEntry
import com.mangadock.android.webdav.DavResult
import com.mangadock.android.webdav.DirCache
import com.mangadock.android.webdav.WebDavClient
import com.mangadock.android.sync.SyncStore
import com.mangadock.android.update.Updater
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 漫画根路径统一成「以 / 开头、不带尾斜杠」；根就是 "/"。不带尾斜杠很重要，
 *  filesRows() 的「返回上一级」是按最后一个 '/' 切的，带尾斜杠会指回自己。 */
private fun normalizeRoot(raw: String): String {
    val r = raw.trim().trim('/')
    return if (r.isEmpty()) "/" else "/$r"
}

/**
 * 详情页返回栈里的一层。
 *
 * [queue] 是从父目录带下来的兄弟话队列 —— 「一章一个文件夹」的库靠它把
 * 「下一话」串起来：子目录本身没有可枚举的兄弟，只有把它从父级带进来才行。
 */
private data class DetailEntry(
    val book: BookItem,
    val queue: List<NextChapter> = emptyList()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    var settings by remember { mutableStateOf(store.load()) }
    // 任何字段一变就落盘。设置项很少，直接全量写也不心疼。
    LaunchedEffect(settings) { store.save(settings) }

    // v21：所有页面（含对话框、详情页、底栏）统一从这里拿语义色。
    MangaTheme(settings) {
        // 深色时状态栏图标要反过来，否则黑底黑字看不见
        val colors = MangaTheme.colors
        val view = LocalView.current
        SideEffect {
            val win = (view.context as? Activity)?.window ?: return@SideEffect
            val ctl = WindowCompat.getInsetsController(win, view)
            ctl.isAppearanceLightStatusBars = !colors.dark
            ctl.isAppearanceLightNavigationBars = !colors.dark
        }
        Root(settings) { settings = it }
    }
}

@Composable
private fun Root(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    /** 每本书读到哪了 */
    val appCtx = LocalContext.current
    val progressStore = remember { ProgressStore(appCtx) }
    /** 同步落地后要重新读一遍设置（v30 起设置也在同步范围里） */
    val settingsStore = remember { SettingsStore(appCtx) }
    /** 目录列表磁盘缓存：冷启动 / 断网时先上屏再刷新 */
    val dirCache = remember { DirCache(appCtx) }
    /** 书架是本地收藏，跟 WebDAV 目录浏览分开（iOS 1.3.5） */
    val bookshelf = remember { BookshelfStore(appCtx) }
    /** v26：隐私书架（独立书单 + 设备凭证解锁），对齐 iOS `PrivacyStore` */
    val privacy = remember { PrivacyStore(appCtx) }
    var books by remember { mutableStateOf(bookshelf.all()) }
    /** 进度变化时推一下，让书架卡片上的「第 N 页」跟着刷新 */
    var booksTick by remember { mutableIntStateOf(0) }
    /** 拉取云端时会把 booksTick 抬起来，别让「改动就推送」把刚拿下来的数据又推回去 */
    var syncApplying by remember { mutableStateOf(false) }
    /** 刚从云端落地的那份设置：下一轮别反手再推回去（见下面的 LaunchedEffect(settings)） */
    var pulledSettings by remember { mutableStateOf<AppSettings?>(null) }
    var lastReaderKey by remember { mutableStateOf<String?>(null) }
    var bookToRemove by remember { mutableStateOf<BookItem?>(null) }
    /** v25：书架长按菜单（自定义封面 / 恢复默认 / 移出书架） */
    var bookMenu by remember { mutableStateOf<BookItem?>(null) }
    /** v37：菜单贴在「被长按的那一格」下面，所以要记住那一格的位置 */
    var bookMenuAt by remember { mutableStateOf(Rect.Zero) }
    var rowMenuAt by remember { mutableStateOf(Rect.Zero) }
    var privacyMenuAt by remember { mutableStateOf(Rect.Zero) }
    /** 正在等用户选封面图的那本书 */
    var coverTarget by remember { mutableStateOf<BookItem?>(null) }
    /** 文件页长按文件夹弹的菜单。单个 cbz 长按不再直接加入书架（v19）。 */
    var rowMenu by remember { mutableStateOf<DavEntry?>(null) }
    /** 详情页返回栈：支持从子目录一层层进去再一层层退出来。 */
    var detailStack by remember { mutableStateOf<List<DetailEntry>>(emptyList()) }
    /** 隐私书架：是否已在本会话里解锁（和 iOS 一样只在内存里，杀进程就重新锁） */
    var privacyUnlocked by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    var privacyBooks by remember { mutableStateOf(privacy.all()) }
    var privacyMenu by remember { mutableStateOf<BookItem?>(null) }

    // v26：首屏就是书架。以前默认落在设置页，每次冷启动都要先点一下底栏，
    // 老大提的「首次进入应该展示书架」。
    var tab by remember { mutableStateOf(DockTab.BOOKS) }
    /**
     * v71：删掉了 v17 起的「200ms 内只认第一次切换」节流。
     *
     * 它当初是为了防「连点让同一个 FilesPage 出现两份、抢同一个 LazyListState」，
     * 但那个前提早就不成立了：三页的滚动状态全都提到了这一层（booksGrid / filesList /
     * settingsList），页面内部也没有 LaunchedEffect 之类的副作用，重复组合是幂等的。
     *
     * 代价是实实在在的：连点底栏时第二次点击被**静默吞掉**，用户看到的是
     * 「点了没反应 / 像卡住」，比动画本身更像卡顿。Compose 1.7 的 AnimatedContent
     * 对「动画中途改回已存在的 target」是按复用处理的（不会叠出第三份内容）。
     */
    /** 正在编辑哪一行（弹对话框用） */
    var dialogKey by remember { mutableStateOf<SKey?>(null) }
    /** v68：选择类设置项走锚定弹出菜单，记下被点那一行的 root 坐标 Rect。 */
    var pickerAnchor by remember { mutableStateOf(Rect.Zero) }
    /** v26：设置现在是二级菜单，这是当前停在哪一页 */
    var settingsPage by remember { mutableStateOf(SettingsPageId.ROOT) }
    /** v26：真实测一次 WebDAV，结果只放内存（不落盘，换网络就重测） */
    var davTest by remember { mutableStateOf<String?>(null) }
    var davTesting by remember { mutableStateOf(false) }
    /** 通用提示 */
    var notice by remember { mutableStateOf<String?>(null) }
    /**
     * 轻提示：底栏上方浮一个小胶囊，1.6s 自己消失，不打断操作。
     * 只有「需要用户读完再确认」的内容（多行错误、同步结果）才走 [notice] 弹窗 ——
     * 刷个封面这种一秒就过去的反馈弹 AlertDialog 太重了。
     */
    var toast by remember { mutableStateOf<String?>(null) }
    // v25：书架「自定义封面」选图。系统照片选择器（minSdk 33 原生支持），
    // 不需要 READ_MEDIA_IMAGES 权限，也不会把整个相册暴露给应用。
    val uiScope = rememberCoroutineScope()
    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = coverTarget
        coverTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            val ok = CoverStore.setCustom(appCtx, target, uri)
            booksTick++
            notice = if (ok) {
                "「${target.title}」的封面已更新。"
            } else {
                "这张图读不出来，换一张试试。"
            }
        }
    }
    /**
     * v30 书架下拉刷新。
     *
     * 为什么要专门来一下：Bangumi 补匹配的失败冷却只在**内存**里（`lastFailure`），
     * 断网或者接口抽风一次就冷却 5 分钟。这段时间里书架怎么刷都补不上封面，
     * 而杀进程重进刚好把这份记忆清零 —— 于是表现成「封面必须重启 app 才出来」。
     * 用户主动下拉就是明确要求重来，所以这里把三件事一起做：清封面失败标记、
     * 清 Bangumi 冷却、抬 tick 让每张卡片重新取图。
     */
    var shelfRefreshing by remember { mutableStateOf(false) }
    /**
     * 只有**下拉手势**才让 M3 那个转圈指示器出现。
     *
     * 它是 PullToRefreshBox 的指示器，浮在列表上方、正好压在第一张卡片中间，
     * 长得就是一个灰圆 + 一段弧。点顶栏 ↻ 时它也跟着转 550ms，屏幕上同时出现
     * 一个灰圈和一条 toast，像是两个不相干的控件（v35 实测截图里看着像 bug）。
     * 点按类操作的反馈交给 toast 就够了 —— 下拉才需要「手拉着、它转着」的呼应。
     */
    var pullSpinning by remember { mutableStateOf(false) }
    var coverTick by remember { mutableIntStateOf(0) }
    fun refreshShelf(fromPull: Boolean = false) {
        if (shelfRefreshing) return
        shelfRefreshing = true
        if (fromPull) pullSpinning = true
        uiScope.launch {
            CoverStore.clearMemory()
            CoverStore.clearMisses(appCtx)
            BangumiClient.resetCooldown()
            books = bookshelf.all()
            booksTick++
            coverTick++
            toast = "封面已重新获取"
            // 下拉时让指示器至少转一下，否则本地命中太快，看起来像没反应
            if (fromPull) kotlinx.coroutines.delay(550)
            pullSpinning = false
            shelfRefreshing = false
        }
    }

    /**
     * v26 隐私空间解锁。走系统凭证（KeyguardManager），不引额外依赖，
     * 也不自绘密码框 —— 那个等于给暴力试错留一个入口。
     */
    val keyguard = remember {
        appCtx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    /** 设备有没有设锁屏密码 / 图案；没有就加不了锁，界面上如实说明 */
    val deviceLocked = remember { keyguard?.isDeviceSecure == true }
    val unlockPrivacy = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            privacyUnlocked = true
            privacyBooks = privacy.all()
            /*
             * v96：解锁成功就**直接把隐私书架打开**。
             *
             * 以前这里只置 privacyUnlocked，页面不开 —— 老大报的就是这个：
             * 「指纹解锁之后没办法自动打开隐私书架，还得再点一次」。
             * 因为 openPrivacy() 把「解锁」和「进页面」拆成了两步，
             * 而真正决定要不要进的是这个异步回调，它当时什么都没做，
             * 用户只好再点一次入口（第二次走 privacyUnlocked 分支才进得去）。
             *
             * 隐私页内部那个「解锁」按钮也走这条回调（onUnlock -> requestPrivacyUnlock），
             * 但那时 privacyOpen 早就是 true 了，重复置 true 没有副作用，
             * 也不需要为两处调用点再分一个参数。
             */
            privacyOpen = true
        } else {
            notice = "没有解锁，隐私空间保持锁定。"
        }
    }
    /** 弹一次系统凭证。设备没设锁屏密码 / 厂商不支持时才走到「拿不到 intent」。 */
    fun requestPrivacyUnlock() {
        val i = keyguard?.createConfirmDeviceCredentialIntent(
            "隐私空间", "解锁后查看隐藏的漫画"
        )
        if (i == null) {
            notice = "这台设备的锁屏凭证不可用，暂时打不开隐私空间。"
            return
        }
        unlockPrivacy.launch(i)
    }

    /** 打开隐私空间：已解锁直接进，没解锁先过一次系统凭证。 */
    fun openPrivacy() {
        if (privacyUnlocked) {
            privacyBooks = privacy.all()
            privacyOpen = true
            return
        }
        if (!deviceLocked) {
            // 没设锁屏密码就锁不住。这里不假装安全：直接放行，页面顶部挂提示条。
            privacyUnlocked = true
            privacyBooks = privacy.all()
            privacyOpen = true
            return
        }
        requestPrivacyUnlock()
    }

    /** 清空缓存的二次确认 */
    var clearCacheConfirm by remember { mutableStateOf(false) }
    /** 缓存大小是磁盘上数出来的，清完 / 读完新页后抬一下重新算 */
    var cacheTick by remember { mutableIntStateOf(0) }
    val cacheSize = remember(cacheTick, tab) { CacheStore.sizeText(appCtx) }

    // ---- v28 离线下载：大小摘要是数出来的，下载队列一动就重算 ----
    DownloadStore.init(appCtx)
    val dlVersion by DownloadStore.version.collectAsState()
    var syncTick by remember { mutableIntStateOf(0) }
    val syncStatus = remember(syncTick) { SyncStore.lastSyncText(appCtx) }
    val downloadSize = remember(dlVersion) {
        val n = DownloadStore.count(appCtx)
        if (n == 0) "未使用"
        else "$n 话 · ${humanSize(DownloadStore.totalBytes(appCtx))}"
    }
    /** 清空离线下载的二次确认 */
    var clearDownloadsConfirm by remember { mutableStateOf(false) }

    // ---- v83 应用内更新（和 Flutter 版同一套：WebDAV 清单 → 下载 → 系统安装器）----
    /** 设置行右侧那串状态：当前版本 / 检查中 / 可更新 x / 下载 45% */
    var updateStatus by remember { mutableStateOf("当前 ${Updater.current.text}") }
    var updateChecking by remember { mutableStateOf(false) }
    /** 非空 = 弹「发现新版本」对话框 */
    var updateFound by remember { mutableStateOf<Updater.Manifest?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    /** 下载完了但系统没开「安装未知应用」，弹一次引导 */
    var installPermNeeded by remember { mutableStateOf(false) }

    /**
     * 下载进度轮询。
     *
     * `Updater.lastProgress` 是 IO 线程每个数据块写一次的值；逐字节往 Compose state
     * 里写要不停切主线程（几十 MB 的包能切几千次），所以改成 120ms 读一次 ——
     * 肉眼看到的百分比照样是连续的，主线程一点都不忙。
     */
    LaunchedEffect(updateDownloading) {
        while (updateDownloading) {
            val p = Updater.lastProgress
            updateStatus = if (p < 0f) "下载中…" else "下载 ${(p * 100).toInt()}%"
            delay(120)
        }
    }

    // ---- WebDAV ----
    var davPath by remember { mutableStateOf(normalizeRoot(settings.webdavRoot)) }
    var dav by remember { mutableStateOf(FilesState()) }
    /** 加一就强制重连 */
    var reloadTick by remember { mutableIntStateOf(0) }
    /** 非空就是正在阅读 */
    var reader by remember { mutableStateOf<ReaderSession?>(null) }

    // ---- v98 资源库 ----
    /** 点开的是哪台服务器；null = 停在卡片墙 */
    var libOpen by remember { mutableStateOf<ServerAccount?>(null) }
    /** Komga 二级：点开的系列；null = 系列网格 */
    var komgaSeries by remember { mutableStateOf<KomgaClient.Series?>(null) }
    /** 非 null = 添加 / 编辑表单开着 */
    var libForm by remember { mutableStateOf<ServerFormState?>(null) }
    /** ＋ 号先问类型 */
    var addKindPick by remember { mutableStateOf(false) }
    /** 资源库下拉刷新 / 重连 */
    var libTick by remember { mutableIntStateOf(0) }
    /** dav 这份列表是在哪台账号下取的，换账号要重新拉 */
    var davAccount by remember { mutableStateOf<String?>(null) }

    // ---- v29 同步：启动拉一次、书架/进度变动推一次 ----
    LaunchedEffect(Unit) {
        if (settings.syncEnabled && settings.webdavConfigured) {
            val r = SyncStore.pull(appCtx, settings, bookshelf, progressStore)
            if (r.ok) {
                syncApplying = true
                privacyBooks = privacy.all()
                booksTick++
                syncApplying = false
                syncTick++
                // 云端那份设置（WebDAV 地址 / Bangumi 接口 / 各项开关）可能刚被写进来，
                // 不重新读一遍的话，界面和后面对网络的调用都还按旧配置走。
                val fresh = settingsStore.load()
                pulledSettings = fresh
                onChange(fresh)
                notice = r.message
            }
        }
    }

    LaunchedEffect(booksTick) {
        if (booksTick == 0 || !settings.syncEnabled || !settings.webdavConfigured) return@LaunchedEffect
        if (syncApplying) return@LaunchedEffect
        // 加个延迟：一次操作常常连着抬好几次 tick，攒一下再发
        kotlinx.coroutines.delay(1500)
        SyncStore.pushAuto(appCtx, settings, bookshelf, progressStore)
        syncTick++
    }

    // 退出阅读器时进度刚落盘，这时候推一次最合适
    LaunchedEffect(reader) {
        val key = reader?.key
        if (key == null && lastReaderKey != null && settings.syncEnabled && settings.webdavConfigured) {
            SyncStore.pushAuto(appCtx, settings, bookshelf, progressStore)
            syncTick++
        }
        lastReaderKey = key
    }

    /**
     * v30：设置本身也推。
     *
     * 不加这个的话，「改了 Bangumi 接口 / WebDAV 地址」要等到下次书架变动才同步出去，
     * 换台设备看着像没生效。攒 2s 是因为设置面板常常连着改好几项。
     *
     * 防回环用的是**值比较**而不是一个"刚拉过"的布尔标记：云端拉下来的设置如果和本机
     * 一模一样（最常见的情况），`settings` 对象根本没变、这个 effect 压根不会重启，
     * 布尔标记就会一直挂着，把用户下一次真正的改动吞掉。比对具体值就不会有这个问题。
     */
    LaunchedEffect(settings) {
        if (!settings.syncEnabled || !settings.webdavConfigured) return@LaunchedEffect
        if (settings == pulledSettings) {
            pulledSettings = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(2000)
        SyncStore.pushAuto(appCtx, settings, bookshelf, progressStore)
        syncTick++
    }

    // 三个页面各自记滚动位置；切目录 / 改根路径时把文件页拉回顶部
    val settingsList = rememberLazyListState()
    val filesList = rememberLazyListState()
    val booksGrid = rememberLazyGridState()

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(settings.webdavRoot) {
        davPath = normalizeRoot(settings.webdavRoot)
        filesList.scrollToItem(0)
    }
    LaunchedEffect(davPath) { filesList.scrollToItem(0) }
    // 换了排序 / 分组规则，原来记住的滚动位置就对不上了（比如从「最近加入」切到「名称」，
    // 首屏可能直接落在中间）。回到顶部才看得见新的第一组。
    LaunchedEffect(settings.bookSort, settings.bookGroup) { booksGrid.scrollToItem(0) }
    // v26：进出设置子页都回到顶部，否则会停在上一页滚到的位置
    LaunchedEffect(settingsPage) { settingsList.scrollToItem(0) }

    /**
     * 文件页加载。
     *
     * v30 改了触发条件：以前 `tab` 也是 key，于是每次切到这个页面都重刷一遍 ——
     * 列表内容没变也会闪一下「刷新中」，和书架的静默行为对不上。现在只有三种情况会真的打网络：
     * 换目录、改服务器配置、用户主动刷新（下拉 / 顶栏刷新钮，都会抬 reloadTick）。
     * 只是切回这个 tab 就直接沿用屏幕上的列表。
     *
     * 失败态例外：`loadedOnce=false` 时会重试，别让一次超时把目录卡死在错误页上。
     */
    var davFetchedTick by remember { mutableIntStateOf(reloadTick) }
    LaunchedEffect(
        tab,
        reloadTick,
        davPath,
        libOpen?.id,
        settings.webdavUrl,
        settings.webdavUser,
        settings.webdavPass,
        settings.webdavConfigured
    ) {
        if (tab != DockTab.FILES) return@LaunchedEffect
        // v98：停在卡片墙 / Komga 页上时不拉 WebDAV 目录 —— 那是一次纯浪费的
        // PROPFIND。只有点开某台 WebDAV 才需要这份列表。
        if (libOpen == null || libOpen?.kind != ServerKind.WEBDAV) return@LaunchedEffect
        val forced = reloadTick != davFetchedTick
        val switched = davAccount != libOpen?.id
        if (!forced && !switched && dav.loadedOnce && dav.path == davPath) return@LaunchedEffect
        davFetchedTick = reloadTick
        davAccount = libOpen?.id
        if (!settings.webdavConfigured) {
            dav = FilesState(path = davPath)
            return@LaunchedEffect
        }

        // ① 本地缓存先上屏，绝不让用户干等一个网络超时（iOS 那边踩过两次的坑）
        val ck = dirCache.key(settings.webdavUrl, settings.webdavUser, davPath)
        val cached = dirCache.read(ck)
        if (cached != null) {
            dav = FilesState(
                loading = true, error = null, path = davPath,
                entries = cached,
                images = cached
                    .filter { !it.isDir && isImageFile(it.name) }
                    .sortedWith { a, b -> naturalOrder(a.name, b.name) },
                readIndex = progressStore.get(davPath)?.index ?: -1,
                loadedOnce = true
            )
        } else {
            // 没有本地缓存时不能沿用上一个目录的 entries/images —— 否则路径显示的是
            // B、列表却还是 A 的内容，尤其网络失败时看着特别像「串目录」。
            dav = FilesState(loading = true, error = null, path = davPath)
        }

        // ② 后台刷新：成功就覆盖缓存，失败就保留刚才那份（而不是清成空白）
        when (
            val r = WebDavClient.list(
                settings.webdavUrl, settings.webdavUser, settings.webdavPass, davPath
            )
        ) {
            is DavResult.Ok -> {
                dav = FilesState(
                    loading = false, error = null, path = davPath,
                    entries = r.entries,
                    images = r.entries
                        .filter { !it.isDir && isImageFile(it.name) }
                        .sortedWith { a, b -> naturalOrder(a.name, b.name) },
                    readIndex = progressStore.get(davPath)?.index ?: -1,
                    loadedOnce = true
                )
                dirCache.write(ck, r.entries)
            }

            is DavResult.Fail -> {
                dav = if (cached != null) {
                    dav.copy(loading = false, error = null, offline = true, path = davPath)
                } else {
                    FilesState(loading = false, error = r.message, path = davPath)
                }
            }
        }
    }

    // 「保持屏幕常亮」是唯一一个不用等阅读器就能立刻生效的开关
    val activity = LocalContext.current as? Activity
    LaunchedEffect(settings.keepScreenOn) {
        val win = activity?.window ?: return@LaunchedEffect
        if (settings.keepScreenOn) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 阅读漫画时把状态栏藏掉，让画面顶到最上；退出立刻还回来。
    // 用 WindowInsetsController 而不是 FLAG_FULLSCREEN：edge-to-edge 下前者不会让
    // 内容区高度抖动，三页的 statusBars padding 也不受影响。
    val view = LocalView.current
    val insetsController = remember(activity, view) {
        activity?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    LaunchedEffect(reader) {
        if (reader != null) {
            insetsController?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(Unit) {
        // Root 被销毁 / 切后台时兜底，别把状态栏永久留在隐藏态
        onDispose { insetsController?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    // ---- 返回栈 ----
    // 顺序：弹层 → 阅读器 → 文件页上一级目录 → 回书架 → 书架再按才交给系统。
    // v66：FlClash CommonDialog 镜像自带 BackHandler 播退出动画，这里再算进
    // enabled 只是兜底（最内层的那条优先）。
    val rootPath = normalizeRoot(settings.webdavRoot)
    val hasOverlay = dialogKey != null || notice != null ||
            bookToRemove != null || bookMenu != null || clearCacheConfirm ||
            rowMenu != null || privacyMenu != null ||
            updateFound != null || installPermNeeded ||
            libForm != null || addKindPick

    /**
     * v98：资源库退一层。
     *
     * 顺序是「Komga 单话列表 → 系列网格 → 卡片墙 / WebDAV 子目录 → 上一层 … →
     * 卡片墙」。抽成函数是因为顶栏返回钮和系统返回键要**完全同一套行为**，
     * 两处各写一遍必然有一处先跑偏。
     */
    fun closeLibLevel(): Boolean {
        val acc = libOpen ?: return false
        if (acc.kind == ServerKind.KOMGA) {
            if (komgaSeries != null) komgaSeries = null else libOpen = null
            return true
        }
        val root = normalizeRoot(acc.root)
        if (davPath != root) {
            if (!davPath.startsWith(root)) {
                // 从书架跳进来的外部目录：直接回卡片墙，别在别人的树里乱爬
                libOpen = null
            } else {
                val parent = davPath.substringBeforeLast('/', "").ifEmpty { "/" }
                davPath = if (parent.startsWith(root)) parent else root
            }
            return true
        }
        libOpen = null
        return true
    }

    val backEnabled = hasOverlay || reader != null || detailStack.isNotEmpty() ||
            privacyOpen || settingsPage != SettingsPageId.ROOT ||
            tab != DockTab.BOOKS ||
            (tab == DockTab.FILES && (libOpen != null || davPath != rootPath))

    BackHandler(enabled = backEnabled) {
        when {
            hasOverlay -> {
                dialogKey = null
                notice = null
                bookToRemove = null
                bookMenu = null
                clearCacheConfirm = false
                rowMenu = null
                privacyMenu = null
                updateFound = null
                installPermNeeded = false
                libForm = null
                addKindPick = false
            }

            reader != null -> reader = null

            // 详情页从阅读器退出来还留在原处，再退一层才回书架
            detailStack.isNotEmpty() -> detailStack = detailStack.dropLast(1)

            privacyOpen -> privacyOpen = false

            // 设置子页：退回分类首页，而不是直接跳去书架
            tab == DockTab.SETTINGS && settingsPage != SettingsPageId.ROOT ->
                settingsPage = SettingsPageId.ROOT

            // 资源库：Komga 列表 → 网格，WebDAV 子目录 → 上一层，最后回卡片墙
            tab == DockTab.FILES && libOpen != null -> { closeLibLevel() }

            // 文件页：在子目录就往上一级；从书架跳进来的外部目录直接回书架
            tab == DockTab.FILES && davPath != rootPath -> {
                if (!davPath.startsWith(rootPath)) {
                    tab = DockTab.BOOKS
                } else {
                    val parent = davPath.substringBeforeLast('/', "").ifEmpty { "/" }
                    davPath = if (parent.startsWith(rootPath)) parent else rootPath
                }
            }

            tab != DockTab.BOOKS -> tab = DockTab.BOOKS
        }
    }

    // ---------------------------------------------------------- v98 资源库操作

    /** 点开一张卡片：把那台切成当前，并重置浏览层级。 */
    fun openAccount(acc: ServerAccount) {
        onChange(settings.withAccounts(settings.accounts, acc.id))
        libOpen = acc
        komgaSeries = null
        libTick++
        if (acc.kind == ServerKind.WEBDAV) {
            davPath = normalizeRoot(acc.root)
            // 换了账号必须重新拉：路径可能一样，内容不一样
            davAccount = null
        }
    }

    /**
     * 新增 / 编辑落盘。
     *
     * 编辑**沿用老 id**（表单里带过来的），所以阅读进度、离线下载、
     * 目录缓存都还认得出这是同一台服务器 —— 换个 id 等于换台机器。
     */
    fun upsertAccount(acc: ServerAccount) {
        val existed = settings.accounts.any { it.id == acc.id }
        val list = settings.accounts.filterNot { it.id == acc.id } + acc
        // 第一台自动成为当前，否则加完还得再点一次「设为当前」才知道书架连哪
        val active = if (!existed && settings.activeId.isBlank()) acc.id else settings.activeId
        onChange(settings.withAccounts(list, active))
        libForm = null
        if (libOpen?.id == acc.id) libOpen = acc
        toast = if (existed) "已保存「${acc.name}」" else "已添加「${acc.name}」"
    }

    /** 删除。至少留一台 —— 删空了书架 / 同步 / 更新全都没得连。 */
    fun removeAccount(acc: ServerAccount) {
        val list = settings.accounts.filterNot { it.id == acc.id }
        if (list.isEmpty()) return
        onChange(settings.withAccounts(list))
        if (libOpen?.id == acc.id) {
            libOpen = null
            komgaSeries = null
        }
        toast = "已删除「${acc.name}」"
    }

    /** 表单里的「测试连接」：按类型分派，WebDAV 走 PROPFIND、Komga 走 users/me。 */
    suspend fun testAccount(acc: ServerAccount): String = when (acc.kind) {
        ServerKind.KOMGA -> when (val r = KomgaClient.ping(acc.primary, acc.authHeader)) {
            is KomgaClient.Res.Ok -> "连接成功 · ${r.v}"
            is KomgaClient.Res.Fail -> "连接失败：${r.message}"
        }

        ServerKind.WEBDAV -> {
            if (acc.urls.isEmpty()) {
                "还没填地址"
            } else {
                var last = "连接失败"
                var ok: String? = null
                // 备用地址挨个试：第一个通的就是「对的那条」，这也是备用地址存在的意义
                for (u in acc.urls) {
                    when (val r = WebDavClient.list(u, acc.user, acc.pass, normalizeRoot(acc.root))) {
                        is DavResult.Ok -> {
                            ok = "连接成功 · $u · ${r.entries.size} 项"
                            break
                        }

                        is DavResult.Fail -> last = "连接失败：${r.message}"
                    }
                }
                ok ?: last
            }
        }
    }

    /**
     * 打开一本 Komga 的书。
     *
     * 页清单靠 `media.pagesCount` + `pageUrls()` 直接拼出来，**不用再请求一次**
     * `/books/{id}/pages` —— 进阅读器就有总页数，进度条不会先显示 1/0，
     * 而且整个系列的话都能一次性拼好，「下一话」链和 WebDAV 那边完全同构。
     */
    fun openKomgaBook(
        acc: ServerAccount,
        series: KomgaClient.Series,
        book: KomgaClient.Book,
        all: List<KomgaClient.Book>
    ) {
        val base = acc.primary
        val auth = acc.authHeader
        val chapters = all.map { b ->
            NextChapter(
                title = b.title,
                // 前缀 komga: 让它和 WebDAV 的路径 key 天然不撞
                key = "komga:${b.id}",
                pages = KomgaClient.pageUrls(base, b.id, b.pages).mapIndexed { i, u ->
                    DavEntry(name = "%04d".format(i + 1), path = u, isDir = false)
                },
                authOverride = auth
            )
        }
        val me = chapters.firstOrNull { it.key == "komga:${book.id}" } ?: return
        val i = chapters.indexOf(me)
        reader = me.toSession(
            next = chapters.getOrNull(i + 1),
            chapters = chapters
        ).copy(
            title = "${series.title} · ${book.title}",
            startIndex = progressStore.get(me.key)?.index ?: 0
        )
    }

    /** 书架行 = 分组标题 + 卡片。booksTick 让进度写回后重算文案和「在读」分组。 */
    val bookRows: List<BooksRow> = remember(
        books, booksTick, settings.bookSort, settings.bookGroup
    ) {
        arrangeBooks(
            books = books,
            progress = { progressStore.get(it) },
            sort = BookSort.of(settings.bookSort),
            group = BookGroup.of(settings.bookGroup)
        )
    }

    /**
     * v19：把一组 WebDAV 条目整理成有序章节队列。详情页和文件页各自拉自己的目录，
     * 然后把这份队列塞进 [ReaderSession]，「下一话」链就跟着会话走，
     * 不再依赖文件页当时停在哪个目录（从详情页进阅读器时文件页根本没动过）。
     */
    fun chaptersFrom(list: List<DavEntry>): List<NextChapter> =
        list.asSequence()
            .filter { !it.isDir }
            .filter { it.name.substringAfterLast('.', "").lowercase() in setOf("cbz", "zip") }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
            .mapNotNull { e ->
                WebDavClient.fileUrl(settings.webdavUrl, e.path)?.let { u ->
                    NextChapter(
                        title = e.name.substringBeforeLast('.'),
                        key = e.path,
                        cbzUrl = u
                    )
                }
            }
            .toList()

    fun nextIn(queue: List<NextChapter>, key: String): NextChapter? {
        val i = queue.indexOfFirst { it.key == key }
        return if (i >= 0) queue.getOrNull(i + 1) else null
    }

    /** 从队列里打开某一话。 */
    fun sessionOf(
        queue: List<NextChapter>,
        key: String,
        title: String,
        url: String
    ): ReaderSession {
        val i = queue.indexOfFirst { it.key == key }
        return ReaderSession(
            title = title,
            key = key,
            cbzUrl = url,
            startIndex = progressStore.get(key)?.index ?: 0,
            next = if (i >= 0) queue.getOrNull(i + 1) else null,
            chapters = queue
        )
    }

    /**
     * 从书架打开：单本 CBZ 直接进阅读器；目录书进独立详情页，不碰文件页。
     */
    fun openBook(b: BookItem) {
        if (b.isCbz) {
            val u = WebDavClient.fileUrl(settings.webdavUrl, b.path)
            if (u == null) {
                notice = "服务器地址拼不出文件 URL，去设置里检查一下。"
                return
            }
            reader = sessionOf(chaptersFrom(dav.entries), b.key, b.title, u)
        } else {
            detailStack = listOf(DetailEntry(b))
        }
    }

    fun addToShelf(e: DavEntry) {
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val isCbz = ext == "cbz" || ext == "zip"
        val ok = bookshelf.add(
            BookItem(
                key = e.path,
                title = if (isCbz) e.name.substringBeforeLast('.') else e.name,
                path = e.path,
                isCbz = isCbz
            )
        )
        books = bookshelf.all()
        notice = if (ok) "「${e.name}」已加入书架。" else "这本已经在书架里了。"
    }

    /** 路径行右侧「＋ 加入」：收藏当前目录。 */
    fun addCurrentDir() {
        val p = dav.path
        if (p == "/") {
            notice = "根目录不算一本书，先进到某本漫画的目录再加。"
            return
        }
        val name = p.trimEnd('/').substringAfterLast('/').ifEmpty { p }
        val ok = bookshelf.add(BookItem(key = p, title = name, path = p, isCbz = false))
        books = bookshelf.all()
        notice = if (ok) "「$name」已加入书架。" else "这个目录已经在书架里了。"
    }

    /** 文件页一行的点击行为。抽出来是因为长按要单独接「加入书架」。 */
    fun onRowTap(row: FilesRow) {
        when (row) {
            is FilesRow.Up -> davPath = row.target
            is FilesRow.Entry -> {
                if (row.e.isDir) {
                    davPath = row.e.path
                } else {
                    val ext = row.e.name.substringAfterLast('.', "").lowercase()
                    when (ext) {
                        "cbz", "zip" -> {
                            val u = WebDavClient.fileUrl(settings.webdavUrl, row.e.path)
                            if (u == null) {
                                notice = "服务器地址拼不出文件 URL，去设置里检查一下。"
                            } else {
                                reader = sessionOf(
                                    chaptersFrom(dav.entries),
                                    row.e.path,
                                    row.e.name.substringBeforeLast('.'),
                                    u
                                )
                            }
                        }

                        "cbr", "rar", "7z" -> {
                            notice = "「${row.e.name}」\n\n" +
                                "这种压缩格式还不支持，转成 cbz 或 zip 就能直接读。"
                        }

                        else -> {
                            notice = "「${row.e.name}」\n\n这个文件打不开。"
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------ v83 应用内更新

    /**
     * 查 NAS 上的 `updates/latest-android.json`。
     *
     * 跟 Flutter 版逐条对齐：查到了但版本不新 → 只提示；版本新 → 弹确认框，
     * 用户点「下载并安装」才真的下。失败一律把服务器的原话搬到 notice 里，
     * 不要只写一句"检查失败"—— 401 和 404 的处置方式完全不同。
     */
    fun checkUpdate() {
        if (updateChecking || updateDownloading) return
        if (!settings.webdavConfigured) {
            notice = "还没配置服务器。\n\n" +
                "更新清单放在同步目录的 ${Updater.DIR_NAME}/ 里，" +
                "文件名 ${Updater.MANIFEST_NAME}。"
            return
        }
        updateChecking = true
        updateStatus = "检查中…"
        uiScope.launch {
            when (val r = Updater.check(settings)) {
                is Updater.CheckResult.Found -> {
                    updateStatus = "可更新 ${r.manifest.versionName}"
                    updateFound = r.manifest
                }

                is Updater.CheckResult.Current -> {
                    updateStatus = "已是最新 · ${Updater.current.text}"
                    toast = "已是最新版本"
                }

                is Updater.CheckResult.Fail -> {
                    updateStatus = "检查失败"
                    notice = "检查更新失败：\n\n${r.message}"
                }
            }
            updateChecking = false
        }
    }

    /**
     * 下载 → 校验 → 交给系统安装器。
     *
     * 对话框在这一步就关掉，进度改由设置行右侧和 toast 承担 ——
     * 下载要几十秒，让用户盯着一个不动的弹窗没有意义。
     */
    fun downloadAndInstall(m: Updater.Manifest) {
        if (updateDownloading) return
        updateFound = null
        updateDownloading = true
        updateStatus = "下载中…"
        toast = "正在下载 ${m.versionName}…"
        uiScope.launch {
            val r = Updater.download(appCtx, settings, m)
            updateDownloading = false
            when (r) {
                is Updater.FetchResult.Ok -> {
                    updateStatus = "已下载 ${m.versionName}"
                    // Android 8+ 没开「安装未知应用」时，安装器会直接把你挡回去，
                    // 这里先问一句再跳设置，省得用户以为包坏了。
                    if (!Updater.canInstall(appCtx)) {
                        installPermNeeded = true
                        return@launch
                    }
                    if (!Updater.install(appCtx, r.file)) {
                        notice = "系统没有接起安装请求。\n\n" +
                            "去「设置 → 应用 → MangaDock → 安装未知应用」里打开开关再试一次。"
                    }
                }

                is Updater.FetchResult.Fail -> {
                    updateStatus = "下载失败"
                    notice = "下载更新失败：\n\n${r.message}"
                }
            }
        }
    }

    /** 设置页一行的点击行为。 */
    fun onSettingsKey(key: SKey) {
        when (key.kind) {
            SKeyKind.TOGGLE -> onChange(key.flip(settings))
            SKeyKind.ACTION -> {
                when (key) {
                    SKey.CACHE -> clearCacheConfirm = true
                    SKey.DOWNLOADS -> clearDownloadsConfirm = true
                    SKey.UPDATE -> checkUpdate()
                    // v98：设置里的服务器那几行文本没了，统一去资源库页改
                    SKey.LIBRARY -> {
                        libOpen = null
                        komgaSeries = null
                        tab = DockTab.FILES
                    }
                    SKey.SYNC_PUSH -> uiScope.launch {
                        val r = SyncStore.push(appCtx, settings, bookshelf, progressStore, manual = true)
                        notice = r.message
                        // 状态行是 remember(syncTick) 出来的，不重算就还显示上一次的时间
                        if (r.ok) syncTick++
                    }
                    SKey.SYNC_PULL -> uiScope.launch {
                        val r = SyncStore.pull(appCtx, settings, bookshelf, progressStore, manual = true)
                        notice = r.message
                        if (r.ok) {
                            syncApplying = true
                            privacyBooks = privacy.all()
                            booksTick++
                            syncApplying = false
                            syncTick++
                            val fresh = settingsStore.load()
                            pulledSettings = fresh
                            onChange(fresh)
                        }
                    }
                    SKey.BANGUMI_CLEAR -> {
                        BangumiClient.clearCache(appCtx)
                        cacheTick++
                        notice = "Bangumi 元数据缓存已清空"
                    }
                    // v26：以前这行只是「跳到文件页 + 重连」，等于没测。
                    // 现在真发一次 PROPFIND，把结果和耗时写回行右端。
                    SKey.WEBDAV_TEST -> {
                        if (!settings.webdavConfigured) {
                            notice = "还没填服务器地址，先填一个再测。"
                        } else if (!davTesting) {
                            davTesting = true
                            davTest = "测试中…"
                            uiScope.launch {
                                val t0 = System.currentTimeMillis()
                                val r = WebDavClient.list(
                                    settings.webdavUrl, settings.webdavUser,
                                    settings.webdavPass, normalizeRoot(settings.webdavRoot)
                                )
                                val ms = System.currentTimeMillis() - t0
                                davTesting = false
                                davTest = when (r) {
                                    is DavResult.Ok -> "已连接 · ${r.entries.size} 项 · ${ms}ms"
                                    is DavResult.Fail -> "连接失败 · ${ms}ms"
                                }
                                if (r is DavResult.Fail) {
                                    notice = "连接失败：\n\n${r.message}"
                                } else {
                                    // 顺手把文件页也刷一遍，省得再手动重连
                                    reloadTick++
                                }
                            }
                        }
                    }
                    // 走 openPrivacy()，别直接置 privacyOpen —— 那会绕过解锁，
                    // 设了锁屏密码的机器上等于白挂一个隐私空间
                    SKey.PRIVACY -> openPrivacy()
                    else -> Unit
                }
            }
            else -> dialogKey = key
        }
    }

    // v37 玻璃源提到 BoxWithConstraints 外：FlClash 的弹出菜单也采同一层内容做玻璃，
    // 所以三页、顶栏、底栏、长按菜单必须共享同一个 source。
    val appGlassSource = rememberGlassSource()
    BoxWithConstraints(Modifier.fillMaxSize().background(MangaTheme.colors.bg)) {
        val glassSource = appGlassSource
        val w = maxWidth
        val h = maxHeight
        // 顶栏实际高度 = 状态栏 + 内容高。三页列表用 topInset 让出同一截，别各写一个数
        val topBarH = statusTop + TopBarContentH
        // v39：胶囊定宽并水平居中，不再贴着屏幕左右撑成通栏。
        // 窄屏 / 横屏时才退让，保证左右至少留 MIN_EDGE_MARGIN。
        val barW = minOf(
            w - DockMetrics.MIN_EDGE_MARGIN.dp * 2,
            DockMetrics.BAR_WIDTH.dp
        )
        val barLeft = (w - barW) / 2
        val barTop = h - DockMetrics.BOTTOM_GAP.dp - DockMetrics.BAR_HEIGHT.dp

        Box(Modifier.fillMaxSize()) {
            /*
             * v42：内容**整屏铺满**，一直铺到状态栏底下 —— 这是 FlClash 的前提，
             * 不是纯页面底色（老大截图纠正过：顶部能透出「状态/未运行」等淡影）。
             * 顶栏自己不画 surface，靠 TopBar 里那层 FloatingHeader 式 scrim 把
             * 滚上来的内容压成淡影；glassSource 录整屏，底栏采样不受影响。
             */
            Box(
                Modifier
                    .fillMaxSize()
                    .glassSource(glassSource)
            ) {
                /*
                 * v72：三页常驻 + 各自 alpha 交叉淡入淡出，替掉 AnimatedContent。
                 *
                 * 旧写法的问题：AnimatedContent 的 content 槽是按 targetState 分键的，
                 * 每次切页都把目标页**从零组合一遍** —— 列表重新测量、卡片封面
                 * produceState 全部重启、几十行文字重新排版。实测单次切页有一帧
                 * 45ms（弱机上这一帧会到一两百毫秒），落在手指按下的那一瞬间，
                 * 表现就是「点胶囊切换特别卡」。
                 *
                 * 现在三页（首次访问之后）一直留在组合里，切页只改各自的 alpha：
                 * 不重组、不重新测量、不重启协程，滚动位置也跟着留住。
                 *
                 * 视觉不变：源码 home.dart:376-415 是 PageView 的 `Opacity(1-|delta|)`
                 * 原地交叉淡入淡出，220ms / Curves.easeInOut、无位移 —— 这里同样两页
                 * 同时淡入淡出，曲线时长都从 PrismPageTransition.fadeTabSpec() 取。
                 */
                val pageOf: @Composable (DockTab) -> Unit = { t ->
                    when (t) {
                        DockTab.BOOKS -> BooksPage(
                            settings = settings,
                            rows = bookRows,
                            gridState = booksGrid,
                            layout = settings.bookLayout,
                            onOpen = { openBook(it) },
                            onLongPress = { b, at -> bookMenu = b; bookMenuAt = at },
                            // coverTick 是「封面该重取了」的信号，和 booksTick（书变了）分开：
                            // 下拉刷新只该动封面，不该把书目重新排一遍。
                            coverTick = booksTick + coverTick,
                            refreshing = pullSpinning,
                            onRefresh = { refreshShelf(fromPull = true) },
                            // 顶栏浮在列表上，首条要往下让出这一截
                            topInset = topBarH,
                            onGo = {
                                // v98：书架「去添加」直接落到资源库的卡片墙；
                                // 已经有服务器的话，先帮着把那台的页面打开
                                val davAcc = settings.webdavAccount
                                if (davAcc != null) {
                                    openAccount(davAcc)
                                    tab = DockTab.FILES
                                } else {
                                    tab = if (settings.hasAnyServer) DockTab.FILES
                                    else DockTab.SETTINGS
                                }
                            }
                        )

                        // v98：这一页现在是三层结构
                        //   卡片墙 → （点开 WebDAV）目录页 / （点开 Komga）系列页
                        DockTab.FILES -> {
                            val acc = libOpen
                            when {
                                acc == null -> LibraryPage(
                                    settings = settings,
                                    onOpen = { openAccount(it) },
                                    onAddClick = { addKindPick = true },
                                    onEdit = { libForm = ServerFormState.of(it) },
                                    onActivate = { a ->
                                        onChange(settings.withAccounts(settings.accounts, a.id))
                                        toast = "已切换到「${a.name}」"
                                    },
                                    onDelete = { removeAccount(it) },
                                    topInset = topBarH,
                                    glass = glassSource
                                )

                                acc.kind == ServerKind.KOMGA -> KomgaBrowserPage(
                                    base = acc.primary,
                                    auth = acc.authHeader,
                                    series = komgaSeries,
                                    onOpenSeries = { komgaSeries = it },
                                    onOpenBook = { s, b, all -> openKomgaBook(acc, s, b, all) },
                                    topInset = topBarH,
                                    reloadTick = libTick
                                )

                                else -> FilesPage(
                                    settings = settings,
                                    state = dav,
                                    joined = bookshelf.contains(dav.path),
                                    listState = filesList,
                                    onReload = { reloadTick++ },
                                    onJoin = { addCurrentDir() },
                                    onRead = {
                                        reader = ReaderSession(
                                            title = dav.path.trimEnd('/')
                                                .substringAfterLast('/')
                                                .ifEmpty { "根目录" },
                                            key = dav.path,
                                            pages = dav.images,
                                            startIndex = dav.readIndex.coerceAtLeast(0)
                                        )
                                    },
                                    onGoSettings = { tab = DockTab.SETTINGS },
                                    onRow = { onRowTap(it) },
                                    topInset = topBarH,
                                    // 只对文件夹弹菜单；单个 cbz 长按不再「一键加书架」
                                    onRowLong = { e, at -> if (e.isDir) { rowMenu = e; rowMenuAt = at } }
                                )
                            }
                        }

                        DockTab.SETTINGS -> SettingsPage(
                            settings = settings,
                            page = settingsPage,
                            davStatus = davTest ?: dav.statusText,
                            cacheSize = cacheSize,
                            downloadSize = downloadSize,
                            syncStatus = syncStatus,
                            privacyCount = privacyBooks.size,
                            updateStatus = updateStatus,
                            listState = settingsList,
                            onKey = { onSettingsKey(it) },
                            onSelect = { k, i -> onChange(k.select(settings, i)) },
                            onOpenPage = { settingsPage = it },
                            // v68：PICKER 锚定到被点的行，弹出与长按菜单同一套玻璃小卡片；
                            // 其它类型仍走原来的 onSettingsKey（开关 / 动作 / 文本 / 信息）。
                            onPickerAt = { k, rect ->
                                if (k.kind == SKeyKind.PICKER) {
                                    pickerAnchor = rect
                                    dialogKey = k
                                } else onSettingsKey(k)
                            },
                            topInset = topBarH
                        )
                    }
                }

                // 首次访问过的页才进组合：冷启动别白白多组两页。
                val visitedTabs = remember { mutableStateListOf(tab) }
                val tabAlpha = remember {
                    DockTab.entries.associateWith { Animatable(if (it == tab) 1f else 0f) }
                }
                LaunchedEffect(tab) {
                    if (!visitedTabs.contains(tab)) visitedTabs.add(tab)
                    // 两页同时跑：中途改主意也不跳，各自从当前值接着动画到目标值
                    // （旧 AnimatedContent 的「重定向」行为，这里天然就有了）。
                    coroutineScope {
                        DockTab.entries.forEach { t ->
                            launch {
                                tabAlpha.getValue(t).animateTo(
                                    targetValue = if (t == tab) 1f else 0f,
                                    animationSpec = PrismPageTransition.fadeTabSpec(),
                                )
                            }
                        }
                    }
                }
                DockTab.entries.forEach { t ->
                    if (t in visitedTabs) {
                        val active = t == tab
                        val a = tabAlpha.getValue(t)
                        Box(
                            Modifier
                                .fillMaxSize()
                                // 当前页压最上层：隐藏页虽然 alpha=0，命中测试仍然有效，
                                // 不抬高 z 的话点击会被看不见的那一页先接走。
                                .zIndex(if (active) 1f else 0f)
                                // alpha 归零时整棵子树不下发绘制，省一次全屏录制；
                                // 组合状态照留 —— 这就是「常驻」的意义。
                                .drawWithContent { if (a.value > 0.002f) drawContent() }
                                .graphicsLayer { alpha = a.value }
                        ) {
                            pageOf(t)
                            // 非当前页盖一层事件吸收层，别让点击穿透到看不见的列表上。
                            if (!active) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent().changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }

            // v42：顶栏压在内容上，FloatingHeader 式 scrim 把内容压成淡影；按钮单独玻璃
            TopBar(
                statusTop = statusTop,
                title = when (tab) {
                    DockTab.BOOKS -> "书架"
                    // v98：卡片墙叫「资源库」，点进去以后标题换成那台服务器的名字；
                    // Komga 再进一层就显示系列名，和 iOS 那边一致
                    DockTab.FILES -> libOpen?.let { acc ->
                        if (acc.kind == ServerKind.KOMGA) {
                            komgaSeries?.title ?: acc.name
                        } else {
                            acc.name
                        }
                    } ?: "资源库"

                    DockTab.SETTINGS -> settingsPage.title
                },
                // 一级页面（书架 / 资源库卡片墙 / 设置根页）一律大标题 —— 参考的 SenPlayer、
                // Streama 都是这个路子；只有二级页收成常规标题，那边左边有返回钮，
                // 28sp 的大标题会把它挤扁。
                large = when (tab) {
                    DockTab.SETTINGS -> settingsPage == SettingsPageId.ROOT
                    DockTab.FILES -> libOpen == null
                    else -> true
                },
                // v80：顶栏刷新钮去掉（老大 14:33「改得更丑了，暂时去掉」）。
                // 书架 / 文件页本来就各有下拉刷新，少一个入口不影响功能。
                // TopBar 的 onRefresh 参数留着，以后要恢复只需重新传 lambda。
                onRefresh = null,
                // 设置子页 / 资源库子页都给个返回钮，别只靠系统返回键
                onBack = when {
                    tab == DockTab.SETTINGS && settingsPage != SettingsPageId.ROOT ->
                        { { settingsPage = SettingsPageId.ROOT } }

                    tab == DockTab.FILES && libOpen != null -> { { closeLibLevel() } }
                    else -> null
                },
                glass = glassSource,
                // v87：书架页右上角的隐私书架快捷入口。
                // v98：资源库卡片墙右上角的「＋」——点开先选 WebDAV 还是 Komga。
                // openPrivacy() 已经处理「已解锁直接进 / 没解锁先过系统凭证」，
                // 这里只补入口，不复制解锁逻辑；按钮与返回/排序钮同一套圆形玻璃。
                trailing = {
                    if (tab == DockTab.BOOKS) {
                        PrismCircleButton(
                            onClick = { openPrivacy() },
                            glass = glassSource,
                            dark = MangaTheme.colors.dark,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            LockGlyph(MangaTheme.colors.ink)
                        }
                    }
                    if (tab == DockTab.FILES && libOpen == null) {
                        PrismCircleButton(
                            onClick = { addKindPick = true },
                            glass = glassSource,
                            dark = MangaTheme.colors.dark,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                "+",
                                style = TextStyle(
                                    fontSize = 22.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                ),
                                color = MangaTheme.colors.ink
                            )
                        }
                    }
                }
            )

            GlassDock(
                barLeft = barLeft,
                barTop = barTop,
                barWidth = barW,
                selected = tab,
                accent = settings.accentColor,
                glass = glassSource,
                // v97：设置 → 外观 → 液态玻璃（无 / 标准 / 极致）
                glassLevel = settings.glassLevelKind,
                onSelect = { t -> if (t != tab) tab = t }
            )

            // v26 隐私空间：盖在三页和底栏之上、详情页和阅读器之下。
            // 从里面打开一本书，退出来还停在隐私空间，不会漏回公开书架。
            AnimatedContent(
                targetState = privacyOpen,
                modifier = Modifier.fillMaxSize(),
                // FlClash SharedXPageTransition：push/pop 两套，450ms / slide 0.25
                transitionSpec = { PrismPageTransition.sharedX(forward = targetState) },
                label = "privacy-shelf"
            ) { open ->
                if (open) {
                    PrivacyShelfScreen(
                        books = privacyBooks,
                        settings = settings,
                        unlocked = privacyUnlocked,
                        deviceLocked = deviceLocked,
                        onUnlock = { requestPrivacyUnlock() },
                        onSetLock = {
                            // 没设锁屏密码的机器上，按钮直接跳到系统安全设置，
                            // 不留一个按了没反应的「解锁」
                            val i = Intent(Settings.ACTION_SECURITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (runCatching { appCtx.startActivity(i) }.isFailure) {
                                notice = "这台设备没有可打开的锁屏设置，隐私空间暂时不加锁。"
                            }
                        },
                        onBack = { privacyOpen = false },
                        onOpen = { openBook(it) },
                        onLongPress = { b, at -> privacyMenu = b; privacyMenuAt = at },
                        layout = settings.bookLayout,
                        coverTick = booksTick + coverTick,
                        refreshing = pullSpinning,
                        onRefresh = { refreshShelf(fromPull = true) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 漫画详情页：盖在三页和底栏之上，自己带返回圆钮。
            // 它不改 tab / davPath，所以文件页原来停在哪，退回来还在哪。
            AnimatedContent(
                targetState = detailStack.lastOrNull(),
                modifier = Modifier.fillMaxSize(),
                // FlClash CommonRoute / SharedXPageRouteMixin：push 从右 25% 滑入，
                // pop 退回右 25%，交接点 0.2，450ms / easeInOutCubicEmphasized。
                transitionSpec = { PrismPageTransition.sharedX(forward = targetState != null) },
                label = "book-detail"
            ) { entry ->
                if (entry != null) {
                    BookDetailScreen(
                        book = entry.book,
                        settings = settings,
                        dirCache = dirCache,
                        progress = { progressStore.get(it) },
                        onBack = { detailStack = detailStack.dropLast(1) },
                        onOpenFolder = { child, q -> detailStack = detailStack + DetailEntry(child, q) },
                        onOpenSession = { reader = it },
                        onNotice = { notice = it },
                        inherited = entry.queue,
                        glass = appGlassSource,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 阅读器进出：淡入 + 轻微放大。退出时旧会话会保留到淡出结束，
            // 进度写盘和画面都不会在动画中途突然消失。
            AnimatedContent(
                targetState = reader,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { PrismPageTransition.sharedX(forward = targetState != null) },
                label = "reader"
            ) { s ->
                // 没有会话时什么都不放：AnimatedContent 自己会保留退出中的旧内容，
                // 不需要全屏空 Box 撑尺寸，也就不会有透明层盖在三页上面挡点击。
                if (s != null) {
                    ReaderScreen(
                        session = s,
                        settings = settings,
                        onProgress = { i, n ->
                            progressStore.put(s.key, i, n)
                            // 同步到文件页，退出后那行会变成「继续阅读」
                            if (dav.path == s.key) dav = dav.copy(readIndex = i)
                            // 书架卡片上的「第 N 页」也跟着刷新
                            booksTick++
                        },
                        onExit = { reader = null },
                        onNextChapter = { n ->
                            // 换一话就是一次全新会话：remember(session.key) 会把页号/模式重置。
                            // 章节队列跟着会话带过去，详情页进来的也能一路「下一话」到底。
                            val q = reader?.chapters.orEmpty()
                            reader = n.toSession(next = nextIn(q, n.key), chapters = q)
                        }
                    )
                }
            }
        }
    }

    dialogKey?.let { key ->
        when (key.kind) {
            SKeyKind.PICKER -> OptionPickerDialog(
                key = key,
                anchor = pickerAnchor,
                settings = settings,
                onDismiss = { dialogKey = null },
                onPick = { i ->
                    onChange(key.select(settings, i))
                    dialogKey = null
                },
                glass = appGlassSource
            )

            SKeyKind.TEXT -> TextEditDialog(
                key = key,
                settings = settings,
                onDismiss = { dialogKey = null },
                onCommit = { v ->
                    onChange(key.withText(settings, v))
                    dialogKey = null
                },
                glass = appGlassSource
            )

            SKeyKind.INFO -> InfoDialog(key, { dialogKey = null }, appGlassSource)
            else -> Unit
        }
    }

    // v98：＋ 先问类型，再进表单。两个都是全屏 / 居中弹层，所以放在最外层，
    // 盖住底栏和顶栏（表单里要输入，底栏那条玻璃压在下面会分心）。
    if (addKindPick) {
        AddKindDialog(
            onDismiss = { addKindPick = false },
            onPick = { kind ->
                addKindPick = false
                libForm = ServerFormState.blank(kind)
            },
            glass = appGlassSource
        )
    }

    libForm?.let { form ->
        ServerFormOverlay(
            initial = form,
            onCancel = { libForm = null },
            onSave = { upsertAccount(it) },
            onTest = { testAccount(it) }
        )
    }

    if (clearDownloadsConfirm) {
        // v66：M3 AlertDialog -> FlClash CommonDialog 的 1:1 镜像。
        // 源码 destructive 语义：最后一项销毁数据 -> 红色、不做默认填充。
        PrismDialog(
            title = "清空离线下载",
            onDismissRequest = { clearDownloadsConfirm = false },
            glass = appGlassSource,
            destructive = true,
            actions = listOf(
                PrismDialogAction("取消") { clearDownloadsConfirm = false },
                PrismDialogAction("清空") {
                    clearDownloadsConfirm = false
                    uiScope.launch {
                        DownloadStore.removeAll(appCtx)
                        notice = "离线下载已清空"
                    }
                },
            ),
        ) { _ ->
            Text("会删掉所有已下载到本机的章节，服务器上的原文件不受影响。没网时就不能看了。")
        }
    }

    if (clearCacheConfirm) {
        PrismDialog(
            title = "清空缓存",
            onDismissRequest = { clearCacheConfirm = false },
            glass = appGlassSource,
            destructive = true,
            actions = listOf(
                PrismDialogAction("取消") { clearCacheConfirm = false },
                PrismDialogAction("清空") {
                    val freed = CacheStore.clear(appCtx)
                    clearCacheConfirm = false
                    cacheTick++
                    // 目录缓存也删了，回文件页会重新拉一次；顺手让状态别停在旧的「已连接」
                    notice = "缓存已清空" + if (freed > 0L) "（释放 ${freed / 1024 / 1024} MB）" else ""
                },
            ),
        ) { _ ->
            Text("会删掉已缓存的漫画页面、目录列表和书架封面；书架、阅读进度、WebDAV 账号都不受影响。\n下次打开会重新从服务器取。")
        }
    }

    // v83：发现新版本。文案和 Flutter 版 lib/shelf.dart:2687-2706 一致 ——
    // 「当前 → 新版」+ 更新说明，动作只有「以后再说 / 下载并安装」。
    updateFound?.let { m ->
        PrismDialog(
            title = "发现新版本 ${m.versionName}",
            onDismissRequest = { updateFound = null },
            glass = appGlassSource,
            actions = listOf(
                PrismDialogAction("以后再说") { updateFound = null },
                PrismDialogAction("下载并安装") { downloadAndInstall(m) },
            ),
        ) { _ ->
            Text(
                "当前 ${Updater.current.text} → 新版 ${m.versionName}（${m.code}）\n\n" +
                    m.notes.ifBlank { "（这个版本没有写更新说明）" }
            )
            Text(
                "安装包从服务器下载，装的时候由系统弹确认窗口 —— " +
                    "应用不能自己静默替换自己。",
                style = MangaType.bodySmall,
                color = MangaTheme.colors.sub,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }

    // 包已经下好了，但系统没开「安装未知应用」：直接拉起安装器只会被弹回来，
    // 所以先说明清楚，再把用户送到那个开关面前。
    if (installPermNeeded) {
        PrismDialog(
            title = "先允许安装",
            onDismissRequest = { installPermNeeded = false },
            glass = appGlassSource,
            actions = listOf(
                PrismDialogAction("取消") { installPermNeeded = false },
                PrismDialogAction("去开启") {
                    installPermNeeded = false
                    Updater.openInstallSettings(appCtx)
                },
            ),
        ) { _ ->
            Text("系统还没允许 MangaDock 安装应用。\n\n打开「安装未知应用」开关后，回到这里再点一次「检查更新」就能装上；更新包已经下载好了。")
        }
    }

    // 文件页长按文件夹：先看详情、再加书架。隐私书架没做之前不摆按不动的按钮。
    rowMenu?.let { e ->
        LongPressMenu(
            anchor = rowMenuAt,
            onDismiss = { rowMenu = null },
            glass = appGlassSource,
            entries = listOf(
                MenuEntry.Row("查看详情") {
                    rowMenu = null
                    detailStack = listOf(
                        DetailEntry(
                            BookItem(key = e.path, title = e.name, path = e.path, isCbz = false)
                        )
                    )
                },
                MenuEntry.Row("加入书架") {
                    rowMenu = null
                    addToShelf(e)
                }
            )
        )
    }

    // v25：书架长按菜单。自定义封面 / 恢复默认 / 移出书架都收在这里，
    // 以后加「隐私书架」也往这个菜单里塞，别再为单个动作加长按手势。
    bookMenu?.let { b ->
        val hasCustom = CoverStore.hasCustom(appCtx, b.key)
        LongPressMenu(
            anchor = bookMenuAt,
            onDismiss = { bookMenu = null },
            glass = appGlassSource,
            entries = buildList {
                add(
                    MenuEntry.Row("自定义封面") {
                        bookMenu = null
                        coverTarget = b
                        pickCover.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                if (hasCustom) {
                    add(
                        MenuEntry.Row("恢复默认封面") {
                            bookMenu = null
                            CoverStore.removeCustom(appCtx, b.key)
                            booksTick++
                            notice = "已恢复默认封面。"
                        }
                    )
                }
                add(MenuEntry.Divider)
                // v26 隐私书架：整本书从可见书架移走，落到隐私空间
                add(
                    MenuEntry.Row("隐藏进隐私空间") {
                        bookMenu = null
                        val ok = privacy.hide(bookshelf, b)
                        books = bookshelf.all()
                        booksTick++
                        privacyBooks = privacy.all()
                        notice = if (ok) {
                            "「${b.title}」已移进隐私空间。\n\n从「设置 → 书架 → 隐私空间」进去看。"
                        } else {
                            "「${b.title}」已经在隐私空间里了。"
                        }
                    }
                )
                // 移出书架是破坏性操作：单列一行、红色、和上面隔一条线 —— SenPlayer 的「删除」就是这个位置
                add(MenuEntry.Divider)
                add(
                    MenuEntry.Row("移出书架", danger = true) {
                        bookMenu = null
                        bookToRemove = b
                    }
                )
            }
        )
    }

    // 隐私空间里的长按菜单：只有「放回书架」和「彻底移出」两件事。
    privacyMenu?.let { b ->
        LongPressMenu(
            anchor = privacyMenuAt,
            onDismiss = { privacyMenu = null },
            glass = appGlassSource,
            entries = listOf(
                MenuEntry.Row("恢复到书架") {
                    privacyMenu = null
                    privacy.restore(bookshelf, b.key)
                    books = bookshelf.all()
                    privacyBooks = privacy.all()
                    booksTick++
                    notice = "「${b.title}」已回到书架。"
                },
                MenuEntry.Divider,
                MenuEntry.Row("移出隐私空间", danger = true) {
                    privacyMenu = null
                    privacy.remove(b.key)
                    privacyBooks = privacy.all()
                    booksTick++
                    notice = "「${b.title}」已移出隐私空间，服务器上的文件不会删除。"
                }
            )
        )
    }

    bookToRemove?.let { b ->
        PrismDialog(
            title = "移出书架",
            onDismissRequest = { bookToRemove = null },
            glass = appGlassSource,
            destructive = true,
            actions = listOf(
                PrismDialogAction("取消") { bookToRemove = null },
                PrismDialogAction("移出书架") {
                    bookshelf.remove(b.key)
                    books = bookshelf.all()
                    bookToRemove = null
                },
            ),
        ) { _ ->
            Text("「${b.title}」将不再显示在书架里，服务器上的文件不会删除。")
        }
    }

    // 轻提示：浮在底栏上方，1.6s 自己消失。没有按钮、不拦点击。
    // v91：样式抽到 ui/PillToast，详情页排序切换也从系统 Toast 改成它，全站只剩一种提示。
    PillToast(toast, onDismiss = { toast = null })

    notice?.let { msg ->
        PrismDialog(
            title = "提示",
            onDismissRequest = { notice = null },
            glass = appGlassSource,
            actions = listOf(PrismDialogAction("好") { notice = null }),
        ) { _ ->
            Text(msg)
        }
    }
}

/** 顶栏在状态栏以下的内容高度。内容区让位、顶栏自身高度都取它，别各写一个数。 */
// v75：详情页也要用这一套顶栏，去掉 private 让 ui 包能 import。
// 两套顶栏各写一份正是 v42 之后详情页样式一直没跟上的原因。
val TopBarContentH = 74.dp

/** 顶栏：状态栏 + 74dp 标题区。
 *
 *  v42 照 FlClash / prism_ui 的 `FloatingHeader` 重做：
 *  内容整屏铺到状态栏底下，顶栏自己**不画 surface**，只盖一层页面底色的
 *  scrim 把滚上来的内容压成淡影（当前 55% 可见），标题和按钮浮在这层之上。
 *  只有返回 / 刷新按钮自带一小块独立玻璃圆底，没有通栏毛玻璃带。
 *
 *  和 v41 那版被否掉的「白纱」区别在**曲线**：那版从顶部就一路线性变淡，
 *  整条都在半透，彩色封面被搅成脏雾；这版前段（状态栏那一截）是 alpha 恒定的
 *  干净浅面，之后按 easeInOut 快速淡出，还往下多伸 16dp 消掉硬边。 */
@Composable
fun TopBar(
    statusTop: Dp,
    title: String,
    large: Boolean = false,
    /** 非空就在标题左边画一个返回圆钮（设置子页 / 详情页用） */
    onBack: (() -> Unit)? = null,
    /** 非空就在右边画一个刷新圆钮（书架 / 文件页用） */
    onRefresh: (() -> Unit)? = null,
    /** v75：右侧自定义按钮，详情页的「正序 / 倒序」用；与 onRefresh 可并存 */
    trailing: @Composable (RowScope.() -> Unit)? = null,
    /** 只给按钮的玻璃底采样用；为 null 时按钮退化成不采样的中性实心圆片 */
    glass: GlassSource? = null,
) {
    val barH = statusTop + TopBarContentH
    // DrawScope 里不能用 @Composable getter，先把值取出来给 lambda 捕获
    val c = MangaTheme.colors

    /*
     * 照抄 prism_ui/lib/sheet_header.dart 的 FloatingHeader._scrimOf：
     *   _fadeSteps = 8、fadeStart = top/(top+toolbarHeight)、overhang = 16
     * 换算到这边：scrim 高度 = barH + 16dp，恒定段正好到 statusTop 为止。
     * easeInOut 用 smoothstep t²(3-2t) 近似 Flutter 的 Curves.easeInOut。
     *
     * 上游 _alpha = 0.9。老大 21:22 指定看 0.45 这一档：遮罩更淡、内容透出
     * 55%（0.75 透 25%、0.95 只透 5%）。fadeStart / overhang / 步数全锁住，
     * 只动这一个数，方便横向比较。
     */
    val overhang = 16.dp
    val scrimH = barH + overhang
    /*
     * 上游 fadeStart = top/(top+toolbarHeight) ≈ 0.21，恒定段只盖到状态栏下沿。
     * 这里把恒定段延到标题上沿（至少 45% 高度），让标题不直接压在深色封面上；
     * 内容仍从下半段透出，不是纯底色，也不会像 v41 白纱那样整条半透。
     */
    val fadeStart = maxOf(statusTop.value / scrimH.value, 0.45f)
    val scrimAlpha = 0.45f
    val scrimStops = mutableListOf(0f to c.bg.copy(alpha = scrimAlpha))
    for (i in 0..8) {
        val t = i / 8f
        val eased = t * t * (3f - 2f * t)
        scrimStops += (fadeStart + (1f - fadeStart) * t) to
            c.bg.copy(alpha = scrimAlpha * (1f - eased))
    }

    Box(Modifier.fillMaxWidth().height(scrimH)) {
        // 不挂 clickable，这层不消费触摸；只是把内容压成淡影
        Box(
            Modifier
                .fillMaxWidth()
                .height(scrimH)
                .background(Brush.verticalGradient(*scrimStops.toTypedArray()))
        )

        Row(
            Modifier
                .fillMaxWidth()
                .height(barH)
                .padding(start = 16.dp, end = 12.dp, top = statusTop),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                // v87：统一的 44dp 圆形玻璃按钮；返回图标从细字符「‹」
                // 换成 20dp 描边折线，放大一圈也更清晰。
                PrismCircleButton(
                    onClick = onBack,
                    glass = glass,
                    dark = c.dark,
                    modifier = Modifier.padding(end = 10.dp),
                ) {
                    BackChevron(c.ink)
                }
            }
            Text(
                title,
                Modifier.weight(1f),
                softWrap = false,
                style = (if (large) MangaType.display else MangaType.titleLarge)
                    .copy(color = c.ink)
            )
            // 顶栏右侧原来挂着一组「三横线 + 三点」图标，纯装饰、没有 onClick，
            // 点了没反应。书架排序/分组在 设置 → 书架，没必要在顶栏再放一次。
            // v30：这里换成一个**真的能用**的刷新钮：书架重新取封面（Bangumi 补匹配
            // 失败冷却只在内存里，不点这一下就得等 5 分钟或者杀进程），文件页重拉当前目录。
            if (onRefresh != null) {
                PrismCircleButton(
                    onClick = onRefresh,
                    glass = glass,
                    dark = c.dark,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("↻", fontSize = 28.sp, color = c.ink)
                }
            }
            // v75：详情页的「正序 / 倒序」等自定义右侧控件
            trailing?.invoke(this)
        }
    }
}

// v87：顶栏圆钮统一搬到 ui/prism/PrismCircleButton.kt（PrismCircleButton），
// 主界面 / 详情页 / 设置子页共用同一套 44dp 圆形玻璃按钮与底栏同款材质。

/** 文件夹长按菜单里的一行。 */
@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 13.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MangaType.titleSmall, color = MangaTheme.colors.ink)
    }
}

// ---------------------------------------------------------------- 对话框

private fun dialogTitle(key: SKey): String = when (key) {
    SKey.THEME -> "主题"
    SKey.DARK_MODE -> "深浅模式"
    SKey.PAGE_DIR -> "翻页方向"
    SKey.PAGE_ANIM -> "翻页动画"
    SKey.READ_BG -> "阅读背景"
    SKey.BOOK_SORT -> "排序方式"
    SKey.BOOK_GROUP -> "分组方式"
    SKey.WEBDAV_URL -> "服务器地址"
    SKey.WEBDAV_USER -> "账号"
    SKey.WEBDAV_PASS -> "密码"
    SKey.WEBDAV_ROOT -> "漫画根路径"
    SKey.LICENSES -> "开源许可"
    SKey.BANGUMI_BASE -> "Bangumi 接口地址"
    SKey.SYNC_DIR -> "同步目录"
    SKey.BOOK_LAYOUT -> "书架布局"
    else -> key.name
}

/**
 * v68：选择类设置项与长按菜单**完全同一套** FlClash `CommonPopupMenu`。
 *
 * 之前 v66 用居中 `PrismDialog`（玻璃大卡片 + 绿色「取消」按钮），和书架长按出来的
 * 锚定小菜单是两种风格，老大指出不好看；现在统一走 `PrismPopupCard`：
 *  · `ui/list.dart:370-390` 的 `CommonPopupMenuItem(checked:)` 选中态 ✓
 *  · `popup.dart` 的双弹簧 platter morph / 200ms 收缩退出 / 16dp 安全边距 / avoid 翻转
 *  · anchor 就是被点那一行的 root bounds（Screens.kt 的 boundsInRoot）
 * 确认/输入/信息类仍走 `CommonDialog` 的镜像 —— 它们在 FlClash 里本来就是 dialog
 * 而不是 menu，且需要 TextField / 说明文字，塞不进菜单。
 */
@Composable
private fun OptionPickerDialog(
    key: SKey,
    anchor: Rect,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    glass: GlassSource? = null
) {
    val opts = key.options()
    val cur = key.currentIndex(settings)
    val items = opts.mapIndexed { i, label ->
        PrismPopupItem(
            label = label,
            checked = i == cur,
            onPressed = { onPick(i) },
        )
    }
    PrismPopupCard(
        anchor = anchor,
        items = items,
        onDismiss = onDismiss,
        glass = glass,
    )
}

@Composable
private fun TextEditDialog(
    key: SKey,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
    glass: GlassSource? = null
) {
    var buf by remember(key) { mutableStateOf(key.text(settings)) }
    val isPass = key == SKey.WEBDAV_PASS
    // v66：`InputDialog` 走 CommonDialog(hasFields: true)：title 用 body 字重、
    // 内容左右内边距 16（不是 30），最后一项「保存」是默认填充按钮。
    PrismDialog(
        title = dialogTitle(key),
        onDismissRequest = onDismiss,
        glass = glass,
        hasFields = true,
        actions = listOf(
            PrismDialogAction("取消", onDismiss),
            PrismDialogAction("保存") { onCommit(buf) },
        ),
    ) { _ ->
        OutlinedTextField(
            value = buf,
            onValueChange = { buf = it },
            singleLine = true,
            visualTransformation = if (isPass) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPass) KeyboardType.Password else KeyboardType.Uri
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (key == SKey.WEBDAV_URL) {
            Text(
                "例：http://192.168.1.10:5005/dav",
                style = MangaType.bodySmall,
                color = MangaTheme.colors.sub,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (key == SKey.BANGUMI_BASE) {
            Text(
                "留空用官方 api.bgm.tv/v0。可填自建接口（形如 https://域名/v0 或 http://内网IP:端口/v0）。请求直连，不再经过代理。",
                style = MangaType.bodySmall,
                color = MangaTheme.colors.sub,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun InfoDialog(
    key: SKey,
    onDismiss: () -> Unit,
    glass: GlassSource? = null
) {
    val (title, body) = when (key) {
        SKey.LICENSES -> "开源许可" to
                "Jetpack Compose / Kotlin（Apache-2.0）\nOkHttp（Apache-2.0）"
        else -> dialogTitle(key) to ""
    }
    PrismDialog(
        title = title,
        onDismissRequest = onDismiss,
        glass = glass,
        actions = listOf(PrismDialogAction("好", onDismiss)),
    ) { _ ->
        Text(body)
    }
}
