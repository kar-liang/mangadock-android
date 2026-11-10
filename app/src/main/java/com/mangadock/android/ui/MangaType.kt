package com.mangadock.android.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * v22 字号比例尺。
 *
 * 以前字号是散着写的：13.sp 出现 18 次、12.sp 13 次、14.sp 9 次、15.sp 7 次……
 * 而且**一个 lineHeight 都没设**，全靠默认值，中英文混排时行距飘。这里按 M3 的
 * 比例尺思路收敛成 10 档语义名字，字号保持观感不变，补上 lineHeight 和字重。
 *
 * 命名对齐 Material 3 的 token（display / title / body / label），
 * 但数值是按这个 app 的信息密度定的 —— 直接套 M3 的 displayLarge 57sp 会把列表撑爆。
 *
 * 用法：`Text("书名", style = MangaType.titleSmall, color = c.ink)`
 * 颜色**永远单独传**，不要写进 style，否则切主题时会盖掉语义色。
 */
object MangaType {
    /**
     * 一级页面大标题。
     *
     * 28sp / Bold 是照着 SenPlayer 和 Streama 的顶栏量的：这两个 app 的标题字高在
     * 1080p@3x 屏上都是 80~82px（≈27dp），字重是 Bold 而不是 SemiBold。
     * 之前的 24sp SemiBold 相比之下明显偏小偏软，顶栏看起来像"工具条"而不是"标题"。
     */
    val display = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    )

    /** 结尾卡「本话完」这类强调标题（原 20sp） */
    val headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    )

    /** 顶栏标题（原 19sp） */
    val titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    )

    /** 卡片标题 / 对话框标题 / 章节名（原 16sp） */
    val titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    )

    /** 列表主文本：设置行 label、文件名（原 15sp） */
    val titleSmall = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal
    )

    /** 对话框正文、按钮文字（原 14sp） */
    val bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    )

    /** 次要说明、状态行（原 13sp） */
    val bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal
    )

    /** 小标签、提示（原 12sp） */
    val bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal
    )

    /** 徽标 / 分段控件（原 12sp + Medium） */
    val labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )

    /** 极小说明（原 11sp） */
    val labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal
    )

    /** 封面角标（原 9sp） */
    val micro = TextStyle(
        fontSize = 9.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium
    )
}
