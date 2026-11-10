package com.mangadock.android.settings

import androidx.compose.ui.graphics.Color
import com.mangadock.android.bookshelf.BookGroup
import com.mangadock.android.bookshelf.BooksLayoutKind
import com.mangadock.android.bookshelf.BookSort

/**
 * 设置页的**数据层**：每一行的 key、类型、当前显示值、以及行的顺序。
 *
 * v26：从「一页长列表」改成**二级菜单**。原来十几个分区、二十来行全堆在一页里，
 * 找一个开关要从上滚到底。现在根页只放 6 个分类入口（右侧带当前值摘要），
 * 点进去才是具体选项；返回键 / 左上返回先回分类首页。
 *
 * 分组原则是「按用户想干什么」而不是按技术模块：
 *  - 外观：主题、深浅、取色
 *  - 阅读：翻页方向、翻页动画、双页、画质、常亮、背景
 *  - 书架：排序、分组、隐私空间
 *  - 服务器：WebDAV 五项
 *  - 元数据：Bangumi 开关/接口/代理/清缓存
 *  - 存储与关于：缓存、版本、许可
 *
 * 渲染在 `ui/Screens.kt` 的 `SettingsPage`。
 */

/** 设置的二级页面。ROOT 是分类首页。 */
/**
 * 设置分类的图标类型。画法见 `Screens.kt` 的 `SettingBadge`：
 * 一律用简单几何（圆 / 圆角矩形 / 弧），不引 material-icons-extended ——
 * 离线构建下多一个依赖就是一堵墙，而且这几个符号手绘反而更统一。
 */
enum class SettingIcon { PALETTE, BOOK, GRID, SERVER, SYNC, STAR, DRIVE }

/**
 * [tint] 是 ARGB 长整型，画的时候再转 `Color` —— 这样枚举本身不依赖 Compose 的图形类型，
 * 设置内容（纯数据）和渲染（Compose）还是分开的。
 */
enum class SettingsPageId(val title: String, val icon: SettingIcon, val tint: Long) {
    ROOT("设置", SettingIcon.PALETTE, 0xFF6B7280),
    APPEARANCE("外观", SettingIcon.PALETTE, 0xFFFF5C8A),
    READING("阅读", SettingIcon.BOOK, 0xFF3B82F6),
    SHELF("书架", SettingIcon.GRID, 0xFF22C55E),
    SERVER("服务器", SettingIcon.SERVER, 0xFF8B5CF6),
    SYNC("同步", SettingIcon.SYNC, 0xFFF59E0B),
    META("元数据", SettingIcon.STAR, 0xFF06B6D4),
    STORAGE("存储与关于", SettingIcon.DRIVE, 0xFF64748B)
}

/**
 * 每个可点的行认一个 key。有了它，绘制时才知道该去哪儿取状态，
 * 点击时才知道该改哪个字段。
 */
enum class SKey {
    THEME,
    /** v21：跟随系统 / 浅色 / 深色 */
    DARK_MODE,
    /** v21：从壁纸动态取色 */
    DYNAMIC_COLOR,
    /** v97：底栏液态玻璃三档 */
    GLASS_LEVEL,

    /**
     * v98：资源库（多台 WebDAV / Komga）。点这一行直接跳到资源库页签 ——
     * 服务器不再在设置里手输，因为「一台」这件事本身已经不成立了。
     */
    LIBRARY,

    WEBDAV_URL,
    WEBDAV_USER,
    WEBDAV_PASS,
    WEBDAV_ROOT,
    /** v26：真实发一次 PROPFIND 测连通性（以前只是跳到文件页，等于没测） */
    WEBDAV_TEST,

    /** 显示真实缓存大小，点一下弹清空确认 */
    CACHE,

    /**
     * v83：应用内更新。和 Flutter 版同一套方案 —— 读 WebDAV 上的
     * `updates/latest-android.json`，下载 APK 后交给系统安装器。
     * 右侧那串状态（当前版本 / 发现新版 / 下载 45%）由界面运行时注入。
     */
    UPDATE,

    /** v28：离线下载占用，点一下弹清空确认 */
    DOWNLOADS,

    /** v29：多设备同步（和 iOS 共用 .mangadock-sync.json） */
    SYNC_ENABLED,
    SYNC_DIR,
    SYNC_STATUS,
    SYNC_PUSH,
    SYNC_PULL,

    PAGE_DIR,
    /** v19：分页翻页动画三选一 */
    PAGE_ANIM,
    /** v20：画质优先（解码不压到刚好屏宽） */
    HIGH_QUALITY,
    DUAL_PAGE,
    KEEP_AWAKE,
    READ_BG,

    LICENSES,

    /** 书架：排序 / 分组 / 隐私空间 */
    BOOK_SORT,
    BOOK_GROUP,
    /** v31：书架网格 / 列表 */
    BOOK_LAYOUT,
    PRIVACY,

    /** v23：Bangumi 元数据 */
    BANGUMI_ENABLED,
    BANGUMI_BASE,
    BANGUMI_CLEAR
}

/** 点下去会发生什么。 */
enum class SKeyKind {
    /** 开关，点一下翻转 */
    TOGGLE,

    /** 选项多，弹个单选对话框 */
    PICKER,

    /** 弹输入框改文本 */
    TEXT,

    /** 只弹个说明 */
    INFO,

    /** 干一件具体的事（测试连接 / 清空缓存 / 进隐私空间） */
    ACTION
}

val SKey.kind: SKeyKind
    get() = when (this) {
        SKey.DUAL_PAGE, SKey.KEEP_AWAKE, SKey.HIGH_QUALITY, SKey.DYNAMIC_COLOR,
        SKey.BANGUMI_ENABLED, SKey.SYNC_ENABLED -> SKeyKind.TOGGLE
        SKey.THEME, SKey.DARK_MODE, SKey.PAGE_DIR, SKey.PAGE_ANIM, SKey.READ_BG,
        SKey.BOOK_SORT, SKey.BOOK_GROUP, SKey.BOOK_LAYOUT, SKey.GLASS_LEVEL -> SKeyKind.PICKER
        SKey.WEBDAV_URL, SKey.WEBDAV_USER, SKey.WEBDAV_PASS,
        SKey.WEBDAV_ROOT, SKey.BANGUMI_BASE, SKey.SYNC_DIR -> SKeyKind.TEXT
        SKey.WEBDAV_TEST, SKey.CACHE, SKey.BANGUMI_CLEAR, SKey.PRIVACY,
        SKey.DOWNLOADS, SKey.SYNC_PUSH, SKey.SYNC_PULL, SKey.UPDATE,
        SKey.LIBRARY -> SKeyKind.ACTION
        SKey.SYNC_STATUS -> SKeyKind.INFO
        SKey.LICENSES -> SKeyKind.INFO
    }

/** 对话框里的候选项。 */
fun SKey.options(): List<String> = when (this) {
    SKey.THEME -> ThemeAccent.entries.map { it.label }
    SKey.DARK_MODE -> ThemeMode.entries.map { it.label }
    SKey.PAGE_DIR -> PageDirKind.entries.map { it.label }
    SKey.PAGE_ANIM -> PageAnimKind.entries.map { it.label }
    SKey.READ_BG -> ReadBgKind.entries.map { it.label }
    SKey.BOOK_SORT -> BookSort.entries.map { it.label }
    SKey.BOOK_GROUP -> BookGroup.entries.map { it.label }
    SKey.BOOK_LAYOUT -> BooksLayoutKind.entries.map { it.label }
    SKey.GLASS_LEVEL -> GlassLevel.entries.map { it.label }
    else -> emptyList()
}

fun SKey.currentIndex(s: AppSettings): Int = when (this) {
    SKey.THEME -> s.accent
    SKey.DARK_MODE -> s.darkMode
    SKey.PAGE_DIR -> s.pageDir
    SKey.PAGE_ANIM -> s.pageAnim
    SKey.READ_BG -> s.readBg
    SKey.BOOK_SORT -> s.bookSort
    SKey.BOOK_GROUP -> s.bookGroup
    SKey.BOOK_LAYOUT -> s.bookLayout
    SKey.GLASS_LEVEL -> s.glassLevel
    else -> -1
}

/** 选中第 [i] 项之后的新设置。 */
fun SKey.select(s: AppSettings, i: Int): AppSettings = when (this) {
    SKey.THEME -> s.copy(accent = i)
    SKey.DARK_MODE -> s.copy(darkMode = i)
    SKey.PAGE_DIR -> s.copy(pageDir = i)
    SKey.PAGE_ANIM -> s.copy(pageAnim = i)
    SKey.READ_BG -> s.copy(readBg = i)
    SKey.BOOK_SORT -> s.copy(bookSort = i)
    SKey.BOOK_GROUP -> s.copy(bookGroup = i)
    SKey.BOOK_LAYOUT -> s.copy(bookLayout = i)
    SKey.GLASS_LEVEL -> s.copy(glassLevel = i)
    else -> s
}

/** 开关行翻转。 */
fun SKey.flip(s: AppSettings): AppSettings = when (this) {
    SKey.DUAL_PAGE -> s.copy(dualPage = !s.dualPage)
    SKey.SYNC_ENABLED -> s.copy(syncEnabled = !s.syncEnabled)
    SKey.KEEP_AWAKE -> s.copy(keepScreenOn = !s.keepScreenOn)
    SKey.DYNAMIC_COLOR -> s.copy(dynamicColor = !s.dynamicColor)
    SKey.HIGH_QUALITY -> s.copy(highQuality = !s.highQuality)
    SKey.BANGUMI_ENABLED -> s.copy(bangumiEnabled = !s.bangumiEnabled)
    else -> s
}

/** 文本行的当前值 / 写入。 */
fun SKey.text(s: AppSettings): String = when (this) {
    SKey.WEBDAV_URL -> s.webdavUrl
    SKey.WEBDAV_USER -> s.webdavUser
    SKey.WEBDAV_PASS -> s.webdavPass
    SKey.WEBDAV_ROOT -> s.webdavRoot
    SKey.BANGUMI_BASE -> s.bangumiBase
    SKey.SYNC_DIR -> s.syncDir
    else -> ""
}

fun SKey.withText(s: AppSettings, v: String): AppSettings = when (this) {
    SKey.WEBDAV_URL -> s.copy(webdavUrl = v.trim())
    SKey.WEBDAV_USER -> s.copy(webdavUser = v.trim())
    SKey.WEBDAV_PASS -> s.copy(webdavPass = v)
    SKey.WEBDAV_ROOT -> s.copy(webdavRoot = v.trim().ifBlank { "/" })
    SKey.BANGUMI_BASE -> s.copy(bangumiBase = v.trim().trimEnd('/'))
    // 同步目录：允许 /Document/同步 或 Document/同步，存的时候统一去掉两端斜杠
    SKey.SYNC_DIR -> s.copy(syncDir = v.trim().trim('/'))
    else -> s
}

/** 一行要显示成什么样。空着的字段就是这行不画那个东西。 */
class RowView(
    val value: String? = null,
    val toggle: Boolean? = null,
    val seg: List<String>? = null,
    val segIndex: Int = 0,
    val swatch: Color? = null,
    val ramp: List<Color>? = null,
    val rampIndex: Int = 0,
    /** 密码这类要打码 */
    val masked: Boolean = false
)

fun viewOf(
    key: SKey,
    s: AppSettings,
    davStatus: String,
    /** 隐私空间里存了几本 —— 这个值只有运行时知道 */
    privacyCount: Int = 0
): RowView = when (key) {
    SKey.THEME -> RowView(swatch = s.accentColor)
    SKey.DARK_MODE -> RowView(value = s.themeMode.label)
    SKey.DYNAMIC_COLOR -> RowView(toggle = s.dynamicColor)
    SKey.GLASS_LEVEL -> RowView(value = s.glassLevelKind.label)

    SKey.LIBRARY -> RowView(
        value = when {
            s.accounts.isEmpty() -> "未配置"
            s.accounts.size == 1 -> s.accounts.first().name
            else -> "${s.accounts.size} 台"
        }
    )

    SKey.WEBDAV_URL -> RowView(value = s.webdavUrl.ifBlank { "未设置" })
    SKey.WEBDAV_USER -> RowView(value = s.webdavUser.ifBlank { "未设置" })
    SKey.WEBDAV_PASS -> RowView(
        value = if (s.webdavPass.isBlank()) "未设置" else "••••••",
        masked = s.webdavPass.isNotBlank()
    )
    SKey.WEBDAV_ROOT -> RowView(value = s.webdavRoot.ifBlank { "/" })
    SKey.WEBDAV_TEST -> RowView(value = davStatus)

    // 真实大小由 SettingsPage 用运行时算出来的 cacheSize 覆盖
    SKey.CACHE -> RowView(value = "计算中…")
    // 同上，真实占用由 SettingsPage 注入
    SKey.DOWNLOADS -> RowView(value = null)
    // 当前版本 / 检查结果 / 下载进度，都由 SettingsPage 运行时注入
    SKey.UPDATE -> RowView(value = null)

    SKey.BOOK_SORT -> RowView(value = BookSort.of(s.bookSort).label)
    SKey.BOOK_GROUP -> RowView(value = BookGroup.of(s.bookGroup).label)
    SKey.BOOK_LAYOUT -> RowView(value = BooksLayoutKind.of(s.bookLayout).label)
    SKey.PRIVACY -> RowView(value = if (privacyCount > 0) "$privacyCount 本" else "未使用")

    SKey.PAGE_DIR -> RowView(value = s.pageDirKind.label)
    SKey.PAGE_ANIM -> RowView(value = s.pageAnimKind.label)
    SKey.DUAL_PAGE -> RowView(toggle = s.dualPage)
    SKey.KEEP_AWAKE -> RowView(toggle = s.keepScreenOn)
    SKey.HIGH_QUALITY -> RowView(toggle = s.highQuality)

    SKey.READ_BG -> RowView(
        ramp = ReadBgKind.entries.map { it.solid },
        rampIndex = s.readBg
    )

    SKey.BANGUMI_ENABLED -> RowView(toggle = s.bangumiEnabled)
    SKey.BANGUMI_BASE -> RowView(value = s.bangumiBase.ifBlank { "官方（留空）" })
    SKey.BANGUMI_CLEAR -> RowView(value = null)

    SKey.SYNC_ENABLED -> RowView(toggle = s.syncEnabled)
    SKey.SYNC_DIR -> RowView(value = s.syncDir.ifBlank { "服务器根目录" })
    SKey.SYNC_PUSH -> RowView(value = null)
    SKey.SYNC_PULL -> RowView(value = null)
    // 上次同步时间是运行时读的，由 SettingsPage 注入
    SKey.SYNC_STATUS -> RowView(value = null)

    SKey.LICENSES -> RowView(value = null)
}

sealed interface SItem {
    data class Section(val title: String) : SItem

    /** [key] 为空 = 纯展示行，点了没反应。 */
    data class Row(
        val label: String,
        val key: SKey? = null,
        val value: String? = null,
        val height: Float = 50f
    ) : SItem

    /** v26 根页的分类入口：点进去换一页，右侧显示当前值摘要。 */
    data class Nav(val label: String, val page: SettingsPageId) : SItem
}

object SettingsSpec {
    /** 根页顺序：用得最多的排前面。 */
    val ROOT: List<SettingsPageId> = listOf(
        SettingsPageId.APPEARANCE,
        SettingsPageId.READING,
        SettingsPageId.SHELF,
        SettingsPageId.SERVER,
        SettingsPageId.SYNC,
        SettingsPageId.META,
        SettingsPageId.STORAGE
    )

    /** 某个二级页里的行。分成几段（Section）就渲染成几张卡片。 */
    fun items(page: SettingsPageId): List<SItem> = when (page) {
        SettingsPageId.ROOT -> ROOT.map { SItem.Nav(it.title, it) }

        SettingsPageId.APPEARANCE -> listOf(
            SItem.Row("主题色", key = SKey.THEME),
            SItem.Row("深浅模式", key = SKey.DARK_MODE),
            SItem.Row("跟随系统取色", key = SKey.DYNAMIC_COLOR), // 开关
            // v97：FlClash 那个「液态玻璃」三档，放外观页最末 ——
            // 它只影响底栏材质，不动任何行为。
            SItem.Row("液态玻璃", key = SKey.GLASS_LEVEL)
        )

        // 阅读这一页是「真会改变阅读器行为」的全集，一个假开关都不留：
        //  - 翻页方向：上下滚动 = 条漫；新书没有单独记录时就用它（v26 修）
        //  - 翻页动画 / 双页显示：只对分页模式有效，标签里写清楚
        SettingsPageId.READING -> listOf(
            SItem.Row("翻页方向", key = SKey.PAGE_DIR),
            SItem.Row("翻页动画（分页时）", key = SKey.PAGE_ANIM),
            SItem.Row("双页显示（横屏）", key = SKey.DUAL_PAGE),
            SItem.Row("画质优先", key = SKey.HIGH_QUALITY),
            SItem.Row("保持屏幕常亮", key = SKey.KEEP_AWAKE),
            SItem.Section("背景"),
            SItem.Row("阅读背景", key = SKey.READ_BG, height = 140f)
        )

        SettingsPageId.SHELF -> listOf(
            SItem.Row("排序方式", key = SKey.BOOK_SORT),
            SItem.Row("分组方式", key = SKey.BOOK_GROUP),
            SItem.Row("书架布局", key = SKey.BOOK_LAYOUT),
            SItem.Section("隐私"),
            SItem.Row("隐私空间", key = SKey.PRIVACY)
        )

        // v98：原来的「服务器 / 账号 / 密码 / 根路径」四行文本全撤掉 ——
        // 多账号之后在这里改某一行会直接和资源库里的卡片打架。
        // 现在只留一个入口，具体配置统一去资源库页改。
        SettingsPageId.SERVER -> listOf(
            SItem.Row("资源库", key = SKey.LIBRARY),
            SItem.Row("说明", value = "WebDAV / Komga 都在资源库里添加"),
            SItem.Section("连通性"),
            SItem.Row("测试当前 WebDAV", key = SKey.WEBDAV_TEST)
        )

        SettingsPageId.META -> listOf(
            SItem.Row("显示元数据", key = SKey.BANGUMI_ENABLED),
            SItem.Row("接口地址", key = SKey.BANGUMI_BASE),
            SItem.Section("维护"),
            SItem.Row("清除元数据缓存", key = SKey.BANGUMI_CLEAR)
        )

        SettingsPageId.SYNC -> listOf(
            SItem.Row("自动同步", key = SKey.SYNC_ENABLED),
            SItem.Row("同步目录", key = SKey.SYNC_DIR),
            // v30：设置也进了同步范围，这一行是给用户看的说明（纯展示，点了没反应）
            SItem.Row("同步内容", value = "书架 · 进度 · 设置"),
            SItem.Section("手动"),
            SItem.Row("上传同步（本机覆盖云端）", key = SKey.SYNC_PUSH),
            SItem.Row("下载同步（云端覆盖本机）", key = SKey.SYNC_PULL),
            SItem.Row("状态", key = SKey.SYNC_STATUS)
        )

        SettingsPageId.STORAGE -> listOf(
            SItem.Row("离线下载（点击清空）", key = SKey.DOWNLOADS),
            SItem.Row("缓存（点击清空）", key = SKey.CACHE),
            // v83：和 Flutter 版同一套 —— 清单放服务器的 updates/ 目录，
            // 下载后由系统安装器确认，App 不能静默替换自己。
            SItem.Section("应用更新"),
            SItem.Row("检查更新", key = SKey.UPDATE),
            SItem.Section("关于"),
            SItem.Row("版本", value = com.mangadock.android.BuildConfig.VERSION_NAME),
            SItem.Row("开源许可", key = SKey.LICENSES)
        )
    }

    /** 根页每个分类右侧那句摘要。 */
    fun summary(
        page: SettingsPageId,
        s: AppSettings,
        cacheSize: String,
        davStatus: String
    ): String = when (page) {
        SettingsPageId.APPEARANCE ->
            if (s.dynamicColor) "${s.themeMode.label} · 跟随取色"
            else "${s.themeMode.label} · ${ThemeAccent.of(s.accent).label}"

        SettingsPageId.READING ->
            if (s.pageDirKind == PageDirKind.VERTICAL) "条漫 · 上下滚动"
            else "${s.pageDirKind.label} · ${s.pageAnimKind.label}"

        SettingsPageId.SHELF -> "${BookSort.of(s.bookSort).label} · ${BookGroup.of(s.bookGroup).label}"

        SettingsPageId.SERVER -> when {
            s.accounts.isEmpty() -> "未配置"
            s.accounts.size == 1 -> s.accounts.first().kind.label
            else -> "${s.accounts.size} 台 · ${s.accounts.first().kind.label} 等"
        }

        SettingsPageId.SYNC -> when {
            !s.syncEnabled -> "已关闭"
            s.syncDir.isBlank() -> "已开启 · 根目录"
            else -> "已开启 · ${s.syncDir}"
        }

        SettingsPageId.META -> if (s.bangumiEnabled) "已开启" else "已关闭"

        SettingsPageId.STORAGE -> cacheSize.ifBlank { davStatus }

        SettingsPageId.ROOT -> ""
    }
}
