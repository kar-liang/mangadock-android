package com.mangadock.android.reader

import android.content.Context
import org.json.JSONObject

/** 某本书读到哪了。 */
data class ReadingProgress(
    /** 从 0 开始的页号 */
    val index: Int,
    val total: Int,
    /** 毫秒时间戳，用来排序"继续阅读" */
    val updatedAt: Long
) {
    /** 读到最后一页就算读完了，别再显示"继续阅读"。 */
    val finished: Boolean get() = total > 0 && index >= total - 1
}

/**
 * 阅读进度。key 是书的 WebDAV 路径（目录路径，或 cbz 文件路径）。
 *
 * 为什么整份存成一个 JSON 字符串：这种数据一共几十条、每条几十字节，
 * 分键存反而要遍历 getAll。整份读写最省事，也方便以后整体迁移格式。
 */
class ProgressStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mangadock_progress", Context.MODE_PRIVATE)

    private var cache: MutableMap<String, ReadingProgress>? = null

    private fun load(): MutableMap<String, ReadingProgress> {
        cache?.let { return it }
        val map = mutableMapOf<String, ReadingProgress>()
        val raw = sp.getString(KEY, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(raw)
                root.keys().forEach { k ->
                    val o = root.optJSONObject(k) ?: return@forEach
                    map[k] = ReadingProgress(
                        index = o.optInt("i", 0),
                        total = o.optInt("t", 0),
                        updatedAt = o.optLong("u", 0L)
                    )
                }
            }
        }
        cache = map
        return map
    }

    fun get(path: String): ReadingProgress? = load()[norm(path)]

    fun put(path: String, index: Int, total: Int) {
        val map = load()
        map[norm(path)] = ReadingProgress(index, total, System.currentTimeMillis())
        flush(map)
    }

    /** 全量，同步用。 */
    fun all(): Map<String, ReadingProgress> = load().toMap()

    /** 同步用：整份替换（云端覆盖本机）。 */
    fun replaceAll(map: Map<String, ReadingProgress>) {
        val normed = map.entries
            .filter { it.key.isNotBlank() && it.value.total > 0 }
            .associate { norm(it.key) to it.value }
        cache = normed.toMutableMap()
        flush(normed)
    }

    /** 最近读过的 N 本，新的在前。 */
    fun recent(limit: Int = 20): List<Pair<String, ReadingProgress>> =
        load().entries
            .sortedByDescending { it.value.updatedAt }
            .take(limit)
            .map { it.key to it.value }

    fun remove(path: String) {
        val map = load()
        map.remove(norm(path))
        flush(map)
    }

    private fun flush(map: Map<String, ReadingProgress>) {
        val root = JSONObject()
        map.forEach { (k, v) ->
            root.put(
                k,
                JSONObject()
                    .put("i", v.index)
                    .put("t", v.total)
                    .put("u", v.updatedAt)
            )
        }
        sp.edit().putString(KEY, root.toString()).apply()
    }

    /** 统一成「以 / 开头、不以 / 结尾」，免得同一个目录存出两份。 */
    private fun norm(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return if (p.length > 1) p.trimEnd('/') else p
    }

    private companion object {
        const val KEY = "progress_v1"
    }
}
