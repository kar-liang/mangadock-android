package com.mangadock.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangadock.android.bookshelf.BookGroup
import com.mangadock.android.bookshelf.BookItem
import com.mangadock.android.bookshelf.BookSort
import com.mangadock.android.bookshelf.arrangeBooks
import com.mangadock.android.glass.glassSource
import com.mangadock.android.glass.rememberGlassSource
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.ui.prism.BackChevron
import com.mangadock.android.ui.prism.LockGlyph
import com.mangadock.android.ui.prism.PrismCircleButton

/**
 * 隐私空间（对齐 iOS `PrivateShelfView`）。
 *
 * 两层：没解锁只画一个锁面板，解锁之后才是网格。
 * 解锁走系统凭证（[android.app.KeyguardManager]），不用额外依赖，
 * 也不像自绘密码框那样留一个可以慢慢试的输入口。
 *
 * ⚠️ 界面上明说了这是「界面级隐藏」：文件还在服务器上，谁也拦不住有账号的人。
 * 别把它写成保险箱，用户会当真。
 */
@Composable
fun PrivacyShelfScreen(
    books: List<BookItem>,
    settings: AppSettings,
    unlocked: Boolean,
    /** 设备有没有设锁屏密码；没有就加不了锁，面板里如实说明 */
    deviceLocked: Boolean,
    onUnlock: () -> Unit,
    /** 设备没设锁屏密码时，面板上的按钮改成跳系统锁屏设置 */
    onSetLock: () -> Unit,
    onBack: () -> Unit,
    onOpen: (BookItem) -> Unit,
    onLongPress: (BookItem, Rect) -> Unit,
    coverTick: Int = 0,
    /** 跟主书架用同一个布局设置 */
    layout: Int = 0,
    /** 下拉刷新：和主书架同一套动作（清封面缓存 + 重试 Bangumi 补匹配） */
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // v88：这一页也统一到「顶栏圆形玻璃按钮」规范。它是盖在底栏之上的独立页面，
    // 所以要自己建一份 GlassSource：顶栏底色 + 页面内容进录制层，返回钮单独盖在上层
    // （不能让按钮采到自己，否则玻璃里会出现按钮自己的影子）。
    val privacyGlass = rememberGlassSource()

    Box(modifier.fillMaxSize().background(MangaTheme.colors.bg)) {
        Column(Modifier.fillMaxSize().glassSource(privacyGlass)) {
            // 自己带一条顶栏：它是盖在底栏之上的一层独立页面，不共用主顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .background(MangaTheme.colors.bar)
                    .padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回钮在录制层外覆盖，这里占位，保证标题位置不变
                Spacer(Modifier.size(44.dp))
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(
                    "隐私空间",
                    Modifier.weight(1f),
                    style = MangaType.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MangaTheme.colors.ink
                )
                if (unlocked) {
                    Text(
                        "${books.size} 本",
                        style = MangaType.bodySmall,
                        color = MangaTheme.colors.sub
                    )
                }
            }

            if (!unlocked) {
                LockPanel(
                    deviceLocked = deviceLocked,
                    accent = settings.accentColor,
                    onUnlock = onUnlock,
                    onSetLock = onSetLock
                )
                return@Column
            }

            val grid = rememberLazyGridState()
            val rows = remember(books, coverTick) {
                arrangeBooks(
                    books = books,
                    progress = { null },
                    sort = BookSort.of(settings.bookSort),
                    group = BookGroup.of(0)   // 隐私空间不分组：分组标题会漏书名
                )
            }

            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "隐私空间是空的。\n长按书架上的漫画选「隐藏进隐私空间」放进来。",
                        style = MangaType.bodyMedium,
                        color = MangaTheme.colors.sub,
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }

            BooksPage(
                settings = settings,
                rows = rows,
                gridState = grid,
                onOpen = onOpen,
                onLongPress = onLongPress,
                onGo = onBack,
                coverTick = coverTick,
                layout = layout,
                refreshing = refreshing,
                onRefresh = onRefresh,
                note = if (!deviceLocked) {
                    "本机没有设置锁屏密码，隐私空间当前没有加锁。"
                } else {
                    "只隐藏、不加密：服务器上的文件仍然在。"
                },
                emptyTitle = "隐私空间是空的",
                emptySub = "长按书架上的漫画选「隐藏进隐私空间」",
                emptyAction = "回书架"
            )
        }

        // 顶栏返回圆钮：和主顶栏 / 详情页同一枚 44dp 圆形玻璃按钮。
        // 放在录制层之外，避免自采样；位置与上面那条顶栏 Row 完全对齐。
        Row(
            Modifier
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrismCircleButton(
                onClick = onBack,
                glass = privacyGlass,
                dark = MangaTheme.colors.dark
            ) {
                BackChevron(MangaTheme.colors.ink)
            }
        }
    }
}

@Composable
private fun LockPanel(
    deviceLocked: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onUnlock: () -> Unit,
    onSetLock: () -> Unit
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            // v88：改用 v87 抽出的公共锁图标，不再留一份私有副本
            LockGlyph(accent, glyphSize = 30.dp)
        }
        Spacer(Modifier.height(22.dp))
        Text("已锁定", style = MangaType.titleMedium, color = MangaTheme.colors.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            if (deviceLocked) {
                "用这台设备的锁屏密码 / 图案 / 指纹解锁。\n只在本次运行期间保持解锁。"
            } else {
                "这台设备没有设置锁屏密码，无法给隐私空间加锁。\n设置一个锁屏密码后再回来。"
            },
            style = MangaType.bodyMedium,
            color = MangaTheme.colors.sub,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(accent.copy(alpha = 0.12f))
                .clickable { if (deviceLocked) onUnlock() else onSetLock() }
                .padding(horizontal = 26.dp, vertical = 11.dp)
        ) {
            Text(
                if (deviceLocked) "解锁" else "去设置锁屏密码",
                style = MangaType.bodyLarge,
                color = accent,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 隐私空间里列表尾部的说明条，接到 BooksPage 的 note 上。 */
@Composable
internal fun PrivacyNote(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MangaTheme.colors.chipSoft)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, style = MangaType.bodySmall, color = MangaTheme.colors.sub)
    }
}
