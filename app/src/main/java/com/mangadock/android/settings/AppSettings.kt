package com.mangadock.android.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * 全部可选项的枚举都放在这里，值是"序号"，存进 SharedPreferences 就是一个 Int。
 *
 * 加新选项时**只能往末尾加**，不能插队——不然老用户的序号会整体错位。
 *
 * v16 清死设置：玻璃材质整套下掉后，图标风格 / 语言 / 亮度跟随系统 / 仅 Wi-Fi /
 * 自动清理缓存 / 连接状态这些「点了没反应」的字段全部删了，连磁盘上的旧键也一起清。
 * 只留真正会影响行为的项。
 */

/** v21：深浅色。默认跟随系统，老用户不会突然看到界面变色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/** 主题色。 */
enum class ThemeAccent(val label: String, val color: Color) {
    BLUE("蓝色", Color(0xFF0A7CFF)),
    PURPLE("紫色", Color(0xFF7B5BFF)),
    PINK("粉色", Color(0xFFFF4F82)),
    ORANGE("橙色", Color(0xFFFF8A28)),
    GREEN("绿色", Color(0xFF2FBF5F)),
    GRAPHITE("石墨", Color(0xFF5B5B63));

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

enum class PageDirKind(val label: String) {
    RTL("从右往左"), LTR("从左往右"), VERTICAL("上下滚动");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/** 阅读背景预设。[solid] 给阅读器铺底，也拿来画设置里那条色带。 */
enum class ReadBgKind(val label: String, val solid: Color) {
    PAPER("纸白", Color(0xFFFFFFFF)),
    CREAM("米黄", Color(0xFFEFE6D6)),
    GRAY("深灰", Color(0xFF33333A)),
    BLACK("纯黑", Color(0xFF000000));

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * v19 分页模式的翻页动画。三种都只动 RenderNode 层，不自绘整页 Bitmap：
 *  - SMOOTH：换页时淡入 + 方向性位移（最省，默认）
 *  - FOLLOW：页面跟着手指走，松手越过阈值才翻过去
 *  - CURL：rotationY 卷页 + 明暗变化，仿真书页
 * 条漫模式是原生 LazyColumn，不受这个选项影响。
 */
enum class PageAnimKind(val label: String) {
    SMOOTH("平滑"), FOLLOW("跟手"), CURL("仿真");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * v97：底栏「液态玻璃」三档（对齐 FlClash 的设置项）。
 *
 * [NONE] 不是「把玻璃调淡」，而是**完全不跑 shader**：不透明胶囊 + 静态选中块，
 * 长按只剩整条底栏的弹性放大，没有折射气泡。跑 shader 再把它调到所谓的「弱」
 * 只会在边缘留一圈糊边，反而不如不做。
 *
 * [STANDARD] 就是 v81~v96 一直在用的那套参数，**一个数都没改** ——
 * 默认值必须是它，否则升级完底栏观感变了，又是一次「改得比原来丑」。
 *
 * [ULTRA] 只动三样肉眼能分辨的：更透（haze 低）、边缘挤得更狠（refraction 大）、
 * 气泡彩边更明显（dispersion 大）。bevel 不动 —— 胶囊 62dp 高，
 * `uBevel` 本来就被 shader 夹到 halfMinor*0.5 = 15.5dp，往上调是空转。
 */
enum class GlassLevel(val label: String, val desc: String) {
    NONE("无", "不透明胶囊，没有折射和气泡"),
    STANDARD("标准", "当前这套液态玻璃"),
    ULTRA("极致", "更通透，边缘折射与彩边更明显");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * v98：服务器的两种类型。
 *
 * 以前只有 WebDAV（一套扁平字段 `webdavUrl/User/Pass/Root`）。加 Komga 之后
 * 这两者是**并列的浏览来源**，不是「主 / 备」：WebDAV 是文件树，Komga 是
 * 已经整理好的漫画库（系列 → 单话），路径概念完全不同，所以只能分成两个类型。
 */
enum class ServerKind(val label: String, val desc: String) {
    WEBDAV("WebDAV", "任何 WebDAV 服务器，按文件夹浏览"),
    KOMGA("Komga", "Komga 漫画服务器，按系列 / 单话浏览");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * 一台配置好的服务器（v98 起）。
 *
 * [id] 一旦生成就**不许再变** —— 阅读进度、离线下载、目录缓存都按路径记账，
 * 而路径又挂在账号上；「编辑」要是换了 id，等于换了一台服务器，历史全断。
 *
 * [urls] 是**已经拼好的完整基址**列表，第一条是主地址，后面的都是备用
 * （家里内网 IP + 公网域名并行探测是这个 App 的核心用法，iOS 那份同样保留）。
 */
data class ServerAccount(
    val id: String,
    val kind: ServerKind,
    val name: String,
    /** 卡片上那行说明；留空就显示地址 */
    val note: String = "",
    val urls: List<String> = emptyList(),
    val user: String = "",
    val pass: String = "",
    /** 只有 WebDAV 用得上：漫画根路径 */
    val root: String = "/"
) {
    val primary: String get() = urls.firstOrNull().orEmpty()

    /** 卡片副标题：备注优先，其次第一条地址（去掉协议头，省得一行放不下） */
    val subtitle: String
        get() = note.ifBlank { primary.substringAfter("://").ifBlank { "未填地址" } }

    /** 这台服务器上的图片要不要认证头（Komga 的封面 / 页面都必须带）。 */
    val authHeader: String?
        get() = com.mangadock.android.webdav.WebDavClient.authHeader(user, pass)
}

/**
 * 全局设置。**所有字段都给默认值**，`copy` 改起来方便，也省得漏参数。
 */
data class AppSettings(
    val accent: Int = 0,

    // ---- v21 主题：深浅色 + 动态取色 ----
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val darkMode: Int = 0,
    /** Android 12+ 从壁纸取色；关掉就用上面那 6 个手动主题色 */
    val dynamicColor: Boolean = false,
    /**
     * v97：底栏液态玻璃档位。0 无 / 1 标准 / 2 极致。
     * 默认 **1（标准）= v96 的参数原样**，不动老用户的观感。
     */
    val glassLevel: Int = 1,

    // 默认「从左往右」：库里基本是国漫，日漫可以在设置里切回从右往左
    val pageDir: Int = 1,
    val dualPage: Boolean = true,
    val keepScreenOn: Boolean = false,
    val readBg: Int = 0,
    /** v19：分页翻页动画，默认平滑 */
    val pageAnim: Int = 0,
    /**
     * v20：画质优先。关闭时解码宽度只保证 ≥ 屏宽（最省内存）；
     * 打开后目标宽翻倍，超分过的图能保留更多细节，代价是单页位图内存约 ×4。
     */
    val highQuality: Boolean = false,

    // ---- v98 资源库：多台服务器 ----
    /**
     * 配置好的服务器列表，WebDAV 和 Komga 混在一起存 ——
     * 卡片墙本来就是混排的，分两张表只会在「哪台是当前」上多一层不一致。
     */
    val accounts: List<ServerAccount> = emptyList(),
    /** 当前选中的那台（点开卡片就会切到它） */
    val activeId: String = "",

    // ---- WebDAV：书架的数据来源 ----
    /**
     * ⚠️ 这四个是**派生字段**，别直接改。
     *
     * v98 之前它们是唯一的服务器配置；现在真正的来源是 [accounts]，
     * 这里只是把「当前 WebDAV 账号」摊平出来，好让 [webdav.WebDavClient] /
     * [sync.SyncStore] / [update.Updater] 这些按老签名写的调用方一行都不用改。
     *
     * 一致性由 [withAccounts] 负责：任何改动账号列表的地方都必须经过它，
     * 它会顺手把这四个字段算好。
     */
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPass: String = "",
    val webdavRoot: String = "/",

    // ---- 书架：排序 / 分组存序号，老用户没这两个键时默认「最近加入 + 不分组」 ----
    val bookSort: Int = 0,
    val bookGroup: Int = 0,
    /** v31：书架网格 / 列表，默认网格（和之前一致） */
    val bookLayout: Int = 0,

    // ---- v23 Bangumi 元数据 ----
    /** 关掉后详情页不显示信息卡，也不发任何请求。 */
    val bangumiEnabled: Boolean = true,
    /** 留空走官方 https://api.bgm.tv/v0；可填自建接口（如 https://bgm.example.com/v0）。 */
    val bangumiBase: String = "",

    // ---- v29 同步（和 iOS 共用 .mangadock-sync.json）----
    /** 默认开，跟 iOS 一致；关掉后完全不动网络。 */
    val syncEnabled: Boolean = true,
    /** 同步文件放在服务器哪个目录；留空 = 根目录。 */
    val syncDir: String = ""
) {
    val accentColor: Color get() = ThemeAccent.of(accent).color
    val pageDirKind: PageDirKind get() = PageDirKind.of(pageDir)
    val readBgKind: ReadBgKind get() = ReadBgKind.of(readBg)
    val pageAnimKind: PageAnimKind get() = PageAnimKind.of(pageAnim)
    val themeMode: ThemeMode get() = ThemeMode.of(darkMode)
    val glassLevelKind: GlassLevel get() = GlassLevel.of(glassLevel)

    /** 地址 + 账号都填了才算"配置过"，能不能连上另说。 */
    val webdavConfigured: Boolean get() = webdavUrl.isNotBlank()

    /** 点开的那台（可能是 WebDAV，也可能是 Komga）。 */
    val activeAccount: ServerAccount?
        get() = accounts.firstOrNull { it.id == activeId }

    /**
     * 书架 / 同步 / 更新用的那台 WebDAV。
     *
     * 当前选中的是 Komga 时**回落到第一台 WebDAV** —— 否则「点开 Komga 卡片」
     * 会顺手把书架和同步弄断，那是莫名其妙的行为。
     */
    val webdavAccount: ServerAccount?
        get() = accounts.firstOrNull { it.id == activeId && it.kind == ServerKind.WEBDAV }
            ?: accounts.firstOrNull { it.kind == ServerKind.WEBDAV }

    /** Komga 的那台；当前选中的是 Komga 就用它，否则用第一台。 */
    val komgaAccount: ServerAccount?
        get() = accounts.firstOrNull { it.id == activeId && it.kind == ServerKind.KOMGA }
            ?: accounts.firstOrNull { it.kind == ServerKind.KOMGA }

    val hasAnyServer: Boolean get() = accounts.isNotEmpty()
}

/**
 * v98：改账号列表的**唯一入口**。
 *
 * 它会顺手把 [AppSettings.activeId] 和四个派生的 `webdav*` 字段算好 ——
 * 所以界面上只要 `onChange(settings.withAccounts(newList))` 就行，
 * 不用记得「还要同步更新 webdavUrl」。
 *
 * 两条兜底规则：
 *  - [active] 传的 id 已经不在列表里（刚被删掉）→ 落到第一台；
 *  - 当前选中的不是 WebDAV → 派生字段取第一台 WebDAV，见 [AppSettings.webdavAccount]。
 */
fun AppSettings.withAccounts(
    list: List<ServerAccount>,
    active: String = activeId
): AppSettings {
    val act = when {
        list.any { it.id == active } -> active
        else -> list.firstOrNull()?.id.orEmpty()
    }
    val probe = copy(accounts = list, activeId = act)
    val dav = probe.webdavAccount
    return probe.copy(
        webdavUrl = dav?.primary.orEmpty(),
        webdavUser = dav?.user.orEmpty(),
        webdavPass = dav?.pass.orEmpty(),
        webdavRoot = dav?.root?.ifBlank { "/" } ?: "/"
    )
}

/** 新账号的 id。8 位够用，也不会把 SharedPreferences 撑大。 */
fun newServerId(): String = java.util.UUID.randomUUID().toString().take(8)

/**
 * 落盘。用 SharedPreferences 而不是 DataStore：这堆东西全是标量，
 * 读一次就够，不值得为它引一个 kotlinx-coroutines 的依赖。
 */
class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("mangadock.settings", Context.MODE_PRIVATE)

    private companion object {
        /** v97 那套扁平字段迁过来的那台服务器，id 固定，每次启动算出来都一样。 */
        const val LEGACY_ID = "legacy"
    }

    /** v15 及之前留下的死键。加载配置时顺手抹掉，别在磁盘上留一排没用的开关。 */
    private val legacyKeys = listOf(
        "dockGlass", "topBar", "glassFollowSystem", "iconStyle",
        "language", "wifiOnly", "autoCleanCache",
        "brightnessFollowSystem", "webdavConnected"
    )

    init {
        if (legacyKeys.any { sp.contains(it) }) {
            sp.edit().apply { legacyKeys.forEach { remove(it) } }.apply()
        }
    }

    fun load(): AppSettings = AppSettings(
        accent = sp.getInt("accent", 0),
        darkMode = sp.getInt("darkMode", 0),
        dynamicColor = sp.getBoolean("dynamicColor", false),
        glassLevel = sp.getInt("glassLevel", 1),
        pageDir = sp.getInt("pageDir", 1),
        dualPage = sp.getBoolean("dualPage", true),
        keepScreenOn = sp.getBoolean("keepScreenOn", false),
        readBg = sp.getInt("readBg", 0),
        pageAnim = sp.getInt("pageAnim", 0),
        highQuality = sp.getBoolean("highQuality", false),
        accounts = readAccounts(),
        activeId = sp.getString("activeServerId", "").orEmpty(),
        webdavUrl = sp.getString("webdavUrl", "").orEmpty(),
        webdavUser = sp.getString("webdavUser", "").orEmpty(),
        webdavPass = sp.getString("webdavPass", "").orEmpty(),
        webdavRoot = sp.getString("webdavRoot", "/").orEmpty(),
        bookSort = sp.getInt("bookSort", 0),
        bookGroup = sp.getInt("bookGroup", 0),
        bookLayout = sp.getInt("bookLayout", 0),
        bangumiEnabled = sp.getBoolean("bangumiEnabled", true),
        bangumiBase = sp.getString("bangumiBase", "").orEmpty(),
        syncEnabled = sp.getBoolean("syncEnabled", true),
        syncDir = sp.getString("syncDir", "").orEmpty()
    ).migrated()

    /**
     * v98 迁移：v97 及以前只有一套扁平 WebDAV 字段。
     *
     * 读到 accounts 是空、但 `webdavUrl` 有值时，就地合成一张卡片 ——
     * **不写盘**，`id` 用固定的 `"legacy"`，所以每次启动算出来的都是同一台，
     * 阅读进度那些按路径记的东西不会因为「换了个随机 id」而错位。
     * 用户第一次动设置时才会被 `save()` 落成正式数据。
     */
    private fun AppSettings.migrated(): AppSettings {
        if (accounts.isNotEmpty() || webdavUrl.isBlank()) {
            // 没有账号时也要保证 activeId 干净，免得指着一个不存在的 id
            return if (accounts.any { it.id == activeId }) this else withAccounts(accounts)
        }
        val one = ServerAccount(
            id = LEGACY_ID,
            kind = ServerKind.WEBDAV,
            // 只留主机名：老配置的地址是完整 URL，直接截出来会带端口，
            // 名字一长卡片上就要折两行，而且和下面那行副标题完全重复。
            name = webdavUrl.substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .ifBlank { "WebDAV" },
            urls = listOf(webdavUrl),
            user = webdavUser,
            pass = webdavPass,
            root = webdavRoot.ifBlank { "/" }
        )
        return withAccounts(listOf(one), LEGACY_ID)
    }

    fun save(s: AppSettings) {
        sp.edit()
            .putInt("accent", s.accent)
            .putInt("darkMode", s.darkMode)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putInt("glassLevel", s.glassLevel)
            .putInt("pageDir", s.pageDir)
            .putBoolean("dualPage", s.dualPage)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putInt("readBg", s.readBg)
            .putInt("pageAnim", s.pageAnim)
            .putBoolean("highQuality", s.highQuality)
            .putString("accounts", writeAccounts(s.accounts))
            .putString("activeServerId", s.activeId)
            .putString("webdavUrl", s.webdavUrl)
            .putString("webdavUser", s.webdavUser)
            .putString("webdavPass", s.webdavPass)
            .putString("webdavRoot", s.webdavRoot)
            .putInt("bookSort", s.bookSort)
            .putInt("bookGroup", s.bookGroup)
            .putInt("bookLayout", s.bookLayout)
            .putBoolean("bangumiEnabled", s.bangumiEnabled)
            .putString("bangumiBase", s.bangumiBase)
            .putBoolean("syncEnabled", s.syncEnabled)
            .putString("syncDir", s.syncDir)
            .apply()
    }

    // ------------------------------------------------- v98 账号列表（JSON 数组）

    private fun readAccounts(): List<ServerAccount> {
        val raw = sp.getString("accounts", "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val kind = ServerKind.of(o.optInt("kind", 0))
                val urls = o.optJSONArray("urls")?.let { ua ->
                    (0 until ua.length()).map { ua.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
                ServerAccount(
                    id = id,
                    kind = kind,
                    name = o.optString("name").ifBlank { kind.label },
                    note = o.optString("note"),
                    urls = urls,
                    user = o.optString("user"),
                    pass = o.optString("pass"),
                    root = o.optString("root", "/").ifBlank { "/" }
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun writeAccounts(list: List<ServerAccount>): String {
        val arr = org.json.JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("kind", a.kind.ordinal)
                    .put("name", a.name)
                    .put("note", a.note)
                    .put("urls", org.json.JSONArray(a.urls))
                    .put("user", a.user)
                    .put("pass", a.pass)
                    .put("root", a.root)
            )
        }
        return arr.toString()
    }

    // ------------------------------------------------------ v30 同步用 JSON

    /**
     * 整份设置 → JSON。键名直接用 prefs 的键名，中间不再造一套别名 ——
     * 以后加字段只要两边各加一行，不会出现"JSON 里叫 A、磁盘上叫 B"的错位。
     *
     * **只给同步文件里 `android` 那一段用**。iOS 的 `settings` 段是
     * `appearance / readerMode / ...` 这套枚举字符串，和这里的整型下标语义不同，
     * 所以两边各占一段，谁也不解释对方那段。
     */
    fun toJson(s: AppSettings): JSONObject = JSONObject()
        .put("accent", s.accent)
        .put("darkMode", s.darkMode)
        .put("dynamicColor", s.dynamicColor)
        .put("pageDir", s.pageDir)
        .put("dualPage", s.dualPage)
        .put("keepScreenOn", s.keepScreenOn)
        .put("readBg", s.readBg)
        .put("pageAnim", s.pageAnim)
        .put("highQuality", s.highQuality)
        // v98：资源库整份带过去。不然换台机器同步下来只剩一堆设置，
        // 服务器还得手输一遍 —— 那同步就没意义了。password 本来就在这一段里，同一棵树。
        .put("accounts", writeAccounts(s.accounts))
        .put("activeServerId", s.activeId)
        .put("webdavUrl", s.webdavUrl)
        .put("webdavUser", s.webdavUser)
        // 同步文件在用户自己的服务器上、走同一套 WebDAV 认证；不写密码的话
        // "换台手机登录一下"就还是得手输一次，等于没同步。
        .put("webdavPass", s.webdavPass)
        .put("webdavRoot", s.webdavRoot)
        .put("bookSort", s.bookSort)
        .put("bookGroup", s.bookGroup)
        .put("bookLayout", s.bookLayout)
        .put("bangumiEnabled", s.bangumiEnabled)
        .put("bangumiBase", s.bangumiBase)
        .put("syncEnabled", s.syncEnabled)
        .put("syncDir", s.syncDir)

    /**
     * 把云端那份设置合并进来，返回合并后的结果。
     *
     * **只覆盖 JSON 里真的出现过的键**：缺键 = "这份 payload 没带这一项"，
     * 不是"把它清回默认值"。iOS 那段 `settings` 是同一个道理 ——
     * 谁都不是对方设置的权威来源，缺了就当没说过。
     */
    fun applyJson(o: JSONObject): AppSettings {
        val e = sp.edit()
        fun i(k: String) { if (o.has(k)) e.putInt(k, o.optInt(k, 0)) }
        fun b(k: String) { if (o.has(k)) e.putBoolean(k, o.optBoolean(k, false)) }
        fun s(k: String) { if (o.has(k)) e.putString(k, o.optString(k, "")) }

        i("accent"); i("darkMode"); b("dynamicColor")
        i("pageDir"); b("dualPage"); b("keepScreenOn")
        i("readBg"); i("pageAnim"); b("highQuality")
        s("accounts"); s("activeServerId")
        s("webdavUrl"); s("webdavUser"); s("webdavPass"); s("webdavRoot")
        i("bookSort"); i("bookGroup"); i("bookLayout")
        b("bangumiEnabled"); s("bangumiBase")
        b("syncEnabled"); s("syncDir")
        e.apply()
        return load()
    }
}
