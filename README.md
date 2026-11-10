# MangaDock for Android

自用的 WebDAV 漫画阅读器。Kotlin + Jetpack Compose，不依赖任何在线漫画源，
只从自己的 NAS 上读 `.cbz` / 图片目录。

iOS 版在隔壁：[kar-liang/MangaDock](https://github.com/kar-liang/MangaDock)（SwiftUI）。
两个仓库分开维护，出包链路完全不同，不合并。

## 能干什么

- **书架**：最近加入 / 按阅读状态排序，隐私空间单独加锁（设备凭证或生物识别）
- **阅读器**：分页 / 条漫两种模式，cbz 与图片目录都支持，仿真卷页动画，进度自动记录
- **切章**：阅读页顶栏直接调章节列表跳章，不用退回详情页
- **文件页**：WebDAV 目录浏览器，直接读 NAS 上的文件夹
- **元数据**：可关联 Bangumi 条目，补封面 / 作者 / 评分 / 标签
- **下载与同步**：整本下载到本地，书架和进度走 WebDAV 同步
- **液态玻璃底栏**：三档（无 / 标准 / 极致），设置 → 外观里切
- **检查更新**：从自建更新源拉 APK 覆盖安装

## 构建

需要 JDK 21 和 Android SDK 35：

```bash
gradle :app:assembleDebug
```

`local.properties` 里的 `sdk.dir` 指向本机 SDK。仓库里没有 gradle wrapper，
用系统 gradle（开发时用的 8.9）或让 CI 的 `gradle/actions/setup-gradle` 装。

推送到 `main` 会自动跑 GitHub Actions 构建 debug APK，产物在 Actions 的
`mangadock-debug-apk` 工件里。

## 说明

个人自用项目，没有发布到应用商店，也没有附开源许可证 —— 代码可以看，
但没打算按某个许可证分发。底栏的液态玻璃材质参考了 FlClash 的视觉，
对照用的第三方源码不在本仓库里。
