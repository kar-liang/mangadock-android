package com.mangadock.android.bangumi

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mangadock.android.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "MangaDock"

/**
 * 一条 Bangumi 元数据。只读，不做收藏 / 进度同步（那要 OAuth + 注册应用）。
 * 对齐 iOS `Net/BangumiClient.swift` 的 `BangumiSubject`。
 */
data class BangumiSubject(
    val id: Int,
    val name: String,
    val nameCn: String?,
    val summary: String?,
    val date: String?,
    val coverUrl: String?,
    /** v25：候选列表用的缩略图（small/grid），比 medium 省一个量级的流量。 */
    val thumbUrl: String?,
    val score: Double?,
    val ratingTotal: Int?,
    /** 「漫画」/「小说」这类，搜索响应里有，详情响应里也常有。 */
    val platform: String?,
    val tags: List<String>,
    val author: String?
) {
    /** 中文名优先，没有就退回原名。 */
    val displayTitle: String get() = nameCn?.takeIf { it.isNotBlank() } ?: name
}

/**
 * 条目下的一个角色。对齐 iOS `BangumiClient.swift` 的 `BangumiCharacter`。
 * 只用于详情页角色栏，不做声优关联。
 */
data class BangumiCharacter(
    val id: Int,
    val name: String,
    /** 「主角」/「配角」/「客串」，API 直接给中文。 */
    val relation: String?,
    val portraitUrl: String?
)

/**
 * Bangumi v0 API 客户端（v23）。
 *
 * ## 关于接口地址
 * `*.bgm.tv` 在部分线路上被 DNS 污染，所以接口地址可配（[AppSettings.bangumiBase]），
 * 留空走官方 `https://api.bgm.tv/v0`。v27 起**不再内置代理** —— 自建接口是直连的，
 * 再挂一层代理反而会把内网/自建地址绕死。
 *
 * ## 加载顺序（项目硬约定）
 * 磁盘 → 网络；**缓存先上屏，后台刷新**。搜索只给进入组合树的可见详情页发，
 * [resolveLock] 串行，绝不批量预取。一次网络失败冷却 5 分钟，避免每次进页面干等超时。
 */
object BangumiClient {

    const val DEFAULT_BASE = "https://api.bgm.tv/v0"

    private const val UA = "MangaDock/2.0.25-android.1 (https://github.com/kar-liang/MangaDock)"
    private const val JSON_TYPE = "application/json; charset=utf-8"
    private const val COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAP_PREFS = "mangadock.bangumi.map"
    /** 详情卡封面最大解码宽，别把 800px 原图整张摊在内存里。 */
    private const val IMG_TARGET_W = 400

    /** 只有一个 client：不再按代理地址分桶。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val resolveLock = Mutex()

    @Volatile
    private var lastFailure = 0L

    private fun shouldSkipForNow(): Boolean =
        lastFailure > 0L && System.currentTimeMillis() - lastFailure < COOLDOWN_MS

    /** 书架补匹配用：正在冷却就别排队，否则整页书各排一次、每次都要等超时。 */
    fun isCoolingDown(): Boolean = shouldSkipForNow()

    /**
     * 手动刷新时清掉失败冷却。
     *
     * 这就是「封面必须重启 app 才出来」的真凶：`lastFailure` 只在内存里，
     * 断网 / 接口抽风一次就会冷却 5 分钟，这段时间里书架怎么刷都补不上封面，
     * 而杀进程重进恰好把这半分钟的记忆清零了 —— 看起来就像「只有重启才生效」。
     * 用户主动点了刷新，就该按用户的意思立刻重试。
     */
    fun resetCooldown() {
        lastFailure = 0L
    }

    private fun base(settings: AppSettings): String =
        settings.bangumiBase.trim().trimEnd('/').ifBlank { DEFAULT_BASE }

    // ------------------------------------------------------------ transport

    /** 同步请求，调用方必须已经在 IO 线程。非 2xx / 异常都记一次失败冷却。 */
    private fun http(url: String, settings: AppSettings, body: String? = null): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)   // 不带自定义 UA 会被 403
            .header("Accept", "application/json")
        if (body != null) req.post(body.toRequestBody(JSON_TYPE.toMediaType()))
        return runCatching {
            httpClient.newCall(req.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    lastFailure = System.currentTimeMillis()
                    Log.w(TAG, "BGM HTTP ${r.code} $url")
                    null
                } else {
                    lastFailure = 0L
                    r.body?.string()
                }
            }
        }.onFailure {
            lastFailure = System.currentTimeMillis()
            Log.w(TAG, "BGM fail $url: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun bytes(url: String, settings: AppSettings): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        httpClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }.getOrNull()

    // --------------------------------------------------------------- parse

    private fun parseSubject(o: JSONObject): BangumiSubject {
        val images = o.optJSONObject("images")
        var coverUrl: String? = null
        for (k in arrayOf("medium", "common", "large", "small", "grid")) {
            val v = images?.optString(k).orEmpty()
            if (v.startsWith("http")) {
                coverUrl = v
                break
            }
        }
        // 缩略图优先 200px 的 small / grid，列表里没必要拉 800px 原图
        var thumbUrl: String? = null
        for (k in arrayOf("small", "grid", "common", "medium")) {
            val v = images?.optString(k).orEmpty()
            if (v.startsWith("http")) {
                thumbUrl = v
                break
            }
        }

        val rating = o.optJSONObject("rating")
        val score = rating?.optDouble("score", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 }
        val total = rating?.optInt("total", 0)?.takeIf { it > 0 }

        val tagArr = o.optJSONArray("tags")
        val tags = ArrayList<Pair<String, Int>>()
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                val t = tagArr.optJSONObject(i) ?: continue
                val n = t.optString("name")
                if (n.isNotBlank()) tags += n to t.optInt("count", 0)
            }
        }
        val topTags = tags.sortedByDescending { it.second }.take(4).map { it.first }

        return BangumiSubject(
            id = o.optInt("id"),
            name = o.optString("name"),
            nameCn = o.optString("name_cn").takeIf { it.isNotBlank() },
            summary = o.optString("summary").takeIf { it.isNotBlank() },
            date = o.optString("date").takeIf { it.isNotBlank() },
            coverUrl = coverUrl,
            thumbUrl = thumbUrl,
            score = score,
            ratingTotal = total,
            platform = o.optString("platform").takeIf { it.isNotBlank() },
            tags = topTags,
            author = infoValue(o, "作者", "原作")
        )
    }

    /** `infobox[].value` 可能是 String，也可能是 `[{v,k}]`，两种都得吃。 */
    private fun infoValue(o: JSONObject, vararg keys: String): String? {
        val arr = o.optJSONArray("infobox") ?: return null
        for (k in keys) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optString("key") != k) continue
                val text = when (val v = e.opt("value")) {
                    is String -> v
                    is JSONArray -> {
                        val parts = ArrayList<String>()
                        for (j in 0 until v.length()) {
                            val item = v.optJSONObject(j) ?: continue
                            val p = item.optString("v").ifBlank { item.optString("k") }
                            if (p.isNotBlank()) parts += p
                        }
                        parts.joinToString(" / ")
                    }
                    else -> ""
                }.trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    /** 去空白 / 标点 / 符号，只留字母数字（中文、假名都算 letter）。 */
    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** 宁可不显示也不要张冠李戴：只在归一化后互相包含时才自动采用。 */
    private fun matches(query: String, s: BangumiSubject): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return false
        return listOf(s.displayTitle, s.name).any {
            val c = normalize(it)
            c.isNotEmpty() && (c == q || c.contains(q) || q.contains(c))
        }
    }

    // --------------------------------------------------------------- cache

    private fun dir(ctx: Context): File =
        File(ctx.cacheDir, "bangumi").apply { if (!exists()) mkdirs() }

    private fun detailFile(ctx: Context, id: Int) = File(dir(ctx), "$id.json")

    private fun imgDir(ctx: Context): File =
        File(dir(ctx), "img").apply { if (!exists()) mkdirs() }

    /** 稳定文件名：和 CoverStore 一样用 MD5，绝不用带随机种子的 hash。 */
    private fun md5(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun mapSp(ctx: Context) =
        ctx.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)

    /** null = 没找过；-1 = 找过但没可信匹配；>0 = subject id。 */
    private fun linked(ctx: Context, key: String): Int? {
        val sp = mapSp(ctx)
        return if (sp.contains(key)) sp.getInt(key, -1) else null
    }

    private fun putLink(ctx: Context, key: String, id: Int) {
        mapSp(ctx).edit().putInt(key, id).apply()
    }

    /** 清详情 / 图片磁盘缓存，映射保留（对齐 iOS 的「清除元数据缓存」）。 */
    fun clearCache(ctx: Context) {
        val d = File(ctx.cacheDir, "bangumi")
        // 只清内容、保留根目录：握着 File 引用的地方下次写缓存才不会静默失败
        runCatching { d.listFiles()?.forEach { it.deleteRecursively() } }
    }

    // ------------------------------------------------------------- lookup

    /**
     * null = 网络/解析失败（不能写「放弃匹配」标记，否则恢复网络后这本书永远不再重试）；
     * 空列表 = 请求成功但确实没有候选。两者必须分开。
     */
    private fun search(name: String, settings: AppSettings, limit: Int = 6): List<BangumiSubject>? {
        // type 1 = 书籍，漫画归这里
        val body = JSONObject()
            .put("keyword", name)
            .put("filter", JSONObject().put("type", JSONArray().put(1)))
            .toString()
        val raw = http("${base(settings)}/search/subjects?limit=$limit", settings, body)
            ?: return null
        val arr = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
            ?: return null
        val out = ArrayList<BangumiSubject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = runCatching { parseSubject(o) }.getOrNull() ?: continue
            if (s.id > 0 && s.name.isNotBlank()) out += s
        }
        return out
    }

    /** 搜索响应没有 rating / infobox，命中后必须再拉一次详情。 */
    private suspend fun detail(
        ctx: Context,
        id: Int,
        settings: AppSettings
    ): BangumiSubject? = withContext(Dispatchers.IO) {
        val f = detailFile(ctx, id)
        if (f.isFile && f.length() > 0) {
            val cached = runCatching { parseSubject(JSONObject(f.readText())) }.getOrNull()
            if (cached != null) return@withContext cached
            f.delete()
        }
        val raw = http("${base(settings)}/subjects/$id", settings)
            ?: return@withContext null
        val subject = runCatching { parseSubject(JSONObject(raw)) }.getOrNull()
            ?: return@withContext null
        runCatching { f.writeText(raw) }
        subject
    }

    /**
     * 给一本漫画取元数据。缓存命中直接返回；没匹配上返回 null，
     * 详情页静默降级（不摆 loading、不摆按了没反应的按钮）。
     */
    suspend fun subject(
        ctx: Context,
        title: String,
        seriesKey: String,
        settings: AppSettings
    ): BangumiSubject? = withContext(Dispatchers.IO) {
        if (!settings.bangumiEnabled || shouldSkipForNow()) return@withContext null
        resolveLock.withLock {
            when (val prev = linked(ctx, seriesKey)) {
                null -> {
                    val results = search(title, settings)
                    if (results == null) {
                        // 只是这条线路连不上：不写映射，冷却过后自动重试
                        Log.i(TAG, "BGM lookup failed (network): $title")
                        null
                    } else {
                        val found = results.firstOrNull()?.takeIf { matches(title, it) }
                        if (found == null) {
                            // 请求成功但确实没有可信匹配，才记「找过但没对上」
                            putLink(ctx, seriesKey, -1)
                            Log.i(TAG, "BGM give up: $title")
                            null
                        } else {
                            putLink(ctx, seriesKey, found.id)
                            Log.i(TAG, "BGM match: $title -> ${found.id}")
                            detail(ctx, found.id, settings)
                        }
                    }
                }
                else -> if (prev <= 0) null else detail(ctx, prev, settings)
            }
        }
    }

    /**
     * v25：书架封面用。**只读本地**（映射 + 详情缓存），不发任何搜索请求。
     *
     * 书架首次组合时如果每本书都去搜一次，等于整页预取，会踩性能红线；
     * 搜索交给 [BangumiClient.subject] 在后台串行补（见 bookshelf/BangumiCoverBackfill）。
     * 调用方必须已经在 IO 线程（CoverStore 就在 IO 里调）。
     */
    fun cachedSubject(ctx: Context, seriesKey: String): BangumiSubject? {
        val id = linked(ctx, seriesKey) ?: return null
        if (id <= 0) return null
        val f = detailFile(ctx, id)
        if (!f.isFile || f.length() <= 0L) return null
        return runCatching { parseSubject(JSONObject(f.readText())) }.getOrNull()
    }

    private fun charsFile(ctx: Context, id: Int) = File(dir(ctx), "$id.characters.json")

    private fun parseCharacters(raw: String): List<BangumiCharacter>? {
        // 官方是裸数组；兼容个别反代包一层 {"data":[...]}
        val arr = runCatching {
            if (raw.trimStart().startsWith("[")) JSONArray(raw)
            else JSONObject(raw).optJSONArray("data")
        }.getOrNull() ?: return null

        val out = ArrayList<BangumiCharacter>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id")
            val name = o.optString("name").trim()
            if (id <= 0 || name.isEmpty()) continue
            val img = o.optJSONObject("images")
            var portrait: String? = null
            // v88：回到 iOS 版 `BangumiCharacter.portraitURL` 的顺序
            //（medium → large → small → grid），也就是**高分辨率原图**。
            // v87 曾改成优先 grid 方图来「直接拿到脸」，但 grid 只有 100px，
            // 放到 64dp 的框里被拉糊（老大 22:46 反馈）。
            // 真正让脸露出来的是显示端的顶部对齐裁剪，不是换小图：
            // iOS 用 .aspectRatio(.fill) + .frame(72x96, alignment: .top)，
            // 这里对应 ContentScale.Crop + Alignment.TopCenter。
            for (k in arrayOf("medium", "large", "small", "grid")) {
                val v = img?.optString(k).orEmpty()
                if (v.startsWith("http")) {
                    portrait = v
                    break
                }
            }
            out += BangumiCharacter(
                id = id,
                name = name,
                relation = o.optString("relation").takeIf { it.isNotBlank() },
                portraitUrl = portrait
            )
        }
        return out
    }

    /**
     * 角色栏数据（v25）。缓存优先；失败返回空列表，UI 整段不显示 ——
     * 角色是锦上添花，不能挡住章节列表。网络刚失败时不再干等超时。
     */
    suspend fun characters(
        ctx: Context,
        subjectId: Int,
        settings: AppSettings
    ): List<BangumiCharacter> = withContext(Dispatchers.IO) {
        if (!settings.bangumiEnabled || subjectId <= 0) return@withContext emptyList()
        val f = charsFile(ctx, subjectId)
        if (f.isFile && f.length() > 0L) {
            val hit = runCatching { parseCharacters(f.readText()) }.getOrNull()
            if (hit != null) return@withContext hit
            f.delete()
        }
        if (shouldSkipForNow()) return@withContext emptyList()
        val raw = http("${base(settings)}/subjects/$subjectId/characters", settings)
            ?: return@withContext emptyList()
        val list = runCatching { parseCharacters(raw) }.getOrNull()
            ?: return@withContext emptyList()
        if (list.isNotEmpty()) runCatching { f.writeText(raw) }
        list
    }

    // ------------------------------------------------------- manual link

    /**
     * 手动搜索候选（对齐 iOS `candidates(for:)`）：**不做「名字必须对得上」的自动过滤**，
     * 把 12 条结果原样交给用户挑。null = 网络/解析失败，空列表 = 确实没结果。
     */
    suspend fun candidates(
        ctx: Context,
        keyword: String,
        settings: AppSettings
    ): List<BangumiSubject>? = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isEmpty()) return@withContext emptyList()
        search(q, settings, limit = 12)
    }

    /**
     * 手动指定条目：写映射（覆盖「找过但没对上」的 -1），并冲掉该 id 的旧详情缓存，
     * 强制按当前接口地址重新拉一次详情。手动操作后清失败冷却，别让 5 分钟窗口挡住重试。
     */
    fun link(ctx: Context, seriesKey: String, id: Int) {
        putLink(ctx, seriesKey, id)
        runCatching { detailFile(ctx, id).delete() }
        lastFailure = 0L
    }

    /** 当前映射：null 没找过 / -1 明确不关联 / >0 已关联的 subject id。UI 靠它决定给哪些入口。 */
    fun mapping(ctx: Context, seriesKey: String): Int? = linked(ctx, seriesKey)

    /**
     * 恢复自动匹配（对齐 iOS `unlink`）：直接删映射，下次进详情页重新走搜索。
     * 注意删了以后自动匹配可能又贴回同一条 —— 那正是「恢复自动」的语义，
     * 想彻底不显示元数据请用 [mute]。
     */
    fun unlink(ctx: Context, seriesKey: String) {
        mapSp(ctx).edit().remove(seriesKey).apply()
        lastFailure = 0L
    }

    /**
     * 取消关联：写 -1 保持卡片干净且不重搜。不能直接删映射，否则自动匹配会把
     * 同一条（用户刚说「对不上」的那条）立刻贴回来，按钮看着像没反应。
     */
    fun mute(ctx: Context, seriesKey: String) {
        putLink(ctx, seriesKey, -1)
        lastFailure = 0L
    }

    /**
     * 元数据卡片 / 候选列表的封面图，走同一个代理 client + 磁盘缓存。
     *
     * [targetW] 只影响解码尺寸，缓存文件名仍然按 URL 走 —— 所以调用方对同一个 URL
     * 要始终传同一个宽度，否则会在同一个文件上反复解码不同尺寸（内容本身不变）。
     */
    suspend fun image(
        ctx: Context,
        url: String,
        settings: AppSettings,
        targetW: Int = IMG_TARGET_W
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val f = File(imgDir(ctx), md5(url) + ".img")
        if (f.isFile && f.length() > 0) {
            val hit = runCatching { decode(f.readBytes(), targetW) }.getOrNull()
            if (hit != null) return@withContext hit
            f.delete()
        }
        val data = bytes(url, settings) ?: return@withContext null
        runCatching { f.writeBytes(data) }
        decode(data, targetW)
    }

    private fun decode(data: ByteArray, targetW: Int = IMG_TARGET_W): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= targetW && sample < 8) {
            sample *= 2
        }
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size, opt)?.asImageBitmap()
        }.getOrNull()
    }
}
