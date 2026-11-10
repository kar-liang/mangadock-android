package com.mangadock.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mangadock.android.BuildConfig
import com.mangadock.android.settings.AppSettings
import com.mangadock.android.webdav.WebDavClient
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 应用内更新 —— 和 Flutter 版**同一套方案**（`lib/update.dart`）。
 *
 * 方案本体就四步，没有服务端、没有自建接口：
 *  1. 更新清单是 WebDAV 上的一份 JSON（`updates/latest-android.json`），
 *     放的位置和同步文件同一棵树 —— 用户已经在用那个服务器了，不用再记第二个地址；
 *  2. 清单里给 versionCode / 版本名 / apk 文件名 / sha256 / 更新说明；
 *  3. 下载到 `cacheDir/updates/`，先落 `.part`，校验通过才改名；
 *  4. 交给系统安装器（FileProvider + ACTION_VIEW），**不假装能静默安装** ——
 *     静默安装要系统签名或 root，正规 App 只能拉起安装器让用户点确认。
 *
 * ## 为什么文件名不叫 latest.json
 *
 * Flutter 版的清单是 `updates/latest.json`，它的 versionCode 是 Flutter 的
 * 构建号（build34 → 34）。两套包的 versionCode 完全不是一个体系：拿同一份清单，
 * 原生这边（versionCode 8x）永远判定"有新版"，然后把 **Flutter 的 APK** 下下来
 * 覆盖自己 —— 包名相同，真会装上。所以这里固定读 `latest-android.json`，
 * 两份清单各管各的，同一个 updates 目录互不干扰。
 */
object Updater {

    /** 清单与安装包所在的子目录（相对同步目录） */
    const val DIR_NAME = "updates"

    /** 原生版的清单文件名，别改成 latest.json，理由见类注释 */
    const val MANIFEST_NAME = "latest-android.json"

    /** 当前安装包的版本。直接读 BuildConfig，别再手抄一遍版本号。 */
    data class Version(val code: Int, val name: String) {
        val text: String get() = "$name ($code)"
    }

    val current: Version get() = Version(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)

    /**
     * NAS 上的更新清单。格式刻意做得手写也不会写错：
     * ```json
     * {
     *   "versionName": "2.0.83-android.1",
     *   "versionCode": 83,
     *   "apk": "MangaDock-Android-v83-update.apk",
     *   "sha256": "…（可选，填了就校验）",
     *   "notes": "加了在线更新"
     * }
     * ```
     * `apk` 相对路径 = 相对清单所在目录；也可以写 WebDAV 绝对路径。
     */
    data class Manifest(
        val code: Int,
        val versionName: String,
        /** 已解析成 WebDAV 路径，形如 `/Mangadock/updates/xxx.apk` */
        val path: String,
        val sha256: String?,
        val notes: String
    )

    sealed interface CheckResult {
        /** 清单读到了，且比本机新 */
        data class Found(val manifest: Manifest) : CheckResult

        /** 清单读到了，但不比本机新 */
        data class Current(val latest: Version) : CheckResult

        data class Fail(val message: String) : CheckResult
    }

    sealed interface FetchResult {
        data class Ok(val file: File) : FetchResult

        data class Fail(val message: String) : FetchResult
    }

    // ------------------------------------------------------------ 路径

    /**
     * 清单在服务器上的路径。
     *
     * 和 `SyncStore.fileUrl` 用同一套目录语义：同步目录为空 = 服务器根目录。
     * 两处必须一致，否则用户改了同步目录、更新却还去老地方找。
     */
    fun manifestPath(settings: AppSettings): String {
        val dir = settings.syncDir.trim().trim('/')
        return if (dir.isEmpty()) "/$DIR_NAME/$MANIFEST_NAME"
        else "/$dir/$DIR_NAME/$MANIFEST_NAME"
    }

    private fun dirOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i <= 0) "" else path.substring(0, i)
    }

    /** 相对路径按清单所在目录解析；绝对路径原样；完整 URL 取它的 path 部分。 */
    private fun resolveApk(apk: String, baseDir: String): String {
        val t = apk.trim()
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            val p = runCatching { Uri.parse(t).path }.getOrNull().orEmpty()
            return if (p.isNotBlank()) p else "/$DIR_NAME/${t.substringAfterLast('/')}"
        }
        if (t.startsWith("/")) return t
        return "$baseDir/${t.trimStart('/')}".replace(Regex("/+"), "/")
    }

    // ------------------------------------------------------------ 查

    /** 查清单。所有失败都翻成人话返回，不抛异常（界面上直接显示）。 */
    suspend fun check(settings: AppSettings): CheckResult {
        if (!settings.webdavConfigured) {
            return CheckResult.Fail("还没配置服务器。\n更新清单放在同步目录的 $DIR_NAME 文件夹里。")
        }
        val base = WebDavClient.normalizeBase(settings.webdavUrl)
            ?: return CheckResult.Fail("服务器地址填得不对。")
        val path = manifestPath(settings)
        val url = WebDavClient.fileUrl(settings.webdavUrl, path)
            ?: return CheckResult.Fail("清单地址拼不出来：$base$path")
        val auth = WebDavClient.authHeader(settings.webdavUser, settings.webdavPass)

        val manifest = when (val blob = WebDavClient.readFile(url, auth)) {
            is WebDavClient.Blob.Missing -> return CheckResult.Fail(
                "服务器上没有更新清单：\n$path\n\n" +
                    "把 $MANIFEST_NAME 和 APK 一起放进这个目录就能用了。"
            )

            is WebDavClient.Blob.Fail -> return CheckResult.Fail(blob.message)
            is WebDavClient.Blob.Ok -> parse(blob.bytes, dirOf(path))
                ?: return CheckResult.Fail("清单格式不对：读不到 versionCode / apk 字段。")
        }

        return if (manifest.code > current.code) CheckResult.Found(manifest)
        else CheckResult.Current(Version(manifest.code, manifest.versionName))
    }

    private fun parse(bytes: ByteArray, baseDir: String): Manifest? = runCatching {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val code = o.optInt("versionCode", 0)
        val apk = o.optString("apk").trim()
        if (code <= 0 || apk.isEmpty()) return@runCatching null
        val name = o.optString("versionName").trim().ifEmpty { code.toString() }
        val sha = o.optString("sha256").trim().lowercase()
        Manifest(
            code = code,
            versionName = name,
            path = resolveApk(apk, baseDir),
            sha256 = sha.ifEmpty { null },
            notes = o.optString("notes").trim()
        )
    }.getOrNull()

    // ------------------------------------------------------------ 下

    /**
     * 下载安装包到 `cacheDir/updates/`。
     *
     * 先落 `.part`，sha256（清单填了才校验）通过才 rename —— 断网留半包的话，
     * 下一次点安装会装上一个截断的 APK，系统只会报"解析失败"，很难查。
     *
     * 进度不通过回调往外抛（回调在 IO 线程，逐字节往 Compose state 里写要不停切线程），
     * 而是写在 [lastProgress] 里，界面按 100ms 读一次即可。
     */
    suspend fun download(
        ctx: Context,
        settings: AppSettings,
        m: Manifest
    ): FetchResult {
        lastProgress = -1f
        val dir = File(ctx.cacheDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            return FetchResult.Fail("建不了缓存目录：${dir.absolutePath}")
        }
        val name = m.path.substringAfterLast('/').ifEmpty { "update.apk" }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        if (target.exists()) target.delete()
        if (part.exists()) part.delete()

        val url = WebDavClient.fileUrl(settings.webdavUrl, m.path)
            ?: return FetchResult.Fail("安装包地址拼不出来：${m.path}")
        val auth = WebDavClient.authHeader(settings.webdavUser, settings.webdavPass)

        val err = WebDavClient.downloadFile(url, auth, part) { read, total ->
            lastProgress = if (total > 0) read.toFloat() / total else -1f
        }
        if (err != null) {
            part.delete()
            lastProgress = -1f
            return FetchResult.Fail(err)
        }

        if (m.sha256 != null) {
            val digest = runCatching { sha256(part) }.getOrNull()
            if (digest == null || !digest.equals(m.sha256, ignoreCase = true)) {
                part.delete()
                lastProgress = -1f
                return FetchResult.Fail("安装包校验失败（sha256 不一致），可能没传完，重新试一次。")
            }
        }
        if (part.length() <= 0L) {
            part.delete()
            lastProgress = -1f
            return FetchResult.Fail("下载到的是空文件。")
        }
        if (!part.renameTo(target)) {
            part.delete()
            lastProgress = -1f
            return FetchResult.Fail("改名失败，缓存目录可能不可写。")
        }
        lastProgress = 1f
        return FetchResult.Ok(target)
    }

    /**
     * 最近一次下载进度（0..1，-1 = 长度未知）。
     *
     * 放在 object 里是为了避开「回调在 IO 线程、Compose state 要主线程」这件事：
     * 界面按 100ms 轮询这个值画进度条就行，不需要给每个字节都切一次线程。
     */
    @Volatile
    var lastProgress: Float = -1f
        private set

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 装

    /** 设备是否允许本应用安装 APK（Android 8+ 的「安装未知应用」开关）。 */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true

    /** 跳到本应用的「安装未知应用」授权页。 */
    fun openInstallSettings(ctx: Context) {
        val i = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** 拉起系统安装器。返回 false = 系统没接（没授权 / 文件被删了）。 */
    fun install(ctx: Context, apk: File): Boolean {
        if (!apk.exists()) return false
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        }.getOrNull() ?: return false
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(i) }.isSuccess
    }
}
