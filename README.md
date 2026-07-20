# Happymh for TachiJ2K

Happymh 漫画源的独立 GitHub 构建仓库，基于 Keiyoushi 上游 `v27` 改造成纯 TachiJ2K 扩展 API 1.4 版本。

> 本仓库只包含 Happymh API 1.4 插件源码，不包含 TachiJ2K 主程序源码，也不包含 API 1.6 兼容实现。

## 兼容信息

| 项目 | 值 |
| --- | --- |
| 扩展包名 | `eu.kanade.tachiyomi.extension.zh.happymh` |
| versionCode | `27` |
| versionName | `1.4.27` |
| 扩展 API | `1.4` |
| 最低 Android | API 21 |
| compile/target SDK | 34 |

该版本专门用于只支持旧扩展 API 1.4 的 TachiJ2K。

## 为什么需要兼容版

Keiyoushi 新版 Happymh 已迁移到新扩展接口和新版 Kotlin Serialization，停止更新的 TachiJ2K 无法加载这些 ABI。

本仓库将源实现改回 `HttpSource` 与 RxJava 1 接口，并使用 Android `org.json` 解析响应，避免向旧宿主引入新版 Serialization ABI，同时保留当前网站接口、章节分页、GA Cookie 和图片页解密逻辑。

## 仓库结构

```text
.
├── .github/workflows/build.yml     # GitHub Actions 自动构建
├── buildSrc/                       # Android SDK 构建常量
├── core/                           # 扩展公共 Android 资源模块
├── gradle/                         # 版本目录与 Gradle Wrapper
├── src/zh/happymh/                 # Happymh 插件源码
├── common.gradle                   # 扩展公共构建配置
└── settings.gradle.kts
```

## 本地构建

需要：

- JDK 17
- Android SDK Platform 34
- Android SDK Build Tools 34.0.0

Windows PowerShell：

```powershell
.\gradlew.bat :extensions:individual:zh:happymh:assembleDebug --no-daemon
```

Linux/macOS：

```bash
chmod +x ./gradlew
./gradlew :extensions:individual:zh:happymh:assembleDebug --no-daemon
```

APK 输出位置：

```text
src/zh/happymh/build/outputs/apk/debug/
```

## GitHub Actions 流程

`.github/workflows/build.yml` 会在以下场景运行：

- 推送任意分支
- Pull Request
- Actions 页面手动运行

流程会配置 Java 17 和 Android SDK 34、执行 Happymh 的 `assembleDebug`，然后把 APK 上传到该次运行的 Artifacts，保留 14 天。

## 安装

生成的 Debug APK 使用 Android 默认调试证书。如果手机里已安装相同包名、但签名不同的 Happymh，需要先卸载原插件再安装此 APK。操作前建议先在 TachiJ2K 中备份。

## 上游与许可

- 上游扩展仓库：<https://github.com/keiyoushi/extensions-source>
- 本 API 1.4 改造用于旧版 TachiJ2K；网站接口变化后可能需要继续维护。
- 代码依照仓库中的 Apache License 2.0 发布，参见 [LICENSE](LICENSE)。
