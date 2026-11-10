package com.mangadock.android.webdav

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 目录里的一项。 */
data class DavEntry(
    /** 显示用的名字（优先用服务器给的 displayname） */
    val name: String,
    /** 相对 WebDAV 根路径的绝对路径，以 / 开头，不带末尾斜杠 */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val mtime: String = ""
)

sealed interface DavResult {
    data class Ok(val entries: List<DavEntry>) : DavResult
    data class Fail(val message: String) : DavResult
}

/** 一次 HTTP Range 请求的结果。[total] 是整个文件的长度，从 Content-Range 里取。 */
sealed interface RangeResult {
    data class Ok(val bytes: ByteArray, val start: Long, val total: Long) : RangeResult

    /** 服务器忽略了 Range（返回 200 整包），调用方该退回整包下载。 */
    data object Unsupported : RangeResult

    data class Fail(val message: String) : RangeResult
}

/**
 * 一个够用的 WebDAV 客户端：PROPFIND Depth:1 列一层 + HTTP Range 取字节区间。
 *
 * 必须用 OkHttp，不能用 HttpURLConnection——后者有 HTTP 方法白名单，
 * `setRequestMethod("PROPFIND")` 会直接抛 ProtocolException。
 */
object WebDavClient {

    @Volatile
    var trustSelfSigned: Boolean = true

    /** 共用一个 client，连接池才有意义。后面拉图片 / 取 Range 也走它。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 放行自签证书的 client。只给自用服务器用。 */
    private val relaxedClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val XML_TYPE = "application/xml; charset=utf-8".toMediaType()
    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

    /** 给图片加载 / Range 请求复用，别另起一个连接池。 */
    fun httpClient(): OkHttpClient = if (trustSelfSigned) relaxedClient else client

    /** 账号为空就不带 Authorization。 */
    fun authHeader(user: String, pass: String): String? =
        if (user.isBlank()) null else Credentials.basic(user, pass)

    /** 单个文件的完整 URL（不带末尾斜杠）。 */
    fun fileUrl(rawBase: String, path: String): String? {
        val base = normalizeBase(rawBase) ?: return null
        val sub = if (path.startsWith("/")) path.trimEnd('/') else "/$path".trimEnd('/')
        return base + encodePathSegments(sub)
    }

    private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""

    /** 补协议、去尾部斜杠。填 "192.168.1.10:5005/dav" 也能认。 */
    fun normalizeBase(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        s = s.trimEnd('/')
        return try {
            val u = URL(s)
            if (u.host.isNullOrBlank()) null else s
        } catch (t: Throwable) {
            null
        }
    }

    /** baseUrl 里那段路径（比如 /dav），用来把服务器返回的 href 换成相对路径。 */
    private fun basePathOf(base: String): String = try {
        URL(base).path.orEmpty().trimEnd('/')
    } catch (t: Throwable) {
        ""
    }

    private fun encodePathSegments(p: String): String =
        p.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) "" else android.net.Uri.encode(seg)
        }

    /**
     * 有限次重试。只重试 IO 类错误（超时 / 连接被重置），
     * DNS 和证书错误属于配置问题，重试一百次也不会好，直接抛。
     */
    private fun <T> withRetry(times: Int = 2, block: () -> T): T {
        var last: IOException? = null
        repeat(times) { i ->
            try {
                return block()
            } catch (e: IOException) {
                last = e
                if (i == times - 1 || e is UnknownHostException || e is SSLException) throw e
                try {
                    Thread.sleep(200L * (i + 1))
                } catch (_: InterruptedException) {
                    throw e
                }
            }
        }
        throw last ?: IOException("retry failed")
    }

    /** 把 IO 异常翻译成人话，顺带把「已重试」这件事说清楚。 */
    private fun ioMessage(e: IOException): String = when (e) {
        is UnknownHostException ->
            "找不到这台机器：${e.message}\n检查 IP 或域名；只解析到 IPv6 的外网域名在纯 IPv4 模拟器上会失败。"

        is ConnectException ->
            "连不上：${e.message}\n端口不对，或者手机和服务器不在同一个网络。"

        is SocketTimeoutException ->
            "连接/读取超时：服务器响应慢或网络不稳。\n已自动重试，检查 NAS 负载或换个网络。"

        is SSLException ->
            "HTTPS 握手失败：${e.message}\n" +
                "自签名证书要确认「接受自签名证书」是开的；也可以把证书装进系统信任。"

        else -> "网络错误：${e.message}"
    }

    suspend fun list(
        rawBase: String,
        user: String,
        pass: String,
        path: String
    ): DavResult = withContext(Dispatchers.IO) {
        val base = normalizeBase(rawBase)
            ?: return@withContext DavResult.Fail(
                "服务器地址填得不对。\n例：http://192.168.1.10:5005/dav"
            )

        val sub = if (path.startsWith("/")) path.trimEnd('/') else "/$path".trimEnd('/')
        val suffix = if (sub.isEmpty()) "/" else encodePathSegments(sub) + "/"
        val full = base + suffix

        val req = Request.Builder()
            .url(full)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_TYPE))
            .header("Depth", "1")
            .header("Accept", "application/xml, text/xml, */*")
            .apply {
                if (user.isNotBlank()) header("Authorization", Credentials.basic(user, pass))
            }
            .build()

        try {
            val t0 = System.currentTimeMillis()
            withRetry {
                // 必须走 httpClient()：trustSelfSigned 开着时它才放行自签证书。
                // 早期这里误用了私有 client，导致「列目录」严格校验、下载却放行。
                httpClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val detail = try {
                            resp.body?.string()?.take(600).orEmpty()
                        } catch (t: Throwable) {
                            ""
                        }
                        android.util.Log.i(
                            "MangaDock",
                            "DAV list ${sub.ifEmpty { "/" }} -> HTTP ${resp.code} " +
                                "${System.currentTimeMillis() - t0}ms"
                        )
                        DavResult.Fail(mapHttpError(resp.code, detail, base, sub))
                    } else {
                        val stream = resp.body?.byteStream()
                        if (stream == null) DavResult.Fail("服务器返回了空响应。")
                        else DavResult.Ok(stream.use { parse(it, basePathOf(base), sub) }).also {
                            // 「文件页到底有没有重新拉」这种事只能靠日志确认，
                            // 光看界面分不出"用的缓存"和"刚拉回来的"。
                            android.util.Log.i(
                                "MangaDock",
                                "DAV list ${sub.ifEmpty { "/" }} -> " +
                                    "${(it as DavResult.Ok).entries.size} 项 " +
                                    "${System.currentTimeMillis() - t0}ms"
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            DavResult.Fail(ioMessage(e))
        } catch (e: Throwable) {
            DavResult.Fail("请求失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 取 [start]..[end]（含两端）这段字节。CBZ 随机读用。 */
    suspend fun fetchRange(
        url: String,
        auth: String?,
        start: Long,
        end: Long
    ): RangeResult = executeRange(url, auth, "bytes=$start-$end", start)

    /** 取文件末尾 [n] 个字节，用来找 ZIP 的 EOCD。 */
    suspend fun fetchTail(url: String, auth: String?, n: Int = 65536): RangeResult =
        executeRange(url, auth, "bytes=-$n", -1L)

    private suspend fun executeRange(
        url: String,
        auth: String?,
        rangeHeader: String,
        requestedStart: Long
    ): RangeResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .apply { auth?.let { header("Authorization", it) } }
            .build()

        try {
            withRetry {
                httpClient().newCall(req).execute().use { resp ->
                    when {
                        resp.code == 206 -> {
                            val cr = CONTENT_RANGE.find(resp.header("Content-Range").orEmpty())
                            val s = cr?.groupValues?.getOrNull(1)?.toLongOrNull() ?: requestedStart
                            val total = cr?.groupValues?.getOrNull(3)?.toLongOrNull()
                            val bytes = resp.body?.bytes()
                            if (bytes == null) {
                                RangeResult.Fail("Range 响应没有内容。")
                            } else {
                                RangeResult.Ok(
                                    bytes = bytes,
                                    start = if (s < 0) total?.minus(bytes.size) ?: 0L else s,
                                    total = total ?: (s + bytes.size)
                                )
                            }
                        }

                        // 服务器忽略 Range，把整个文件塞回来了。不读 body，直接让调用方兜底。
                        resp.code == 200 -> RangeResult.Unsupported

                        else -> RangeResult.Fail("读取压缩包失败：服务器返回 ${resp.code}。")
                    }
                }
            }
        } catch (e: IOException) {
            RangeResult.Fail(ioMessage(e))
        } catch (e: Throwable) {
            RangeResult.Fail("Range 请求失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 把 HTTP 状态码翻译成"人话"。401 和 404 是用户最常踩的两个，
     * 直接把常见原因写出来，省得来回猜。
     */
    private fun mapHttpError(code: Int, detail: String, base: String, sub: String): String =
        when (code) {
            401 -> "认证失败（401）：账号或密码不对。\n群晖 / 飞牛的 WebDAV 一般要单独开一个应用密码，不是登录密码。"
            403 -> "被拒绝（403）：这个账号没有访问该目录的权限。"
            404 -> if (sub.isEmpty()) {
                "路径不存在（404）：地址结尾的 WebDAV 路径不对。\n现在请求的是 $base\n" +
                        "常见的是 /dav 或 /webdav，先去浏览器里确认一下。"
            } else {
                "这个目录不在了（404）：$sub\n可能被改名或删掉了，返回上一级看看。"
            }

            405 -> "这个地址不支持 WebDAV（405）。可能填的是网页端地址，不是 WebDAV 端点。"
            301, 302, 307, 308 -> "服务器要求跳转（$code）。把地址改成跳转后的最终地址再试。"
            500 -> "服务器内部错误（500）。${detail.take(120)}"
            else -> "服务器返回 $code。${detail.take(160)}"
        }

    /**
     * 解析 multistatus。只认 localName，不认 namespace 前缀——
     * 各家 NAS 给的前缀五花八门（D:、d:、lp1:、ns0:）。
     */
    private fun parse(input: InputStream, basePath: String, selfSub: String): List<DavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

        val out = ArrayList<DavEntry>()
        var href = ""
        var isDir = false
        var size = 0L
        var mtime = ""
        var display = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> {
                        href = ""; isDir = false; size = 0L; mtime = ""; display = ""
                    }

                    "href" -> href = parser.nextText()

                    "collection" -> isDir = true

                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L

                    "getlastmodified" -> mtime = parser.nextText()

                    "displayname" -> display = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "response") {
                val decoded = android.net.Uri.decode(href)
                if (decoded.isNotEmpty()) {
                    var rel = decoded
                    if (basePath.isNotEmpty() && rel.startsWith(basePath)) {
                        rel = rel.substring(basePath.length)
                    }
                    val relTrim = rel.trimEnd('/')
                    val self = if (selfSub.isEmpty()) "/" else selfSub
                    // 列表里会带一条"目录自己"，跳过
                    if (relTrim != self.trimEnd('/')) {
                        val dir = isDir || decoded.endsWith("/")
                        val name = display.trim().ifEmpty {
                            relTrim.substringAfterLast('/')
                        }
                        if (name.isNotEmpty()) {
                            out += DavEntry(
                                name = name,
                                path = relTrim.ifEmpty { "/" },
                                isDir = dir,
                                size = size,
                                mtime = mtime
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        // 文件夹排前面，同类按名字
        out.sortWith(
            compareBy<DavEntry> { if (it.isDir) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        return out
    }

    // ---------------------------------------------------- 读写整份文件（v29 同步用）

    /** 读文件的结果。404 单独拿出来：第一次同步时"服务器上没有"是正常状态，不是错误。 */
    sealed interface Blob {
        data class Ok(val bytes: ByteArray) : Blob

        data object Missing : Blob

        data class Fail(val message: String) : Blob
    }

    suspend fun readFile(url: String, auth: String?): Blob = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        try {
            withRetry {
                httpClient().newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> Blob.Missing
                        !resp.isSuccessful -> Blob.Fail("读取失败：HTTP ${resp.code}")
                        else -> resp.body?.bytes()?.let { Blob.Ok(it) }
                            ?: Blob.Fail("服务器返回了空响应。")
                    }
                }
            }
        } catch (e: IOException) {
            Blob.Fail(ioMessage(e))
        } catch (e: Throwable) {
            Blob.Fail("读取失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 整包下载到本地文件（应用内更新用）。返回 null = 成功，否则是给人看的错误。
     *
     * 直接落到调用方给的 [dest]（约定是 `.part` 临时文件），**不在这里改名** ——
     * 「校验通过才 rename」是调用方的事，中途失败留下半包也不能顶替正式文件名。
     *
     * [onProgress] 在 IO 线程被调用（read/total，total 可能为 0 = 服务器没给长度）。
     * 读不了 Content-Length 的服务器上就只能显示"下载中"，不要去猜百分比。
     */
    suspend fun downloadFile(
        url: String,
        auth: String?,
        dest: java.io.File,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }
    ): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        try {
            withRetry {
                httpClient().newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 -> return@use "认证失败（401）：账号或密码不对。"
                        resp.code == 403 -> return@use "被拒绝（403）：这个账号不能读更新目录。"
                        resp.code == 404 -> return@use "服务器上找不到这个安装包（404）。"
                        !resp.isSuccessful -> return@use "下载失败：HTTP ${resp.code}"
                    }
                    val body = resp.body ?: return@use "服务器返回了空响应。"
                    val total = body.contentLength()
                    dest.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(1 shl 16)
                            var read = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                read += n
                                onProgress(read, if (total > 0) total else 0L)
                            }
                            output.flush()
                        }
                    }
                    null
                }
            }
        } catch (e: IOException) {
            ioMessage(e)
        } catch (e: Throwable) {
            "下载失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** PUT 一份文件。返回 null = 成功，否则是给人看的错误。 */
    suspend fun writeFile(url: String, auth: String?, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { auth?.let { header("Authorization", it) } }
                .build()
            try {
                withRetry {
                    httpClient().newCall(req).execute().use { resp ->
                        when {
                            resp.code in 200..299 -> null
                            resp.code == 401 -> "认证失败（401）：账号或密码不对。"
                            resp.code == 403 -> "被拒绝（403）：这个账号不能在同步目录里写文件。"
                            // 409 / 405 是 PUT 打到不存在集合时各家 NAS 的不同表态，
                            // 对用户来说都是同一件事：同步目录没建起来。
                            resp.code == 409 || resp.code == 405 ->
                                "同步目录不存在（HTTP ${resp.code}），先在服务器上建好这个目录，或换一个已存在的目录。"

                            else -> "写入失败：HTTP ${resp.code}"
                        }
                    }
                }
            } catch (e: IOException) {
                ioMessage(e)
            } catch (e: Throwable) {
                "写入失败：${e.javaClass.simpleName}: ${e.message}"
            }
        }

    /**
     * 确保集合存在，逐级 MKCOL。
     *
     * 这里有个坑值得记着：**MKCOL 的 405 不能直接当成功**。405 既可能是
     * "集合已经存在"（RFC 4918 对已存在资源的标准答复），也可能是
     * "这台服务器根本不支持 MKCOL"。两种情况的后续行为完全相反 ——
     * 前者可以继续 PUT，后者 PUT 会失败，但早期版本一律吞掉 405，
     * 于是"上传成功"的提示是假的。所以建完必须用 PROPFIND Depth:0 验一次。
     */
    suspend fun ensureDir(url: String, auth: String?): String? = withContext(Dispatchers.IO) {
        val u = try {
            URL(url)
        } catch (t: Throwable) {
            return@withContext "同步目录地址不合法：$url"
        }
        val origin = "${u.protocol}://${u.authority}"
        // subPath 已经过 encodePathSegments，逐级 MKCOL 时保持原样
        val segments = u.path.trim('/').split('/').filter { it.isNotEmpty() }

        var acc = origin
        for (seg in segments) {
            acc = "$acc/$seg"
            // MKCOL 失败先不报错：可能本来就在，也可能服务器不支持，
            // 统一交给下面的 PROPFIND 判定，别拿状态码猜。
            mkcol(acc, auth)?.let { android.util.Log.w("MangaDock", "SYNC mkcol $acc -> $it") }
        }

        val target = if (segments.isEmpty()) origin else acc
        if (exists(target, auth)) null
        else "同步目录不存在也建不出来：$target\n请先在 WebDAV 里手工建好这个目录，或换一个已存在的目录。"
    }

    private suspend fun mkcol(url: String, auth: String?): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        try {
            withRetry {
                httpClient().newCall(req).execute().use { resp ->
                    if (resp.code in 200..299) null
                    else "HTTP ${resp.code}"
                }
            }
        } catch (e: IOException) {
            ioMessage(e)
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** PROPFIND Depth:0 —— 207 = 在，404 = 不在，其余算"不能确认"。 */
    suspend fun exists(url: String, auth: String?): Boolean = withContext(Dispatchers.IO) {
        val target = url.trimEnd('/') + "/"
        val req = Request.Builder()
            .url(target)
            .method("PROPFIND", PROPFIND_DEPTH0.toRequestBody(XML_TYPE))
            .header("Depth", "0")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        try {
            withRetry {
                httpClient().newCall(req).execute().use { resp ->
                    resp.code == 207 || resp.code in 200..299
                }
            }
        } catch (e: Throwable) {
            false
        }
    }

    private const val PROPFIND_DEPTH0 = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""
}

