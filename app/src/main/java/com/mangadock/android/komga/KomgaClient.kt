package com.mangadock.android.komga

import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 一个够用的 Komga 客户端（REST API v1）。
 *
 * 只做四件事，够浏览和阅读就够了：
 *   1. `GET /api/v1/series`          系列列表（网格首页）
 *   2. `GET /api/v1/series/{id}/books` 某系列下的单话
 *   3. `GET /api/v1/books/{id}/pages/{n}` 页面图片（1 起）
 *   4. `GET /api/v1/series/{id}/thumbnail` 系列封面
 *
 * ## 认证
 *
 * 走 **HTTP Basic**（Komga 对 API 和 OPDS 都收 Basic）。这样图片也能复用
 * [com.mangadock.android.reader.ReaderPageLoader] —— 它本来就收一个
 * `Authorization` 头的字符串，不用为了 Komga 另起一套带头的图片加载器。
 * （API Key 走 `X-API-Key` 头，那就得改加载器签名，暂时不值当。）
 *
 * ## 为什么要 `contentNegotiation=…`
 *
 * 不要。页面直接 `GET …/pages/{n}`，Komga 会把原始文件吐回来（JPEG/PNG/WebP），
 * BitmapFactory 全能解。加转换参数反而多一轮服务器编码，还慢。
 */
object KomgaClient {

    /** 结果包装。失败一律带**人话**，不要往上抛异常 —— 界面只显示这串字。 */
    sealed interface Res<out T> {
        data class Ok<T>(val v: T) : Res<T>
        data class Fail(val message: String) : Res<Nothing>
    }

    /** 系列（= 一部漫画）。 */
    data class Series(
        val id: String,
        val title: String,
        val booksCount: Int,
        /** 封面地址；拿不到就空串，界面退回首字色块 */
        val thumbUrl: String
    )

    /** 单话。 */
    data class Book(
        val id: String,
        val title: String,
        val pages: Int
    )

    // ------------------------------------------------------------------ 基础

    /**
     * 补协议、去尾部斜杠。填 `192.168.1.10:25600` 也认。
     *
     * 和 [WebDavClient.normalizeBase] 同样的处理，但**不拼任何路径** ——
     * Komga 的接口都在 `/api/v1` 下，用户填的就是站点根。
     */
    fun normalizeBase(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        s = s.trimEnd('/')
        return s.toHttpUrlOrNull()?.toString()?.trimEnd('/')
    }

    fun authHeader(user: String, pass: String): String? = WebDavClient.authHeader(user, pass)

    private fun url(base: String, vararg seg: String, query: Map<String, String> = emptyMap()): String? {
        val root = base.trimEnd('/')
        val b = (root + "/api/v1/" + seg.joinToString("/")).toHttpUrlOrNull() ?: return null
        val u = b.newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return u.toString()
    }

    /**
     * 发一次 GET 拿 JSON。
     *
     * [timeout] 用的是 [WebDavClient] 那个共享 client（连 8s / 读 15s），
     * 连接池也就一份，省得 Komga 和 WebDAV 各养一个。
     */
    private suspend fun getJson(target: String, auth: String?): Res<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(target)
                    .apply { auth?.let { header("Authorization", it) } }
                    .header("Accept", "application/json")
                    .build()
                WebDavClient.httpClient().newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext Res.Fail(text(resp.code, body))
                    Res.Ok(JSONObject(body))
                }
            } catch (t: Throwable) {
                Res.Fail(text(t))
            }
        }

    /** HTTP 码 → 人话。Komga 的 401 最常见（密码填错 / 没填）。 */
    private fun text(code: Int, body: String): String = when (code) {
        401 -> "账号或密码不对（401）"
        403 -> "这个账号没有权限访问库（403）"
        404 -> "接口不存在（404）：确认地址填的是 Komga 站点根，不是某个库的链接"
        in 500..599 -> "服务器出错了（$code）"
        else -> "请求失败（$code）${body.take(80).takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
    }

    private fun text(t: Throwable): String = when (t) {
        is UnknownHostException -> "找不到这台服务器，检查地址"
        is ConnectException -> "连不上：端口没开或者不在这张网里"
        is SocketTimeoutException -> "连接超时"
        is IOException -> "网络错误：${t.message ?: "未知"}"
        else -> "出错：${t.message ?: t.javaClass.simpleName}"
    }

    // ------------------------------------------------------------------ 接口

    /**
     * 探活。用 `/api/v1/users/me` —— 它同时验证「地址对不对」和「账号密码对不对」，
     * 比看首页 HTML 靠谱。返回 `名字（角色）` 给设置页显示。
     */
    suspend fun ping(base: String, auth: String?): Res<String> {
        val u = url(base, "users", "me") ?: return Res.Fail("地址拼不出 URL")
        return when (val r = getJson(u, auth)) {
            is Res.Fail -> r
            is Res.Ok -> {
                val o = r.v
                val mail = o.optString("email")
                val roles = o.optJSONArray("roles")?.let { a ->
                    (0 until a.length()).joinToString("/") { a.optString(it) }
                }.orEmpty()
                Res.Ok(listOf(mail, roles).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "已连接" })
            }
        }
    }

    /**
     * 系列列表（一次拉 200 个，接着翻页）。
     *
     * [page] 从 0 开始 —— Komga 是 0 基的，和界面上的「第几页」不是一回事。
     */
    suspend fun series(
        base: String,
        auth: String?,
        page: Int = 0,
        size: Int = 200,
        search: String = ""
    ): Res<List<Series>> {
        val q = mutableMapOf(
            "page" to page.toString(),
            "size" to size.toString(),
            "sort" to "metadata.titleSort,asc"
        )
        if (search.isNotBlank()) q["search"] = search
        val u = url(base, "series", query = q) ?: return Res.Fail("地址拼不出 URL")
        return when (val r = getJson(u, auth)) {
            is Res.Fail -> r
            is Res.Ok -> {
                val arr = r.v.optJSONArray("content") ?: return Res.Ok(emptyList())
                val baseClean = base.trimEnd('/')
                val out = ArrayList<Series>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    val meta = o.optJSONObject("metadata")
                    // metadata.title 是用户整理过的标题，name 是文件夹名。前者优先。
                    val title = meta?.optString("title").orEmpty().ifBlank { o.optString("name") }
                    out += Series(
                        id = id,
                        title = title.ifBlank { "未命名" },
                        booksCount = o.optInt("booksCount", 0),
                        thumbUrl = "$baseClean/api/v1/series/$id/thumbnail"
                    )
                }
                Res.Ok(out)
            }
        }
    }

    /** 某系列下的单话，按卷/话号自然序。 */
    suspend fun books(
        base: String,
        auth: String?,
        seriesId: String,
        size: Int = 500
    ): Res<List<Book>> {
        val u = url(
            base, "series", seriesId, "books",
            query = mapOf(
                "page" to "0",
                "size" to size.toString(),
                "sort" to "metadata.numberSort,asc"
            )
        ) ?: return Res.Fail("地址拼不出 URL")
        return when (val r = getJson(u, auth)) {
            is Res.Fail -> r
            is Res.Ok -> {
                val arr = r.v.optJSONArray("content") ?: return Res.Ok(emptyList())
                val out = ArrayList<Book>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    val media = o.optJSONObject("media")
                    val meta = o.optJSONObject("metadata")
                    val title = meta?.optString("title").orEmpty()
                        .ifBlank { o.optString("name") }
                        .ifBlank { meta?.optString("number").orEmpty() }
                    out += Book(
                        id = id,
                        title = title.ifBlank { "第 ${i + 1} 话" },
                        pages = media?.optInt("pagesCount", 0) ?: 0
                    )
                }
                // 页数为 0 的话 = 这本书还没分析完 / 是纯 PDF 特殊包，读不了，滤掉
                Res.Ok(out.filter { it.pages > 0 })
            }
        }
    }

    /**
     * 一本书的页面 URL 列表（**1 起**）。
     *
     * 页数在书目列表里就有（`media.pagesCount`），所以这里不用再打一次
     * `/books/{id}/pages` —— 少一次往返，进阅读器瞬间就有总页数，
     * 「读到第几页」也不会先显示成 1/0。
     */
    fun pageUrls(base: String, bookId: String, count: Int): List<String> {
        val root = base.trimEnd('/')
        return (1..count).map { n -> "$root/api/v1/books/$bookId/pages/$n" }
    }
}
