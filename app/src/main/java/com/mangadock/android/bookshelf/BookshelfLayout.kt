package com.mangadock.android.bookshelf

import com.mangadock.android.pages.BookCard
import com.mangadock.android.pages.naturalOrder
import com.mangadock.android.reader.ReadingProgress

/**
 * 书架的排序 / 分组。纯数据层，不碰 Context、不碰 Compose，
 * 这样切换规则只是一句 `remember(...)` 重算，网格本身不用改结构。
 *
 * 分组标题做成独占整行的 [BooksRow.Section]，由 LazyVerticalGrid 的
 * `span = { GridItemSpan(maxLineSpan) }` 渲染，不额外套一层列表。
 */
enum class BookSort(val label: String) {
    ADDED("最近加入"),
    NAME("名称"),
    RECENT_READ("最近阅读");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

enum class BookGroup(val label: String) {
    NONE("不分组"),
    TYPE("按类型"),
    STATUS("按阅读状态");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * v31：书架用网格还是列表。
 *
 * 网格适合"看封面找书"，列表适合书多、想一眼扫到书名和进度的场景 ——
 * 3 列网格一屏只能看 9 本，列表能看 8~9 本但每本都带完整书名和页码，
 * 书架上几十本之后两者的差别挺明显。只影响渲染方式，排序分组照旧。
 */
enum class BooksLayoutKind(val label: String) {
    GRID("网格"),
    LIST("列表");

    companion object {
        fun of(i: Int) = entries[i.coerceIn(0, entries.lastIndex)]
    }
}

sealed interface BooksRow {
    /** [id] 给 LazyGrid 当稳定 key；标题里带数量，会随书增减变化。 */
    data class Section(val title: String, val id: String) : BooksRow
    data class Card(val card: BookCard) : BooksRow
}

private data class BookInfo(
    val item: BookItem,
    val p: ReadingProgress?,
    val text: String
)

private fun progressText(p: ReadingProgress?): String = when {
    p == null -> "还没读过"
    p.finished -> "已读完"
    else -> "第 ${p.index + 1} / ${p.total} 页"
}

/** v19：书架改 3 列后卡片不再单独放一行进度，改成封面右下角的小角标。 */
private fun badgeOf(p: ReadingProgress?): String? = when {
    p == null -> null
    p.finished -> "已读完"
    else -> "${p.index + 1}/${p.total}"
}

/**
 * [progress] 传的是查询函数而不是 Map：书架卡片文案一直在用 ProgressStore，
 * 这样调用方不用先跑一遍全量读取。
 */
fun arrangeBooks(
    books: List<BookItem>,
    progress: (String) -> ReadingProgress?,
    sort: BookSort,
    group: BookGroup
): List<BooksRow> {
    val info = books.map { b ->
        val p = progress(b.key)
        BookInfo(b, p, progressText(p))
    }

    val ordered = when (sort) {
        // BookshelfStore 本来就是新加在前，显式排一次更稳（旧数据 addedAt 可能是 0）
        BookSort.ADDED -> info.sortedByDescending { it.item.addedAt }
        BookSort.NAME -> info.sortedWith { a, b -> naturalOrder(a.item.title, b.item.title) }
        BookSort.RECENT_READ -> info.sortedWith(
            compareByDescending<BookInfo> { it.p?.updatedAt ?: 0L }
                .thenByDescending { it.item.addedAt }
        )
    }

    val groups: List<Pair<String, List<BookInfo>>> = when (group) {
        BookGroup.NONE -> listOf("" to ordered)
        BookGroup.TYPE -> listOf(
            "CBZ 漫画" to ordered.filter { it.item.isCbz },
            "章节目录" to ordered.filter { !it.item.isCbz }
        )
        BookGroup.STATUS -> listOf(
            "在读" to ordered.filter { it.p != null && !it.p.finished },
            "还没读" to ordered.filter { it.p == null },
            "已读完" to ordered.filter { it.p?.finished == true }
        )
    }

    val out = ArrayList<BooksRow>(ordered.size + groups.size)
    groups.forEach { (title, list) ->
        if (list.isEmpty()) return@forEach
        if (title.isNotEmpty()) out += BooksRow.Section("$title · ${list.size}", "sec:$title")
        list.forEach { out += BooksRow.Card(BookCard(it.item, it.text, badgeOf(it.p))) }
    }
    return out
}
