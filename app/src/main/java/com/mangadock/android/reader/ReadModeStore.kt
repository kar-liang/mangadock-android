package com.mangadock.android.reader

import android.content.Context

/**
 * 每本书上次用的阅读模式（分页 / 条漫）。
 *
 * 为什么要有它：设置里的「翻页方向」是**全局默认值**，但用户经常一本书条漫、
 * 一本书单行本。没有这份记录的话，每次进阅读器都会被全局值覆盖，
 * 手动切的模式下次进来就没了（v25 之前的实际表现）。
 *
 * 读顺序：**每本覆盖 > 全局设置**。新加的书没有覆盖记录，自然就跟着全局走 ——
 * 这正是老大要的「设置成上下滚动之后，新加的漫画默认就是条漫」。
 */
class ReadModeStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("mangadock_readmode", Context.MODE_PRIVATE)

    /** 没有记录返回 null，调用方自己回落到全局设置。 */
    fun get(key: String): ReaderMode? = when (sp.getInt(k(key), -1)) {
        0 -> ReaderMode.PAGED
        1 -> ReaderMode.WEBTOON
        else -> null
    }

    fun set(key: String, mode: ReaderMode) {
        sp.edit().putInt(k(key), if (mode == ReaderMode.PAGED) 0 else 1).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    private fun k(key: String) = "m|" + key.takeLast(96)
}
