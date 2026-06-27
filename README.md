Mantou Earth
==========

[![][license badge]](LICENSE)
[![][issues badge]][issues link]

Fetch photo of the earth every 10–120 minutes from [Himawari-9](https://himawari.asia/) (formerly Himawari-8, [wiki](https://en.wikipedia.org/wiki/Himawari_8)), and make them your live wallpaper.

Accelerating with Cloudinary CDN Fetch and using auto format optimization (WebP/AVIF) to reduce network traffic.

[![Get it on Google Play][play badge]][play link]

![](screenshot.jpg)

## Build

### Requirements

- Android SDK Platform 36
- JDK 11+

```shell
./gradlew assembleGoogleDebug
```

## Thanks

- Inspired by [EarthLiveSharp](https://github.com/bitdust/EarthLiveSharp)
- Images from [Himawari-9](https://himawari.asia/) (NICT science cloud project; switched from Himawari-8 in Nov 2025)

## Third-party libraries used

- [Glide](https://github.com/bumptech/glide)
- [Glide Transformations](https://github.com/wasabeef/glide-transformations)
- [AndroidX Preference](https://developer.android.com/jetpack/androidx/releases/preference)
- [Material Components](https://github.com/material-components/material-components-android)
- [Commons IO](https://commons.apache.org/proper/commons-io/)

## License

[GNU General Public License, version 3](LICENSE)

## Follow us on Wechat

![](qrcode.jpg)

---

## 本 Fork 更新内容（CDN 改造）

> 原项目接口已被 JMA 下线，本 Fork 引入 Cloudinary CDN 转发以保活 App。详细配置指引见 [食用指南](食用指南.md)，完整更新日志见 [update.md](update.md)。

### 核心变更

- **Cloudinary CDN Fetch**：新增 [CloudinaryClient](app/src/main/java/ooo/oxo/apps/earth/cdn/CloudinaryClient.java)，纯 Fetch 代理模式（无需 API Key/Secret，用户填入自己的 Cloud Name 即可）
- **瓦片分块下载 + 拼接**：[EarthFetcher.java](app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java) 改为 2×2 / 3×3 / 4×4 瓦片拉取 + 合成，支持 550p–4400p 多分辨率
- **API 时间戳**：从 `himawari.asia/img/D531106/latest.json` 实时获取最新可用帧（替代旧的本地时间盲猜逻辑）
- **源站迁移**：从 `himawari8-dl.nict.go.jp`（已 403 下线）迁移到 `himawari.asia`（NICT 科学云，URL 格式兼容）

### 架构

```
向日葵卫星源站 (himawari.asia)
         ↓ Cloudinary Fetch 代理
   Cloudinary CDN 缓存
         ↓
      你的设备
```

### Android 现代化适配

- 构建系统升级：AGP 8.9.1 / Gradle 8.11.1 / Java 11 / compileSdk & targetSdk 36 / minSdk 21
- Android 14+ 前台服务类型声明（`FOREGROUND_SERVICE_DATA_SYNC`）
- 沉浸式全屏改用 `WindowInsetsControllerCompat`
- Wear OS 同步服务适配新版 Wearable Data API
- 所有组件显式声明 `android:exported`（Android 12+ 要求）

### Bug 修复

- **BackgroundService 调度失效**：修复同步成功后不再调度下一次的问题；修复用户设置的更新间隔（10–120 分钟）在 BackgroundService 模式下被硬编码 10 分钟覆盖的问题
- **主界面预览不更新**：`EarthsProvider.insert()` 后补充 `notifyChange(LATEST_CONTENT_URI)` 通知
- **设置读取不一致**：`SettingsFragment.syncToContentProvider()` 改为只写入变更字段，避免默认值覆盖

### 新功能

- **双更新模式**：Android SyncAdapter（系统调度，更省电）或前台常驻服务（部分系统备选），均遵循用户设置的更新间隔
- **智能缓存**：按帧缓存（每 10 分钟一帧），48 小时自动清理
- **失败状态提示**：BackgroundService 模式下同步失败时，通知栏显示 `Last update: HH:MM (failed)`

[play badge]: https://developer.android.com/images/brand/en_generic_rgb_wo_45.png
[play link]: https://play.google.com/store/apps/details?id=ooo.oxo.apps.earth

[license badge]: https://img.shields.io/github/license/oxoooo/earth.svg?style=flat-square

[issues badge]: https://img.shields.io/github/issues/oxoooo/earth.svg?style=flat-square
[issues link]: https://github.com/oxoooo/earth/issues
