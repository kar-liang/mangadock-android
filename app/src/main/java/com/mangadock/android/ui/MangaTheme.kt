package com.mangadock.android.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.settings.ThemeAccent
import com.mangadock.android.settings.ThemeMode

/**
 * v21 语义色层。
 *
 * 以前颜色是硬编码散落在 `Screens.kt` / `MainActivity` / `GlassDock` / `BookDetailScreen`
 * 里的（#F2F2F7、#111111、#8A8A8E ……），既没法换深色，也没法跟着壁纸取色。
 * 现在所有界面只认这组语义名字，具体色值由 [MangaTheme] 按三件事算出来：
 *
 * 1. **深色模式**（跟随系统 / 浅色 / 深色）
 * 2. **动态取色**（Android 12+ 从壁纸取，minSdk 33 天然支持）
 * 3. **手动主题色**（原来那 6 个 `ThemeAccent`，动态取色关闭时才生效）
 *
 * 这一层是纯数据，没有任何每帧计算：颜色只在设置/深浅切换时重算一次，
 * 不会碰性能红线。
 */
@Immutable
data class MangaColors(
    val dark: Boolean,
    /** 页面底色 */
    val bg: Color,
    /** 顶栏底色 */
    val bar: Color,
    /** 卡片 / 列表行底色 */
    val surface: Color,
    /** 主文字 */
    val ink: Color,
    /** 正文、对话框文字 */
    val body: Color,
    /** 次要说明文字 */
    val sub: Color,
    /** 行尾箭头这类极淡图标 */
    val chevron: Color,
    val divider: Color,
    /** 胶囊 / 分段控件的底 */
    val chip: Color,
    /** 更淡的填充（已加入按钮之类） */
    val chipSoft: Color,
    val danger: Color,
    val dangerBg: Color,
    val switchOn: Color,
    /** 主题强调色 */
    val accent: Color,
    val accentSoft: Color,
    /** 底栏胶囊底色与描边 */
    val dockSurface: Color,
    val dockBorder: Color
)

private val LocalMangaColors = staticCompositionLocalOf<MangaColors> {
    error("MangaColors 只能在 MangaTheme 内部取用")
}

object MangaTheme {
    /** 只读 CompositionLocal，不产生任何副作用，所以标 ReadOnly 以便被属性 getter 调用。 */
    val colors: MangaColors
        @Composable @ReadOnlyComposable get() = LocalMangaColors.current
}

/** 手动 6 色主题：沿用 v13 定下来的浅色观感，这里补一套深色。 */
private fun manualPalette(accent: Color, dark: Boolean): MangaColors =
    if (dark) {
        MangaColors(
            dark = true,
            bg = Color(0xFF0E0E10),
            bar = Color(0xFF151517),
            surface = Color(0xFF1C1C1F),
            ink = Color(0xFFEDEDF0),
            body = Color(0xFFC7C7CC),
            sub = Color(0xFF9A9AA1),
            chevron = Color(0xFF6E6E75),
            divider = Color(0x1FFFFFFF),
            chip = Color(0x24FFFFFF),
            chipSoft = Color(0x14FFFFFF),
            danger = Color(0xFFFF6B6B),
            dangerBg = Color(0x24FF6B6B),
            switchOn = Color(0xFF32D74B),
            accent = accent,
            accentSoft = accent.copy(alpha = 0.20f),
            dockSurface = Color(0xFF1C1C1F),
            dockBorder = Color(0x1FFFFFFF)
        )
    } else {
        MangaColors(
            dark = false,
            bg = Color(0xFFF2F2F7),
            bar = Color(0xFFF7F7FA),
            surface = Color(0xFFFFFFFF),
            ink = Color(0xFF111111),
            body = Color(0xFF3C3C43),
            sub = Color(0xFF8A8A8E),
            chevron = Color(0xFFB0B0B6),
            divider = Color(0x1A3C3C43),
            chip = Color(0x24787880),
            chipSoft = Color(0x143C3C43),
            danger = Color(0xFFC0392B),
            dangerBg = Color(0x14C0392B),
            switchOn = Color(0xFF34C759),
            accent = accent,
            accentSoft = accent.copy(alpha = 0.12f),
            dockSurface = Color(0xFFFFFFFF),
            dockBorder = Color(0x14000000)
        )
    }

/**
 * 动态取色：表面色全部取 M3 的 surfaceContainer 阶梯，这样壁纸色调能一路渗透到
 * 卡片/底栏，而不是只有强调色变。
 */
private fun dynamicPalette(scheme: ColorScheme, dark: Boolean): MangaColors = MangaColors(
    dark = dark,
    // 浅色：底用略带色调的 low、卡片用最白的 lowest；
    // 深色反过来：底用最暗的 lowest、卡片用略亮的 low。
    bg = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainerLow,
    bar = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainerLow,
    surface = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
    ink = scheme.onSurface,
    body = scheme.onSurfaceVariant,
    sub = scheme.outline,
    chevron = scheme.outlineVariant,
    divider = scheme.outlineVariant.copy(alpha = 0.55f),
    chip = scheme.inkChip(dark),
    chipSoft = scheme.inkChip(dark).copy(alpha = if (dark) 0.08f else 0.06f),
    danger = scheme.error,
    dangerBg = scheme.error.copy(alpha = if (dark) 0.16f else 0.10f),
    switchOn = scheme.primary,
    accent = scheme.primary,
    accentSoft = scheme.primary.copy(alpha = if (dark) 0.22f else 0.14f),
    dockSurface = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
    dockBorder = scheme.outlineVariant.copy(alpha = 0.6f)
)

private fun ColorScheme.inkChip(dark: Boolean): Color =
    if (dark) Color(0x24FFFFFF) else Color(0x24787880)

@Composable
fun MangaTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (ThemeMode.of(settings.darkMode)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    val dynamic = settings.dynamicColor

    val accent = ThemeAccent.of(settings.accent).color
    val scheme = remember(accent, dark, dynamic) {
        when {
            dynamic -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            dark -> darkColorScheme(primary = accent)
            else -> lightColorScheme(primary = accent)
        }
    }
    val colors = remember(scheme, dark, dynamic, accent) {
        if (dynamic) dynamicPalette(scheme, dark) else manualPalette(accent, dark)
    }

    // v22：把所有 clickable 的 ripple 统一染成主题色。
    // 以前默认 ripple 是灰色，深色/动态取色下几乎看不见按下反馈。
    val indication = remember(colors.accent) { ripple(color = colors.accent) }

    MaterialTheme(colorScheme = scheme) {
        // ⚠️ 必须在 MaterialTheme **内部** provide：MaterialTheme 自己也会 provide
        // 一个 LocalIndication（默认灰），套在外面会被它盖掉，按下还是灰的。
        CompositionLocalProvider(
            LocalMangaColors provides colors,
            LocalIndication provides indication
        ) {
            content()
        }
    }
}
