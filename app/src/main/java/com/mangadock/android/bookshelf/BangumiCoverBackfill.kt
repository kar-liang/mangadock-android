package com.mangadock.android.bookshelf

import android.content.Context
import android.util.Log
import com.mangadock.android.bangumi.BangumiClient
import com.mangadock.android.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 书架 Bangumi 封面补匹配（v25）。
 *
 * ## 为什么需要它
 * 书架封面只在「这本书已经匹配过 Bangumi」时才能拿到正式封面，也就是必须之前进过
 * 详情页。新加的书如果从没点开过，书架上永远是 WebDAV 首图。
 *
 * ## 为什么不能直接在每个卡片里搜
 * 一进书架就有 9~12 张可见卡片同时组合，每本各发一次搜索 = 整页预取，直接把
 * 「只给可见卡片发请求」这条约定玩坏，网慢时封面会一片一片地迟到。
 *
 * 所以这里做**串行补匹配**，对齐 iOS `CoverStore.scheduleBangumiBackfill`：
 * - 全局单队列 + [lock]，同一时刻只有一本在搜；
 * - 每本之间 [GAP_MS] 间隔，避免一股脑打 API；
 * - 同一个 key 只排一次（[queued]），已匹配（含 -1）直接跳过；
 * - 网络刚失败（冷却中）就整队不动，等用户下次进书架再说；
 * - 匹配成功 [version] +1，书架卡片把它当 `produceState` 的 key 重新取封面。
 *
 * 界面只需要在卡片组合时调 [enqueue]，剩下都不用管。
 */
object BangumiCoverBackfill {

    private const val TAG = "MangaDock"
    private const val GAP_MS = 600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val queued: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val _version = MutableStateFlow(0)

    /** 每成功匹配一本就 +1，书架拿它触发封面重取。 */
    val version: StateFlow<Int> = _version

    fun enqueue(ctx: Context, book: BookItem, settings: AppSettings) {
        if (!settings.bangumiEnabled) return
        if (CoverStore.hasCustom(ctx, book.key)) return           // 自定义封面优先，不用补
        if (BangumiClient.mapping(ctx, book.path) != null) return  // 搜过就别再搜
        if (BangumiClient.isCoolingDown()) return                  // 刚失败，整队歇一会
        if (!queued.add(book.key)) return

        scope.launch {
            try {
                lock.withLock {
                    // 排队期间可能已经被详情页 / 上一轮补上了
                    if (BangumiClient.mapping(ctx, book.path) != null) return@withLock
                    if (BangumiClient.isCoolingDown()) return@withLock
                    delay(GAP_MS)
                    val got = BangumiClient.subject(ctx, book.title, book.path, settings)
                    if (got != null) {
                        _version.value += 1
                        Log.i(TAG, "COVER bangumi ready: ${book.title} -> ${got.id}")
                    }
                }
            } finally {
                queued.remove(book.key)
            }
        }
    }
}
