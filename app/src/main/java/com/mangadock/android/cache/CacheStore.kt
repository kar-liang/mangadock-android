package com.mangadock.android.cache

import android.content.Context
import com.mangadock.android.bookshelf.CoverStore
import com.mangadock.android.pages.humanSize
import com.mangadock.android.reader.CbzStore
import com.mangadock.android.reader.PageBitmapCache
import java.io.File

/**
 * 磁盘缓存的统一入口。设置页那行不再写死「1.2 GB」，改成真算、真能清。
 *
 * 只管 app 自己的 `cacheDir` 下这几块：
 *   - `cbz/`：CBZ 元数据 / 单页缓存 / 整包兜底
 *   - `dircache/`：WebDAV 目录列表快照
 *   - `covers/`：书架真实封面缩略图（v18）
 *   - `bangumi/`：Bangumi 条目 JSON / 封面图（v23）
 *
 * **书架、阅读进度、WebDAV 账号密码都不在这里**，清空不会影响它们。
 * 清空封面后下次进书架会重新从 NAS 生成，等价于「重新生成所有封面」。
 * Bangumi 的「书名 → subject id」映射存在 SharedPreferences，清缓存不会丢。
 */
object CacheStore {

    private fun roots(ctx: Context): List<File> = listOf(
        File(ctx.cacheDir, "cbz"),
        File(ctx.cacheDir, "dircache"),
        File(ctx.cacheDir, "covers"),
        File(ctx.cacheDir, "bangumi")
    )

    private fun sizeOf(f: File): Long = when {
        f.isFile -> f.length()
        f.isDirectory -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        else -> 0L
    }

    fun sizeBytes(ctx: Context): Long = roots(ctx).sumOf { sizeOf(it) }

    /** 0 字节也给个明确文案，别让「0」显示成空白。 */
    fun sizeText(ctx: Context): String {
        val b = sizeBytes(ctx)
        return if (b <= 0L) "0 B" else humanSize(b)
    }

    /**
     * 清空磁盘 + 内存里的位图/句柄/封面缓存，返回释放的字节数。
     * 这里删的是 app 私有 cacheDir，不涉及任何用户文件。
     */
    fun clear(ctx: Context): Long {
        var freed = 0L
        CbzStore.invalidate()
        // 页位图现在只有这一处内存缓存（v19.1 去掉了 ReaderPageLoader 的 LRU）
        PageBitmapCache.clear()
        CoverStore.clearMemory()
        roots(ctx).forEach { r ->
            freed += sizeOf(r)
            // 只清内容、保留根目录：DirCache 这类 remember 住的对象还握着 File 引用，
            // 把根删了它下次写缓存会静默失败。
            runCatching { r.listFiles()?.forEach { child -> child.deleteRecursively() } }
        }
        return freed
    }
}
