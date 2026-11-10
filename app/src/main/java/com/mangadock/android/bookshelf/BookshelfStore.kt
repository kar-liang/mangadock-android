package com.mangadock.android.bookshelf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架里的「一本书」。
 *
 * iOS 1.3.5 把书架和 WebDAV 目录浏览拆开：书架是用户自己挑出来、存在本地的列表，
 * **不再等于服务器当前目录**。所以这里只存路径 + 标题，不缓存目录内容——
 * 服务器上的章节增删了，点进去重新拉一遍就是最新的。
 */
data class BookItem(
    /** 归一化路径，同时当阅读进度的 key */
    val key: String,
    val title: String,
    /** WebDAV 上的绝对路径 */
    val path: String,
    /** true = 单个 cbz/zip 文件；false = 目录（进去挑章节或图片） */
    val isCbz: Boolean,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 顺序是有意义的（新加的排前面），所以用 JSONArray 而不是 JSONObject。
 * 和 [com.mangadock.android.reader.ProgressStore] 一样整份读写，几十条数据不值得引数据库。
 */
class BookshelfStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mangadock_bookshelf", Context.MODE_PRIVATE)

    private var cache: MutableList<BookItem>? = null

    fun all(): List<BookItem> = load().toList()

    fun contains(key: String): Boolean {
        val k = norm(key)
        return load().any { it.key == k }
    }

    /** 已经加过就返回 false，调用方拿它决定提示文案。 */
    fun add(item: BookItem): Boolean {
        val list = load()
        val k = norm(item.key)
        if (list.any { it.key == k }) return false
        list.add(0, item.copy(key = k))
        flush(list)
        return true
    }

    /** 同步用：整份替换（云端覆盖本机）。 */
    fun replaceAll(items: List<BookItem>) {
        val cleaned = items.filter { it.key.isNotBlank() }
            .map { it.copy(key = norm(it.key), path = norm(it.path)) }
            .distinctBy { it.key }
        cache = cleaned.toMutableList()
        flush(cleaned)
    }

    fun remove(key: String) {
        val list = load()
        val k = norm(key)
        if (list.removeAll { it.key == k }) flush(list)
    }

    private fun load(): MutableList<BookItem> {
        cache?.let { return it }
        val list = ArrayList<BookItem>()
        val raw = sp.getString(KEY, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("k")
                    if (k.isBlank()) continue
                    list += BookItem(
                        key = k,
                        title = o.optString("t").ifBlank { k.substringAfterLast('/') },
                        path = o.optString("p").ifBlank { k },
                        isCbz = o.optBoolean("c", false),
                        addedAt = o.optLong("a", 0L)
                    )
                }
            }
        }
        cache = list
        return list
    }

    private fun flush(list: List<BookItem>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject()
                    .put("k", b.key)
                    .put("t", b.title)
                    .put("p", b.path)
                    .put("c", b.isCbz)
                    .put("a", b.addedAt)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    /** 统一成「以 / 开头、不以 / 结尾」，免得同一个目录存出两份。 */
    private fun norm(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return if (p.length > 1) p.trimEnd('/') else p
    }

    private companion object {
        const val KEY = "books_v1"
    }
}
