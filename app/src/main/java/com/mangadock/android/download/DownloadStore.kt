package com.mangadock.android.download

import android.content.Context
import android.util.Log
import com.mangadock.android.pages.isImageFile
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.webdav.DavResult
import com.mangadock.android.webdav.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 离线下载（对齐 iOS `DownloadStore`，v28）。
 *
 * 和「阅读时顺手缓存」是两件不同的事：
 *  - 缓存（[com.mangadock.android.reader.CbzStore] / 散图页缓存）随时会被「清空缓存」删掉；
 *  - 这里下下来的东西放在 `filesDir/downloads/`，只有用户自己点「删除下载」才会没。
 *
 * 目录结构（每话一个目录，名字是 key 的 sha1，避免中文/特殊字符当路径）：
 * ```
 * downloads/<sha1>/book.cbz       整包章节（原样存，阅读器直接用 ZipFile 随机读）
 * downloads/<sha1>/pages/0001.jpg 散图章节（按自然序列好，离线时按本地文件读）
 * ```
 *
 * 队列**单线程**：手机上一话几十兆、几话一起下只会互相抢带宽，还更容易失败。
 * 状态用 [version] 这个计数器通知 UI（跟书架封面那套一致，不做细粒度 diff）。
 */
object DownloadStore {

    private const val TAG = "MangaDock"
    private const val PREFS = "mangadock_downloads"
    private const val KEY_RECORDS = "records_v1"

    /** 下好的一话。 */
    data class Rec(
        val key: String,
        val title: String,
        val isArchive: Boolean,
        val pageCount: Int,
        val bytes: Long,
        val dirName: String,
        val addedAt: Long
    )

    /** 排队等下载的一话。[url] 是整包地址，[dirPath] 是目录章节的路径，二选一。 */
    data class Job(val key: String, val title: String, val url: String?, val dirPath: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: CoroutineJob? = null
    private var ctxRef: Context? = null

    private var cache: MutableList<Rec>? = null
    private var queue: MutableList<Job> = mutableListOf()
    private var current: Job? = null

    private val _version = MutableStateFlow(0)
    /** 任何记录/队列变化都 +1，UI 拿它触发重算。 */
    val version: StateFlow<Int> = _version

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    private val _activeTitle = MutableStateFlow<String?>(null)
    val activeTitle: StateFlow<String?> = _activeTitle

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (ctxRef == null) ctxRef = ctx.applicationContext
        if (cache != null) return
        val list = mutableListOf<Rec>()
        val raw = prefs(ctx).getString(KEY_RECORDS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val rec = Rec(
                        key = o.optString("k"),
                        title = o.optString("t"),
                        isArchive = o.optBoolean("a", true),
                        pageCount = o.optInt("n", 0),
                        bytes = o.optLong("b", 0L),
                        dirName = o.optString("d"),
                        addedAt = o.optLong("u", 0L)
                    )
                    // 目录被系统清了 / 手动删了 → 记录也一并作废，别留幽灵条目
                    if (rec.key.isNotBlank() && dirOf(rec).exists()) list += rec
                }
            }
        }
        cache = list
        if (list.isNotEmpty()) persist(ctx)
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        cache?.forEach { r ->
            arr.put(
                JSONObject()
                    .put("k", r.key).put("t", r.title).put("a", r.isArchive)
                    .put("n", r.pageCount).put("b", r.bytes).put("d", r.dirName)
                    .put("u", r.addedAt)
            )
        }
        prefs(ctx).edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    // ------------------------------------------------------------ 查询

    fun records(ctx: Context): List<Rec> {
        init(ctx)
        return cache.orEmpty().toList()
    }

    fun record(ctx: Context, key: String): Rec? {
        init(ctx)
        return cache?.firstOrNull { it.key == key }
    }

    fun count(ctx: Context): Int = records(ctx).size

    fun totalBytes(ctx: Context): Long = records(ctx).sumOf { it.bytes }

    fun isQueued(key: String): Boolean = queue.any { it.key == key } || current?.key == key

    fun progressOf(key: String): Float? = _progress.value[key]

    fun dirOf(rec: Rec): File = File(root(ctxRef), rec.dirName)

    /** 整包章节下好之后的本体文件。 */
    fun archiveFile(rec: Rec): File = File(dirOf(rec), "book.cbz")

    /** 散图章节的本地页目录。 */
    fun pagesDir(rec: Rec): File = File(dirOf(rec), "pages")

    private fun root(ctx: Context? = ctxRef): File =
        File(ctx?.filesDir ?: ctxRef!!.filesDir, "downloads")

    // ------------------------------------------------------------ 入队

    /**
     * 把几话塞进队列（已经下过 / 已经在队列里的自动跳过）。
     * [settings] 只用来取 WebDAV 账号，取页时才算地址。
     */
    fun enqueue(ctx: Context, jobs: List<Job>, settings: AppSettings) {
        init(ctx)
        if (jobs.isEmpty()) return
        var added = 0
        jobs.forEach { j ->
            if (cache?.any { it.key == j.key } == true) return@forEach
            if (queue.any { it.key == j.key } || current?.key == j.key) return@forEach
            queue += j
            added++
        }
        if (added == 0) return
        _lastError.value = null
        _version.value++
        startWorker(ctx, settings)
    }

    private fun startWorker(ctx: Context, settings: AppSettings) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val job = synchronized(queue) {
                    if (queue.isEmpty()) null else queue.removeAt(0)
                } ?: break
                current = job
                _activeTitle.value = job.title
                _progress.value = _progress.value + (job.key to 0f)
                _version.value++
                try {
                    val rec = downloadOne(ctx, job, settings)
                    if (rec != null) {
                        cache?.removeAll { it.key == rec.key }
                        cache?.add(rec)
                        persist(ctx)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "download fail ${job.title}: ${e.javaClass.simpleName}")
                    _lastError.value = "「${job.title}」下载失败"
                } finally {
                    _progress.value = _progress.value - job.key
                    current = null
                    _activeTitle.value = null
                    _version.value++
                }
            }
            _activeTitle.value = null
            worker = null
            // 收尾期间又加了任务
            if (queue.isNotEmpty()) startWorker(ctx, settings)
        }
    }

    fun cancel(key: String) {
        synchronized(queue) { queue.removeAll { it.key == key } }
        if (current?.key == key) {
            worker?.cancel()
            worker = null
            current = null
            _activeTitle.value = null
        }
        _progress.value = _progress.value - key
        _version.value++
    }

    fun cancelAll() {
        synchronized(queue) { queue.clear() }
        worker?.cancel()
        worker = null
        current = null
        _activeTitle.value = null
        _progress.value = emptyMap()
        _version.value++
    }

    // ------------------------------------------------------------ 删除

    suspend fun remove(ctx: Context, key: String) {
        cancel(key)
        init(ctx)
        val rec = cache?.firstOrNull { it.key == key } ?: return
        withContext(Dispatchers.IO) { dirOf(rec).deleteRecursively() }
        cache?.removeAll { it.key == key }
        persist(ctx)
        _version.value++
    }

    suspend fun removeAll(ctx: Context) {
        cancelAll()
        init(ctx)
        withContext(Dispatchers.IO) { root(ctx).deleteRecursively() }
        cache?.clear()
        persist(ctx)
        _version.value++
    }

    // ------------------------------------------------------------ 干活

    private suspend fun downloadOne(ctx: Context, job: Job, settings: AppSettings): Rec? {
        val dirName = hash(job.key)
        val dir = File(root(ctx), dirName)
        withContext(Dispatchers.IO) {
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
        }
        val auth = WebDavClient.authHeader(settings.webdavUser, settings.webdavPass)

        if (job.url != null) {
            val dest = File(dir, "book.cbz")
            val ok = downloadFile(job.url, auth, dest) { p ->
                _progress.value = _progress.value + (job.key to p.coerceIn(0f, 1f))
            }
            if (!ok) {
                dir.deleteRecursively()
                throw IllegalStateException("下载失败")
            }
            // 页数直接从本地 zip 数，顺手验证包是完整的
            val pages = com.mangadock.android.reader.CbzStore.listPages(dest).size
            if (pages == 0) {
                dir.deleteRecursively()
                throw IllegalStateException("包里没有图片")
            }
            return Rec(
                key = job.key, title = job.title, isArchive = true,
                pageCount = pages, bytes = dest.length(), dirName = dirName,
                addedAt = System.currentTimeMillis()
            )
        }

        val dirPath = job.dirPath ?: throw IllegalStateException("没有地址")
        val listed = WebDavClient.list(settings.webdavUrl, settings.webdavUser, settings.webdavPass, dirPath)
        if (listed is DavResult.Fail) {
            dir.deleteRecursively()
            throw IllegalStateException(listed.message)
        }
        val images = (listed as DavResult.Ok).entries
            .filter { !it.isDir && isImageFile(it.name) }
            .sortedWith { a, b -> naturalOrder(a.name, b.name) }
        if (images.isEmpty()) {
            dir.deleteRecursively()
            throw IllegalStateException("这个目录里没有图片")
        }

        val pagesDir = File(dir, "pages").apply { mkdirs() }
        var written = 0L
        images.forEachIndexed { i, e ->
            val url = WebDavClient.fileUrl(settings.webdavUrl, e.path)
            if (url != null) {
                val ext = e.name.substringAfterLast('.', "jpg").lowercase()
                val dest = File(pagesDir, String.format("%04d.%s", i + 1, ext))
                val ok = downloadFile(url, auth, dest) { f ->
                    // 单页内部进度不显示，按页数走更稳
                    _progress.value = _progress.value +
                        (job.key to ((i + f) / images.size.toFloat()))
                }
                if (!ok) {
                    dir.deleteRecursively()
                    throw IllegalStateException("第 ${i + 1} 页下载失败")
                }
                written += dest.length()
            }
        }
        return Rec(
            key = job.key, title = job.title, isArchive = false,
            pageCount = images.size, bytes = written, dirName = dirName,
            addedAt = System.currentTimeMillis()
        )
    }

    /** 流式写盘，避免把几十兆的包整个读进内存。 */
    private suspend fun downloadFile(
        url: String,
        auth: String?,
        dest: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        runCatching {
            val req = Request.Builder()
                .url(url)
                .apply { auth?.let { header("Authorization", it) } }
                .build()
            WebDavClient.httpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                val total = body.contentLength()
                var read = 0L
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            Log.w(TAG, "downloadFile fail: ${it.javaClass.simpleName}")
            tmp.delete()
            false
        }
    }

    private fun hash(key: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
    }.getOrDefault(Integer.toHexString(key.hashCode()))
}
