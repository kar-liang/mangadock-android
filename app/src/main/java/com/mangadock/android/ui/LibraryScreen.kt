package com.mangadock.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mangadock.android.glass.GlassSource
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.settings.ServerAccount
import com.mangadock.android.settings.ServerKind
import com.mangadock.android.settings.newServerId
import kotlinx.coroutines.launch

/**
 * v98 资源库（对齐 iOS 2.5 的形态）。
 *
 * 底栏原来那个「文件」页签直接就是一台 WebDAV 的目录列表 —— 一台服务器时没问题，
 * 加第二台（或者换成 Komga）就没地方放了。现在改成**先看卡片、再进具体服务器**：
 *
 *   资源库（卡片墙）── 点卡片 ──▶ WebDAV 目录 / Komga 系列
 *
 * 卡片上的信息刻意只有三样：名字、备注（没填就显示地址）、类型角标。
 * 「当前」那台额外有个点 —— 书架 / 同步 / 应用更新都跟着它走，用户需要看得见。
 */

/** 卡片墙的列数。两列刚好放得下「名字 + 备注」，三列会挤成一行省略号。 */
private const val LIB_COLS = 2

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryPage(
    settings: AppSettings,
    onOpen: (ServerAccount) -> Unit,
    onAddClick: () -> Unit,
    onEdit: (ServerAccount) -> Unit,
    onActivate: (ServerAccount) -> Unit,
    onDelete: (ServerAccount) -> Unit,
    topInset: Dp = 0.dp,
    glass: GlassSource? = null
) {
    val c = MangaTheme.colors
    var menuFor by remember { mutableStateOf<ServerAccount?>(null) }
    var menuAt by remember { mutableStateOf(Rect.Zero) }

    if (settings.accounts.isEmpty()) {
        EmptyLibrary(onAddClick)
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(LIB_COLS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp + topInset,
                bottom = 100.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(settings.accounts, key = { it.id }) { acc ->
                var bounds by remember { mutableStateOf(Rect.Zero) }
                ServerCard(
                    acc = acc,
                    active = acc.id == settings.activeId,
                    modifier = Modifier
                        .onGloballyPositioned { bounds = it.boundsInRoot() }
                        .combinedClickable(
                            onClick = { onOpen(acc) },
                            onLongClick = { menuFor = acc; menuAt = bounds }
                        )
                )
            }
        }

        val target = menuFor
        if (target != null) {
            LongPressMenu(
                anchor = menuAt,
                glass = glass,
                onDismiss = { menuFor = null },
                entries = buildList {
                    if (target.id != settings.activeId) {
                        add(MenuEntry.Row("设为当前") {
                            menuFor = null
                            onActivate(target)
                        })
                    }
                    add(MenuEntry.Row("编辑") { menuFor = null; onEdit(target) })
                    // 只有一台时不给「删除」：删空了书架 / 同步全断，
                    // 而且这个 App 的核心就是「连着自己的服务器」，空资源库等于不能用。
                    if (settings.accounts.size > 1) {
                        add(MenuEntry.Divider)
                        add(MenuEntry.Row("删除", danger = true) {
                            menuFor = null
                            onDelete(target)
                        })
                    }
                }
            )
        }
    }
}

@Composable
private fun ServerCard(acc: ServerAccount, active: Boolean, modifier: Modifier = Modifier) {
    val c = MangaTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            // 不锁死宽高比：名字一行时卡片就该矮一点，硬撑成方块中间会空一大块。
            // 同一行里的卡片由网格自己对齐（取本行最高的那张）。
            .heightIn(min = 104.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(
                width = if (active) 1.5.dp else 0.5.dp,
                color = if (active) c.accent.copy(alpha = 0.55f) else c.divider,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindChip(acc.kind)
            Spacer(Modifier.width(6.dp))
            if (active) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(c.accent)
                )
                Spacer(Modifier.width(4.dp))
                Text("当前", style = MangaType.labelSmall, color = c.accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            acc.name,
            style = MangaType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            acc.subtitle,
            style = MangaType.bodySmall,
            color = c.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun KindChip(kind: ServerKind) {
    val c = MangaTheme.colors
    val isDav = kind == ServerKind.WEBDAV
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (isDav) c.accentSoft else c.chip)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            kind.label,
            style = MangaType.micro.copy(fontWeight = FontWeight.SemiBold),
            color = if (isDav) c.accent else c.body
        )
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    val c = MangaTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .clickable { onAdd() }
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.chip)
        )
        Spacer(Modifier.height(16.dp))
        Text("资源库是空的", style = MangaType.titleMedium, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "添加一台 WebDAV 或 Komga 服务器",
            style = MangaType.bodyMedium,
            color = c.sub
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(c.accent.copy(alpha = 0.12f))
                .padding(horizontal = 18.dp, vertical = 9.dp)
        ) {
            Text("添加服务器", style = MangaType.titleSmall, color = c.accent)
        }
    }
}

// ------------------------------------------------------------------ 添加 / 编辑

/**
 * 表单状态。刻意把「地址」拆成 主机 / 端口 / 路径 / HTTPS 四段 ——
 * 手打完整 URL 最容易漏掉协议头或者把端口写到路径里，iOS 那边已经按这个改了。
 *
 * [altRaw] 是**多行**文本，一行一个备用地址：家里内网 IP + 公网域名并行探测，
 * 这是这个 App 的核心用法，不能丢。
 */
data class ServerFormState(
    val id: String = "",
    val kind: ServerKind = ServerKind.WEBDAV,
    val name: String = "",
    val note: String = "",
    val https: Boolean = false,
    val host: String = "",
    val port: String = "",
    val path: String = "",
    val altRaw: String = "",
    val user: String = "",
    val pass: String = "",
    val root: String = "/"
) {
    /** 拼出来的主地址，保存前拿它判断有没有填够。 */
    val primary: String get() = joinUrl(https, host, port, path)

    fun toAccount(): ServerAccount {
        val urls = buildList {
            primary.takeIf { it.isNotBlank() }?.let { add(it) }
            altRaw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val full = if (line.startsWith("http://", true) ||
                        line.startsWith("https://", true)
                    ) {
                        line.trimEnd('/')
                    } else {
                        joinUrl(https, line, port, path)
                    }
                    if (full.isNotBlank() && !contains(full)) add(full)
                }
        }
        return ServerAccount(
            // 编辑时**必须**沿用老 id：id 变了等于换了一台服务器，
            // 阅读进度 / 离线下载 / 目录缓存全按路径记在账号上，会一起断掉。
            id = id.ifBlank { newServerId() },
            kind = kind,
            name = name.trim().ifBlank { host.trim().substringBefore(':').ifBlank { kind.label } },
            note = note.trim(),
            urls = urls,
            user = user.trim(),
            pass = pass,
            root = root.trim().ifBlank { "/" }
        )
    }

    companion object {
        /** 从已有账号还原成表单。地址拆段失败就整体塞进主机，用户自己再改。 */
        fun of(acc: ServerAccount): ServerFormState {
            val p = splitUrl(acc.primary)
            val alts = acc.urls.drop(1).joinToString("\n")
            return ServerFormState(
                id = acc.id,
                kind = acc.kind,
                name = acc.name,
                note = acc.note,
                https = p.https,
                host = p.host,
                port = p.port,
                path = p.path,
                altRaw = alts,
                user = acc.user,
                pass = acc.pass,
                root = acc.root
            )
        }

        fun blank(kind: ServerKind) = ServerFormState(kind = kind)
    }
}

private data class UrlParts(
    val https: Boolean = false,
    val host: String = "",
    val port: String = "",
    val path: String = ""
)

private fun splitUrl(raw: String): UrlParts {
    val s = raw.trim()
    if (s.isBlank()) return UrlParts()
    val https = s.startsWith("https://", true) || s.startsWith("https:", true)
    val noScheme = s.substringAfter("://", s)
    val authority = noScheme.substringBefore('/')
    val path = noScheme.substringAfter('/', "").trim('/')
    // 主机里可能自带端口（用户直接粘了一段地址），拆出来填到端口框里
    val host = authority.substringBeforeLast(':', authority)
    val maybePort = if (authority.contains(':')) authority.substringAfterLast(':') else ""
    val port = maybePort.takeIf { it.all { ch -> ch.isDigit() } }.orEmpty()
    return UrlParts(
        https = https,
        host = host,
        port = port,
        path = path
    )
}

/**
 * 拼地址。用户可能把 `192.168.1.10:5005` 整个粘进主机框，这里顺手兼容：
 * 主机里带端口时后面的 [port] 就不重复拼了。
 */
private fun joinUrl(https: Boolean, hostRaw: String, portRaw: String, pathRaw: String): String {
    var host = hostRaw.trim()
        .removePrefix("http://").removePrefix("https://")
        .substringBefore('/')
    if (host.isBlank()) return ""
    var port = portRaw.trim().filter { it.isDigit() }
    if (host.count { it == ':' } == 1) {
        val p = host.substringAfterLast(':')
        if (p.all { it.isDigit() }) {
            if (port.isBlank()) port = p
            host = host.substringBeforeLast(':')
        }
    }
    val path = pathRaw.trim().trim('/')
    return buildString {
        append(if (https) "https://" else "http://")
        append(host)
        if (port.isNotBlank()) append(':').append(port)
        if (path.isNotBlank()) append('/').append(path)
    }
}

/**
 * 添加 / 编辑表单。整屏覆盖（不是小弹窗）—— 七段字段塞进 348dp 的 alert 里
 * 会一路顶到状态栏，键盘一弹就看不见正在改的那一项。
 *
 * [onTest] 传进来而不是在这里直接打网络：测试要按类型分派（WebDAV 走 PROPFIND、
 * Komga 走 users/me），那是上层才知道的事。
 */
@Composable
fun ServerFormOverlay(
    initial: ServerFormState,
    onCancel: () -> Unit,
    onSave: (ServerAccount) -> Unit,
    onTest: suspend (ServerAccount) -> String
) {
    val c = MangaTheme.colors
    var f by remember(initial) { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val canSave = f.host.isNotBlank()
    val isNew = initial.id.isBlank()

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏：取消 / 标题 / 保存 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "取消",
                    style = MangaType.titleSmall,
                    color = c.sub,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCancel() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (isNew) "添加 ${f.kind.label}" else "编辑服务器",
                    style = MangaType.titleLarge,
                    color = c.ink
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "保存",
                    style = MangaType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (canSave) c.accent else c.chevron,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = canSave) { onSave(f.toAccount()) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                FormSection("名称")
                FormField("显示名称", f.name, { f = f.copy(name = it) }, "留空就用主机名")
                FormField("备注（可选）", f.note, { f = f.copy(note = it) }, "比如「家里 NAS」")

                FormSection("地址")
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(2f)) {
                        FormField(
                            "主机",
                            f.host,
                            { f = f.copy(host = it) },
                            "192.168.1.10",
                            KeyboardType.Uri
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        FormField(
                            "端口",
                            f.port,
                            { f = f.copy(port = it.filter { ch -> ch.isDigit() }) },
                            if (f.https) "443" else "80",
                            KeyboardType.Number
                        )
                    }
                }
                FormField(
                    "路径（可选）",
                    f.path,
                    { f = f.copy(path = it) },
                    // Komga 的接口都在 /api/v1 下，填了站点根以外的路径反而会 404
                    if (f.kind == ServerKind.KOMGA) "留空 = 站点根" else "如 dav"
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("使用 HTTPS", style = MangaType.titleSmall, color = c.ink)
                        Text(
                            "自签证书也能连（这个 App 本来就只连自己的服务器）",
                            style = MangaType.bodySmall,
                            color = c.sub
                        )
                    }
                    Switch(checked = f.https, onCheckedChange = { f = f.copy(https = it) })
                }
                FormField(
                    "备用地址（可选，一行一个）",
                    f.altRaw,
                    { f = f.copy(altRaw = it) },
                    "内网 IP 和公网域名各写一行，开机自动挨个试",
                    multiline = true
                )

                FormSection("登录")
                FormField("账号", f.user, { f = f.copy(user = it) })
                FormField("密码", f.pass, { f = f.copy(pass = it) }, password = true)

                if (f.kind == ServerKind.WEBDAV) {
                    FormSection("书架")
                    FormField(
                        "漫画根路径",
                        f.root,
                        { f = f.copy(root = it) },
                        "服务器上的 / 目录，留空就是根"
                    )
                }

                // ---- 测试连接：真的发一次请求，不是「跳过去看看」 ----
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(c.surface)
                        .border(0.5.dp, c.divider, RoundedCornerShape(11.dp))
                        .clickable(enabled = canSave && !testing) {
                            testing = true
                            testResult = null
                            val acc = f.toAccount()
                            scope.launch {
                                testResult = onTest(acc)
                                testing = false
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            testing -> "测试中…"
                            else -> "测试连接"
                        },
                        style = MangaType.titleSmall,
                        color = if (canSave) c.accent else c.chevron
                    )
                }
                testResult?.let {
                    Text(
                        it,
                        style = MangaType.bodySmall,
                        color = c.sub,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun FormSection(title: String) {
    Text(
        title,
        style = MangaType.labelMedium,
        color = MangaTheme.colors.sub,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    multiline: Boolean = false
) {
    val c = MangaTheme.colors
    Column(Modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, style = MangaType.bodySmall) },
            singleLine = !multiline,
            minLines = if (multiline) 2 else 1,
            visualTransformation = if (password) PasswordVisualTransformation()
            else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboard
            ),
            modifier = Modifier.fillMaxWidth()
        )
        // 提示放**输入框下面**而不是 placeholder：placeholder 只在获得焦点后
        // 才出现，等于没填的时候看不到该怎么填。这类提示（「留空 = 站点根」）
        // 恰恰是在没填的时候最需要看到。
        if (hint != null) {
            Text(
                hint,
                style = MangaType.bodySmall,
                color = c.chevron,
                modifier = Modifier.padding(start = 4.dp, top = 3.dp)
            )
        }
    }
}

/** 卡片墙右上角的「＋」：先问类型，再进表单。 */
@Composable
fun AddKindDialog(
    onDismiss: () -> Unit,
    onPick: (ServerKind) -> Unit,
    glass: GlassSource? = null
) {
    val c = MangaTheme.colors
    com.mangadock.android.ui.prism.PrismDialog(
        title = "添加服务器",
        onDismissRequest = onDismiss,
        glass = glass,
        actions = listOf(
            com.mangadock.android.ui.prism.PrismDialogAction("取消") { onDismiss() }
        ),
    ) { close ->
        // 两个类型做成可点的行，而不是并排两个按钮 —— PrismDialog 会把
        // 「最后一项」画成强调色的主操作，两个平级的选择被分主次会误导。
        Column(Modifier.padding(top = 4.dp)) {
            ServerKind.entries.forEach { kind ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { close { onPick(kind) } }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    Text(
                        kind.label,
                        style = MangaType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = c.ink
                    )
                    Text(kind.desc, style = MangaType.bodySmall, color = c.sub)
                }
            }
        }
    }
}
