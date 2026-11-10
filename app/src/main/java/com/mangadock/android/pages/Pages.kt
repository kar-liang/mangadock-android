package com.mangadock.android.pages

import androidx.compose.ui.graphics.Color
import com.mangadock.android.bookshelf.BookItem
import com.mangadock.android.webdav.DavEntry
import java.util.Locale

/**
 * 三个页面的**数据层**：状态、行模型、排版常量。
 *
 * 渲染已经全部搬到 `ui/Screens.kt` 的 LazyColumn / LazyVerticalGrid；
 * 以前这里还有一整套 DrawScope 手绘函数（整页 Canvas + 透明点击层），
 * 滚动时每帧重绘所有行、783 项的目录尤其灾难，现已删除。
 */

/** 文件页的状态。 */
data class FilesState(
    val loading: Boolean = false,
    val error: String? = null,
    val path: String = "/",
    val entries: List<DavEntry> = emptyList(),
    /** 当前目录里的图片（已按自然序排好），非空才显示"开始阅读" */
    val images: List<DavEntry> = emptyList(),
    /** 这个目录读到的页号，-1 表示没读过 */
    val readIndex: Int = -1,
    /** 至少成功过一轮 */
    val loadedOnce: Boolean = false,
    /** 网络没刷成功，现在屏幕上显示的是磁盘缓存 */
    val offline: Boolean = false
) {
    val statusText: String
        get() = when {
            offline -> "离线缓存"
            loading -> "连接中…"
            error != null -> "连接失败"
            loadedOnce -> "已连接"
            else -> "未连接"
        }
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jfif", "heic")

fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

/** 001.jpg < 002.jpg < 010.jpg < 100.jpg —— 数字段按数值比，其余按字符比。 */
fun naturalOrder(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** 文件页的一行。绘制和点击用的是同一份列表，不会对不上。 */
sealed interface FilesRow {
    data class Up(val target: String) : FilesRow
    data class Entry(val e: DavEntry) : FilesRow
}

fun filesRows(state: FilesState): List<FilesRow> {
    val out = ArrayList<FilesRow>(state.entries.size + 1)
    if (state.path != "/") {
        val t = state.path.substringBeforeLast('/', "").ifEmpty { "/" }
        out += FilesRow.Up(t)
    }
    state.entries.forEach { out += FilesRow.Entry(it) }
    return out
}

object FilesLayout {
    const val ROW_H = 56f
    /** 路径 / 状态那一行 */
    const val HEADER_H = 44f
    /** 错误提示块占的高度 */
    const val ERROR_H = 66f
    /** 「开始阅读」那一行 */
    const val READ_H = 56f
    /** 路径行右侧那个「＋ 加入」按钮 */
    const val JOIN_BTN_W = 54f
    const val JOIN_BTN_H = 28f
}

/** 书架页的一张卡片：书 + 进度文案 + 封面角标（排版层不碰存储）。 */
data class BookCard(
    val item: BookItem,
    val progress: String,
    /** 封面右下角小角标；没读过就是 null，不占位 */
    val badge: String? = null
)

/**
 * 书架网格尺寸。v19 按老大的参考图改成 **3 列 + 竖版封面**：
 * 之前是 2 列、158×168dp 的近正方形，比例难看、卡片也太大。
 * 现在高度不再写死，用 aspectRatio 跟着列宽自适应，换屏宽也不会变形。
 */
object BooksLayout {
    const val COLS = 3
    // v22：间距吸到 4dp 网格上（原来是 10 / 14，不在网格里）
    const val PAD_X = 16f
    const val GAP_X = 12f
    const val GAP_Y = 16f
    /** 封面宽 / 高，参考图里约 0.6，取 0.62（接近漫画单行本的修长比例） */
    const val COVER_RATIO = 0.62f
    const val CARD_RADIUS = 12f
}

private val COVER_COLORS = listOf(
    Color(0xFF6C7BD1), Color(0xFFC47B8E), Color(0xFF4FA58B),
    Color(0xFFD08A4A), Color(0xFF7A6BB5), Color(0xFF4E8FB8)
)

/** 首字色块封面用的调色板：按书名取模，同一本书颜色稳定。 */
fun coverPalette(seed: String): Color =
    COVER_COLORS[Math.floorMod(seed.hashCode(), COVER_COLORS.size)]

fun humanSize(b: Long): String = when {
    b <= 0L -> ""
    b < 1024L -> "$b B"
    b < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}
