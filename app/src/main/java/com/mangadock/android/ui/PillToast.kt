package com.mangadock.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * v91：全站统一的轻提示。
 *
 * 之前是两套：主界面在 [com.mangadock.android.MainActivity] 里内联了一段
 * 黑胶囊 + 1.6s 自散；详情页排序切换却用系统 `Toast`（v89 改的），
 * 两个提示在同一个 App 里长得完全不一样。这里抽成一处，两边共用。
 *
 * 规格不变：黑 90% 胶囊、白字、1.6s 自动消失、没有按钮、不拦点击。
 *
 * @param message 为 null 时不渲染（调用方只管持有状态）。
 * @param bottomPadding 主界面要给底栏让位（104dp）；详情页盖住了底栏，40dp 就够。
 */
@Composable
fun PillToast(
    message: String?,
    onDismiss: () -> Unit,
    bottomPadding: Dp = 104.dp,
) {
    val msg = message ?: return
    LaunchedEffect(msg) {
        delay(1600)
        onDismiss()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            msg,
            Modifier
                .padding(bottom = bottomPadding)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(0xE6212124))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            color = Color(0xFFF4F4F6),
            style = MangaType.bodyMedium,
        )
    }
}
