package com.mangadock.android.bookshelf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 隐私书架（对齐 iOS `PrivacyStore`）。
 *
 * 它是**一份独立的书单**，不是给书架里的书打个 hidden 标记：
 * 隐藏 = 从 `BookshelfStore` 移走 + 落到这份名单里。这样即使有人翻 `mangadock_bookshelf`
 * 也看不到这本书名，书架首屏、分组、排序全都不用额外过滤。
 *
 * ⚠️ 这是**界面级隐藏 + 设备凭证解锁**，不是加密 —— 文件本来就在服务器上，
 * 谁真拿到设备和账号都能看。和 iOS 一样，说明写在界面上，别让人误以为它是保险箱。
 */
class PrivacyStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mangadock_privacy", Context.MODE_PRIVATE)

    private var cache: MutableList<BookItem>? = null

    fun all(): List<BookItem> = load().toList()

    val count: Int get() = load().size
    val isEmpty: Boolean get() = load().isEmpty()

    fun contains(key: String): Boolean {
        val k = norm(key)
        return load().any { it.key == k }
    }

    /** 移进隐私空间：从可见书架摘掉，落到这里。 */
    fun hide(shelf: BookshelfStore, item: BookItem): Boolean {
        val list = load()
        val k = norm(item.key)
        if (list.any { it.key == k }) {
            shelf.remove(k)
            return false
        }
        list.add(0, item.copy(key = k))
        flush(list)
        shelf.remove(k)
        return true
    }

    /** 放回可见书架。 */
    fun restore(shelf: BookshelfStore, key: String) {
        val list = load()
        val k = norm(key)
        val hit = list.firstOrNull { it.key == k } ?: return
        list.removeAll { it.key == k }
        flush(list)
        shelf.add(hit)
    }

    fun remove(key: String) {
        val list = load()
        if (list.removeAll { it.key == norm(key) }) flush(list)
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

    private fun norm(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return if (p.length > 1) p.trimEnd('/') else p
    }

    private companion object {
        const val KEY = "privacy_v1"
    }
}
