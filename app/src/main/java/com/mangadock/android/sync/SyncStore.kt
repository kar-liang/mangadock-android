package com.mangadock.android.sync

import android.content.Context
import com.mangadock.android.bookshelf.BookItem
import com.mangadock.android.bookshelf.BookshelfStore
import com.mangadock.android.reader.ProgressStore
import com.mangadock.android.reader.ReadingProgress
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.settings.SettingsStore
import com.mangadock.android.webdav.WebDavClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 多设备同步：和 iOS 共用**同一个** `.mangadock-sync.json`。
 *
 * ## 为什么必须逐字段对齐
 *
 * 这个文件 iOS 也在读。只要字段名或日期编码对不上，结果不是"报错"，而是
 * **另一台设备安静地把书架写坏**，所以这里不是随便定个格式：
 *
 * - 顶层键沿用 iOS `SyncPayload`：`serverURL / username / recent / shelf / settings / updatedAt`；
 * - `shelf` 条目 = `urlString / name / isDirectory / size / addedAt`；
 * - `recent` 条目 = `urlString / name / page / pageCount / updatedAt / isFolder`；
 * - **日期是秒**，而且起点是 2001-01-01（Apple 的 referenceDate），不是 1970。
 *   Swift 的 `JSONEncoder` 默认 `.deferredToDate` 就是这个口径；用 1970 会让
 *   "最后写入为准"整段错位，甚至把旧数据判成新的。
 *
 * ## 分工
 *
 * 只同步**书架 + 阅读进度**。`settings` 段 Android 一律不写也不读 ——
 * iOS 那边是 `appearance/readerMode/enhance/...` 这套枚举，和 Android 的整型下标
 * 语义不同，硬套会互相改对方的主题和翻页方向。设置各留各的，谁也不碰谁。
 */
object SyncStore {

    const val FILE_NAME = ".mangadock-sync.json"

    /** 1970 → 2001 的秒差。iOS 的 Date 是相对 2001-01-01 的秒数。 */
    private const val APPLE_EPOCH_OFFSET = 978307200.0

    private const val PREFS = "mangadock_sync"
    private const val K_UPDATED_AT = "updatedAt"
    private const val K_LAST_SYNC = "lastSync"

    data class Result(val ok: Boolean, val message: String)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastSyncAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_SYNC, 0L)

    fun lastSyncText(ctx: Context): String {
        val t = lastSyncAt(ctx)
        if (t <= 0L) return "还没同步过"
        val d = java.util.Date(t)
        val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return "上次同步 ${f.format(d)}"
    }

    /** 同步文件在服务器上的完整 URL；没配服务器 / 目录非法时返回 null。 */
    fun fileUrl(settings: AppSettings): String? {
        val base = WebDavClient.normalizeBase(settings.webdavUrl) ?: return null
        val dir = settings.syncDir.trim().trim('/')
        val path = if (dir.isEmpty()) "/$FILE_NAME" else "/$dir/$FILE_NAME"
        return WebDavClient.fileUrl(base, path)
    }

    private fun dirUrl(settings: AppSettings): String? {
        val base = WebDavClient.normalizeBase(settings.webdavUrl) ?: return null
        val dir = settings.syncDir.trim().trim('/')
        if (dir.isEmpty()) return null
        return WebDavClient.fileUrl(base, "/$dir")
    }

    // ------------------------------------------------------------ 打包

    /**
     * 服务器上那份文件里的 `settings` 段（iOS 专用）。
     *
     * 读不到就返回 null —— 第一次同步、断网、文件还没生成都属于正常情况，
     * 此时写空对象即可：iOS 那边 `applySettings` 只在键有值时才写，
     * 空对象不会清掉它的设置。
     */
    private suspend fun remoteSettings(url: String, auth: String?): JSONObject? =
        when (val blob = WebDavClient.readFile(url, auth)) {
            is WebDavClient.Blob.Ok -> runCatching {
                JSONObject(String(blob.bytes, Charsets.UTF_8)).optJSONObject("settings")
            }.getOrNull()

            else -> null
        }

    private fun shelfJson(base: String, shelf: List<BookItem>): JSONArray {
        val arr = JSONArray()
        shelf.forEach { b ->
            val url = WebDavClient.fileUrl(base, b.path) ?: return@forEach
            arr.put(
                JSONObject()
                    .put("urlString", url)
                    .put("name", b.title)
                    .put("isDirectory", !b.isCbz)
                    // Android 的书架没存文件大小，写 0：iOS 只拿它显示，不参与判断
                    .put("size", 0)
                    .put("addedAt", appleSeconds(b.addedAt))
            )
        }
        return arr
    }

    private fun recentJson(base: String, progress: Map<String, ReadingProgress>): JSONArray {
        val arr = JSONArray()
        progress.entries
            .sortedByDescending { it.value.updatedAt }
            .take(30)   // 和 iOS 的 recordPage 上限一致，别让文件无限长
            .forEach { (path, p) ->
                val url = WebDavClient.fileUrl(base, path) ?: return@forEach
                arr.put(
                    JSONObject()
                        .put("urlString", url)
                        .put("name", displayName(path))
                        .put("page", p.index)
                        .put("pageCount", p.total)
                        .put("updatedAt", appleSeconds(p.updatedAt))
                        .put("isFolder", !path.substringAfterLast('/').contains('.'))
                )
            }
        return arr
    }

    /** 文件名去掉扩展名，和 iOS 的 `displayName` 一致。 */
    private fun displayName(path: String): String {
        val name = path.trimEnd('/').substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ------------------------------------------------------------ 推 / 拉

    /** 自动推送：只在开关打开时做，失败静默（下次改动会重试）。 */
    suspend fun pushAuto(
        ctx: Context,
        settings: AppSettings,
        shelf: BookshelfStore,
        progress: ProgressStore
    ) {
        if (!settings.syncEnabled) return
        push(ctx, settings, shelf, progress)
    }

    /** 手动 / 自动都走这里。force=false 时仅用于自动路径，失败也不打扰用户。 */
    suspend fun push(
        ctx: Context,
        settings: AppSettings,
        shelf: BookshelfStore,
        progress: ProgressStore,
        manual: Boolean = false
    ): Result {
        if (!settings.webdavConfigured) return Result(false, "还没配置服务器")
        val base = WebDavClient.normalizeBase(settings.webdavUrl)
            ?: return Result(false, "服务器地址填得不对")
        val url = fileUrl(settings) ?: return Result(false, "同步目录填得不对")
        val auth = WebDavClient.authHeader(settings.webdavUser, settings.webdavPass)

        val now = System.currentTimeMillis()
        val androidSettings = SettingsStore(ctx).toJson(settings)
        val payload = JSONObject()
            .put("serverURL", base)
            .put("username", settings.webdavUser)
            .put("recent", recentJson(base, progress.all()))
            .put("shelf", shelfJson(base, shelf.all()))
            // iOS 那段 settings 只有它自己认识（appearance / readerMode 这套枚举字符串），
            // Android 既不用也不改写：上传前把服务器上原有的那一段原样搬过来，
            // 否则 Android 一写就把 iOS 的设置拍平 —— 换到 iOS 上拉一次设置就全没了。
            .put("settings", remoteSettings(url, auth) ?: JSONObject())
            // Android 自己的那一份（含 WebDAV 地址 / 账号 / Bangumi 接口 / 各项开关）。
            // iOS 的 SyncPayload 是 Codable，不认识的顶层键会被直接忽略，互不干扰。
            .put("android", androidSettings)
            .put("updatedAt", appleSeconds(now))

        // 自定义目录可能还不存在：PUT 到不存在的集合会被 409/405 拒掉。
        // ensureDir 现在会自己用 PROPFIND 验一遍，建不出来就当场停下，
        // 不再出现"提示上传成功、其实一个字都没写上去"。
        dirUrl(settings)?.let { d ->
            WebDavClient.ensureDir(d, auth)?.let { return Result(false, it) }
        }

        // 打两份出来：跨端同步出问题时，靠这几条日志能直接看到写上去的字段名、日期口径，
        // 以及设置到底带没带上（设置段很长，只看整份 payload 会被前面的 shelf 挤掉）
        android.util.Log.i(
            "MangaDock",
            "SYNC put $url shelf=${shelf.all().size} android=$androidSettings"
        )
        val err = WebDavClient.writeFile(url, auth, payload.toString().toByteArray())
        if (err != null) return Result(false, err)

        prefs(ctx).edit()
            .putLong(K_UPDATED_AT, now)
            .putLong(K_LAST_SYNC, now)
            .apply()
        return Result(
            true,
            "已上传：书架 ${shelf.all().size} 本 · 进度 ${progress.all().size} 条"
        )
    }

    /**
     * 拉取。[manual] = true 时无条件覆盖本机（用户点的，就别拿时间戳拦他）；
     * 自动路径只有云端更新才覆盖。
     */
    suspend fun pull(
        ctx: Context,
        settings: AppSettings,
        shelf: BookshelfStore,
        progress: ProgressStore,
        manual: Boolean = false
    ): Result {
        if (!settings.webdavConfigured) return Result(false, "还没配置服务器")
        val base = WebDavClient.normalizeBase(settings.webdavUrl)
            ?: return Result(false, "服务器地址填得不对")
        val url = fileUrl(settings) ?: return Result(false, "同步目录填得不对")
        val auth = WebDavClient.authHeader(settings.webdavUser, settings.webdavPass)

        val blob = WebDavClient.readFile(url, auth)
        when (blob) {
            is WebDavClient.Blob.Fail -> return Result(false, blob.message)
            WebDavClient.Blob.Missing -> return Result(
                false,
                if (manual) "服务器上还没有同步文件，先点「上传同步」"
                else "还没有云端同步文件"
            )
            is WebDavClient.Blob.Ok -> Unit
        }
        val bytes = (blob as WebDavClient.Blob.Ok).bytes

        android.util.Log.i(
            "MangaDock",
            "SYNC got ${bytes.size}B shelf=${runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("shelf")?.length() }.getOrNull()} " +
                "android=${runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("android") }.getOrNull()} " +
                String(bytes, Charsets.UTF_8).take(700)
        )
        val remote = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            ?: return Result(false, "服务器上的同步文件读不出来（可能已损坏）")

        // 别的服务器写的文件不许动本机数据
        val remoteServer = remote.optString("serverURL")
        if (remoteServer.isNotBlank() && !sameServer(remoteServer, base)) {
            return Result(false, "这份数据来自另一个服务器（${remote.optString("serverURL")}），已拒绝覆盖")
        }

        val remoteAt = appleMillis(remote.optDouble("updatedAt", 0.0))
        val localAt = prefs(ctx).getLong(K_UPDATED_AT, 0L)
        if (!manual && remoteAt <= localAt) {
            return Result(false, "云端不比本机新，跳过")
        }

        val shelfItems = parseShelf(base, remote.optJSONArray("shelf"))
        val recentMap = parseRecent(base, remote.optJSONArray("recent"))
        // 全空才拦：只有设置、没有书架的 payload 也是合法的（新装的设备就是这状态）
        if (shelfItems.isEmpty() && recentMap.isEmpty() && remote.optJSONObject("android") == null) {
            return Result(false, "云端文件是空的，没有覆盖本机")
        }

        // 覆盖前把差异打出来。这东西救过命：上次书架从 5 本变 3 本，
        // 因为没有这行日志，只能靠翻 prefs 猜是哪一步写的。
        val before = shelf.all().map { it.path }.toSet()
        val after = shelfItems.map { it.path }.toSet()
        val removed = before - after
        if (before != after) {
            android.util.Log.i(
                "MangaDock",
                "SYNC shelf local=${before.size} remote=${after.size} " +
                    "added=${(after - before).size} removed=$removed"
            )
        }

        shelf.replaceAll(shelfItems)
        progress.replaceAll(recentMap)

        // 设置也一起拉。只认 `android` 这一段；缺这一段就整段跳过 ——
        // 说明文件是 iOS 写的（它不转存别人的段），此时动 Android 设置就是瞎改。
        val appliedSettings = remote.optJSONObject("android")
            ?.let { runCatching { SettingsStore(ctx).applyJson(it) }.getOrNull() }

        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putLong(K_UPDATED_AT, remoteAt.coerceAtLeast(now))
            .putLong(K_LAST_SYNC, now)
            .apply()

        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(remoteAt))
        // 这个文件是 iOS / Android 共用的一份，语义是「后写的整份覆盖」。
        // 云端比本机少书时，本机那几本会被安静抹掉，所以必须在提示里说出来，
        // 否则用户只会觉得"书自己没了"。
        val tail = if (removed.isNotEmpty()) {
            "\n本机原有 ${before.size} 本，云端没有其中 ${removed.size} 本，已按云端为准。"
        } else ""
        val tailSet = if (appliedSettings != null) "\n设置也一起更新了。" else ""
        return Result(
            true,
            "已下载（云端 $stamp）：书架 ${shelfItems.size} 本 · 进度 ${recentMap.size} 条$tail$tailSet"
        )
    }

    // ------------------------------------------------------------ 解析

    private fun parseShelf(base: String, arr: JSONArray?): List<BookItem> {
        if (arr == null) return emptyList()
        val out = mutableListOf<BookItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = pathOf(base, o.optString("urlString")) ?: continue
            val isDir = o.optBoolean("isDirectory", true)
            val name = o.optString("name").ifBlank { displayName(path) }
            out += BookItem(
                key = path,
                title = if (isDir) name else displayName(name),
                path = path,
                isCbz = !isDir,
                addedAt = appleMillis(o.optDouble("addedAt", 0.0))
            )
        }
        return out
    }

    private fun parseRecent(base: String, arr: JSONArray?): Map<String, ReadingProgress> {
        if (arr == null) return emptyMap()
        val out = mutableMapOf<String, ReadingProgress>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = pathOf(base, o.optString("urlString")) ?: continue
            val total = o.optInt("pageCount", 0)
            if (total <= 0) continue
            out[path] = ReadingProgress(
                index = o.optInt("page", 0),
                total = total,
                updatedAt = appleMillis(o.optDouble("updatedAt", 0.0))
            )
        }
        return out
    }

    /** 绝对 URL → 站内路径。不在这个 base 下的（换过服务器）直接丢掉。 */
    private fun pathOf(base: String, url: String): String? {
        if (url.isBlank()) return null
        val prefix = base.trimEnd('/') + "/"
        if (!url.startsWith(prefix)) return null
        val rest = url.substring(prefix.length)
        return runCatching { URLDecoder.decode(rest, "UTF-8") }
            .getOrNull()
            ?.let { if (it.startsWith("/")) it.trimEnd('/') else "/" + it.trimEnd('/') }
    }

    // ------------------------------------------------------------ 时间

    /** 服务器地址比较：去协议、去尾斜杠、忽略大小写，和 iOS 的 normalizeServer 对齐。 */
    private fun sameServer(a: String?, b: String?): Boolean =
        normServer(a) == normServer(b)

    private fun normServer(raw: String?): String {
        var t = (raw ?: "").trim().lowercase()
        t = t.removePrefix("http://").removePrefix("https://")
        return t.trimEnd('/')
    }

    private fun appleSeconds(millis: Long): Double = millis / 1000.0 - APPLE_EPOCH_OFFSET

    private fun appleMillis(seconds: Double): Long =
        if (seconds <= 0.0) 0L
        else ((seconds + APPLE_EPOCH_OFFSET) * 1000).toLong()
}
