package com.mangadock.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.ui.prism.PrismPopupCard
import com.mangadock.android.ui.prism.PrismPopupItem
import com.mangadock.android.ui.prism.PrismPopupSection

/**
 * 长按菜单的一项。
 *
 * 保留旧的调用方 API，内部已经整体换成 FlClash `popup.dart` 的 1:1 镜像
 * （见 `ui/prism/PrismPopup.kt`）。
 *
 * `Divider` 在这里转成源码 `CommonPopupMenuItem.section` 的语义：
 * 「本项及之后另起一组」，分隔线由菜单自己按 17dp / 1dp 画，
 * 不再让调用方数「第几项后面要画线」。
 */
sealed interface MenuEntry {
    data class Row(
        val label: String,
        /** 破坏性操作走 colorScheme.error，对应源码 `item.danger ? error : onSurface` */
        val danger: Boolean = false,
        val onClick: () -> Unit
    ) : MenuEntry

    data object Divider : MenuEntry
}

/**
 * v65：菜单 UI / 动画换成 FlClash `CommonPopupRoute` + `CommonPopupMenu` 的原值镜像。
 *
 * 之前 v37 那套是照 SenPlayer 截图手搓的（1260 宽屏量 675px / 106px / 32px、
 * 16dp 圆角、50dp 行高、140ms 缩放淡入、纯色卡片），全部作废。
 * 现在走：
 *  - 超椭圆 24dp 卡片 / 16dp 行高亮 / 8dp 内边距 / 160~280dp 自适应宽 / 44dp 行高
 *  - 15/20 主文字、13/18 次要文字、17dp 分隔线
 *  - 450ms 双弹簧 platter morph（400/.754 + 324/.75 延迟 .07s）
 *  - 200ms / easeOut / 缩到 0.8 向锚点淡出
 *  - menu 玻璃 blur 12 + tint .55/.63、29blur/8dp 单条阴影
 *  - `_PopupLayoutDelegate` 的 avoid 翻转与 16dp 屏幕安全边距
 *
 * @param anchor 被长按那一格的 bounds（root 坐标，px），同时充当 `avoid` rect。
 * @param glass  页面内容层，菜单的 backdrop 玻璃从它采样（与顶栏/底栏同一个 source）。
 */
@Composable
fun LongPressMenu(
    anchor: Rect,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    glass: GlassSource? = null,
) {
    val items = buildList {
        var pendingSection = false
        for (e in entries) {
            when (e) {
                MenuEntry.Divider -> pendingSection = true
                is MenuEntry.Row -> {
                    add(
                        PrismPopupItem(
                            label = e.label,
                            danger = e.danger,
                            onPressed = e.onClick,
                            section = if (pendingSection) PrismPopupSection() else null,
                        )
                    )
                    pendingSection = false
                }
            }
        }
    }
    PrismPopupCard(
        anchor = anchor,
        items = items,
        onDismiss = onDismiss,
        glass = glass,
    )
}
