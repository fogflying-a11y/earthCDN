# 馒头地球 CDN 改造更新日志

## 概述

在原项目 https://github.com/oxoooo/earth 的基础上，本版本主要引入了 **Cloudinary CDN 转发** 来获取 Himawari 卫星图像，原大佬的APP接口基本被jma下线了。为保活App引入Cloudinary CDN取代了原有的直连 NICT 服务器方案，提升了在国内网络环境下的可用性和速度。

---

## 核心变更

### 1. 新增 Cloudinary CDN 图像获取机制

- 新增 [CloudinaryClient](app/src/main/java/ooo/oxo/apps/earth/cdn/CloudinaryClient.java) 类，纯 Fetch 模式，通过 Cloudinary 的 `image/fetch` 功能代理 Himawari 原图
- [EarthFetcher.java](app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java) 完全重写：
  - 从单图下载改为 **分块拉取（2×2/3×3/4×4）+ 拼接合成** 的方式
  - 新增基于时间戳的文件缓存，同一小时内不重复下载
  - 使用 Glide 4 的 Bitmap 模式逐块拉取 550×550 的瓦片并拼接为完整图像
  - 输出指定分辨率的 PNG 图片

### 2. 设置页面新增 CDN 配置

- [settings.xml](app/src/main/res/xml/settings.xml) 新增 `PreferenceCategory` + `EditTextPreference`，用户可输入 Cloudinary Cloud Name
- [SettingsFragment.java](app/src/main/java/ooo/oxo/apps/earth/SettingsFragment.java) 增加 `setupSummaries()` 方法，实现 cloud_name 的实时保存和验证
- 字符串资源新增 `cdn_category_title`、`cdn_cloud_name_title`、`cdn_cloud_name_summary`、`save_long_press`

### 3. 数据库与 ContentProvider 升级

- [SettingsContract.java](app/src/main/java/ooo/oxo/apps/earth/provider/SettingsContract.java) 新增 `CDN_CLOUD_NAME` 字段
- [SettingsProvider.java](app/src/main/java/ooo/oxo/apps/earth/provider/SettingsProvider.java) 数据库版本从 v3 升至 v5，添加 `cdn_cloud_name` 列迁移
- [Settings.java](app/src/main/java/ooo/oxo/apps/earth/dao/Settings.java) 新增 `cdnCloudName` 属性
- [EarthsProvider.java](app/src/main/java/ooo/oxo/apps/earth/provider/EarthsProvider.java) 数据库版本升至 v2，升级时清空旧图像记录

### 4. EarthSyncImpl 同步逻辑简化

- [EarthSyncImpl.java](app/src/main/java/ooo/oxo/apps/earth/EarthSyncImpl.java)：
  - 移除旧版 `loadLatestEarth()` 时间间隔检查逻辑
  - 移除友盟统计（`MobclickAgent`）相关调用
  - 每次同步动态创建带 `CloudinaryClient` 的 `EarthFetcher`
  - 新增详细日志输出，方便排查同步问题

### 5. 后台服务适配 Android 14+

- [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 新增 `FOREGROUND_SERVICE_DATA_SYNC` 权限
- [EarthBackgroundService.java](app/src/main/java/ooo/oxo/apps/earth/background/EarthBackgroundService.java) 适配 Android 14 前台服务类型，使用 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`
- [EarthApplication.java](app/src/main/java/ooo/oxo/apps/earth/EarthApplication.java) 移除友盟初始化

### 6. 沉浸式全屏现代化改造

- [ImmersiveUtil.java](app/src/main/java/ooo/oxo/apps/earth/widget/ImmersiveUtil.java) 从老旧的 `SYSTEM_UI_FLAG` 方案迁移到 `WindowInsetsControllerCompat`
- 删除 [SystemUiVisibilityUtil.java](app/src/main/java/ooo/oxo/apps/earth/widget/SystemUiVisibilityUtil.java)（已被 `WindowInsetsControllerCompat` 取代）
- [MainActivity.java](app/src/main/java/ooo/oxo/apps/earth/MainActivity.java) 使用 `WindowCompat.setDecorFitsSystemWindows` 替代旧的系统 UI 标志

### 7. 构建系统全面升级

- Gradle Plugin：`3.5.3` → `8.9.1`
- compileSdk：`29` → `36`
- minSdk：`16` → `21`
- targetSdk：`29` → `36`
- Java 兼容性：`1.8` → `11`
- Glide：`3.7.0` → `4.16.0`（使用 Annotation Processor）
- 移除 jcenter，迁移到 mavenCentral
- 移除友盟 SDK（`umsdk`）及 bintray 仓库
- 移除自建 Google Play Services 7.8.87 本地仓库，升级至 `18.1.0`
- 新增 `namespace 'ooo.oxo.apps.earth'`（AGP 8.x 要求）
- build.gradle 中的 git 信息获取增加异常处理

### 8. Wear OS 适配

- [wear/build.gradle](wear/build.gradle) 同步升级依赖版本
- [wear/AndroidManifest.xml](wear/src/main/AndroidManifest.xml) 新增 `android:exported` 声明，Wear 2.0 Data API 适配
- 手表端同步服务适配新版 Wearable Data API

### 9. Manifest 规范化

- 所有组件显式声明 `android:exported` 属性（Android 12+ 要求）
- `WRITE_EXTERNAL_STORAGE` 限定 `maxSdkVersion="28"`
- 手表端 Wearable 监听器从废弃的 `BIND_LISTENER` 改为 `DATA_CHANGED` / `CAPABILITY_CHANGED`
- 移除 BUG_HD SDK 配置

### 10. 文案与国际化

- 修复"将会**已**最低分辨率"→ "将会**以**最低分辨率"错别字
- 关于页增加说明文字：基于 Claude Code 添加 CDN 功能
- "卫星影像来自" 更新为 "卫星影像来自向日葵9号卫星，经 CloudinaryCDN 获取"
- "保存按钮上方" 新增 "长按按钮进入 CDN 配置" 提示（中英文双语）

---

## 后续修复（2026-06-27）

### 11. 时间戳获取方式改进

**文件**：[EarthFetcher.java](app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java)

**问题**：原逻辑在本地通过 `floor(UTC - 90min, 整小时)` 盲猜时间戳，但 NICT 实际每 10 分钟更新一帧（非整点），导致常下载到过时图像或空结果。

**修复**：
- 新增 `getLatestImageId()` 方法，请求 `himawari.asia/img/D531106/latest.json` 获取**精确的最新可用帧时间戳**
- 删除旧的 `getQuantizedImageId()` 本地时间计算方法
- API 返回失败时抛出异常，由上层调度器统一处理重试
- 移除不再需要的 `Calendar`、`TimeZone` 导入

**缓存键变化**：
- 旧：`earth_2026_06_27_140000.png`（1 个/小时，盲猜）
- 新：`earth_2026_06_27_142000.png`（1 个/10 分钟，精确匹配源站）

### 12. BackgroundService 调度 Bug 修复

**文件**：[EarthBackgroundService.java](app/src/main/java/ooo/oxo/apps/earth/background/EarthBackgroundService.java)

**问题 1（严重）**：`run()` 同步成功后不再调用 `schedule()`，导致服务在首次同步完成后**静默停止**，不再定期更新壁纸。

**问题 2（严重）**：`schedule()` 方法硬编码 10 分钟，用户在主界面通过滑块设置的更新间隔（`settings.interval`）在 BackgroundService 模式下**完全失效**。

**修复**：
- `run()` 方法末尾统一调用 `schedule()`，无论同步成功或失败均调度下一次运行
- 新增 `getIntervalMs()` 方法，从 ContentProvider 读取用户设置的更新间隔，兜底默认值 10 分钟
- 新增 `TAG` 常量用于日志输出
- 同步失败时通知栏显示 `"Last update: HH:MM (failed)"`（修复前为 null，用户无法感知失败）

**修复后两条更新路径的行为**：

| 路径 | `settings.interval` 是否生效 |
|------|---------------------------|
| SyncAdapter（系统调度）| ✅ 通过 `delayUntil` 告知系统 |
| BackgroundService（前台服务）| ✅ 通过 `getIntervalMs()` 读取并调度 |

### 13. Android Studio Gradle 同步错误 Workaround

**文件**：[build.gradle](build.gradle)（根项目）

**问题**：Android Studio（特定版本）在 Gradle 同步/构建时会错误地在子项目（`:app`、`:wear`）上调用只存在于根项目的任务，导致构建失败：

```
Task 'wrapper' not found in project ':app'.
Task 'prepareKotlinBuildScriptModel' not found in project ':app'.
```

CLI 构建（`./gradlew :app:assembleGoogleDebug`）不受影响。

**修复**：在根 `build.gradle` 的 `subprojects` 块中为所有子项目注册这两个任务：

```groovy
subprojects {
    // `wrapper` 任务只存在于根项目；委托给根项目执行
    task wrapper {
        dependsOn rootProject.tasks.wrapper
    }

    // Android Studio 的 Kotlin 插件同步时会调用此任务；
    // 本项目使用 Groovy DSL，注册为空操作即可
    task prepareKotlinBuildScriptModel {
        doLast {}
    }
}
```

> 此 workaround 可在升级 Android Studio 后尝试移除。如不再出现相关错误，可安全删除该 `subprojects` 块。

### 14. 其他调整

- 根 `build.gradle` 中 Gradle wrapper 相关文件自动更新（`validateDistributionUrl=true` 等新配置）
- `食用指南.md` 重写：新增「更新机制说明」章节，补充双模式、缓存策略、常见问题等
- `README.md` 重写：反映当前完整功能特性、技术栈版本、致谢信息

- 保存按钮上方新增 "长按按钮进入 CDN 配置" 提示（中英文双语）

---

## 后续优化（2026-07-30）

### 15. Fetch URL 字节级对齐桌面端（跨端 CDN 缓存共享）

**文件**：[CloudinaryClient.java](app/src/main/java/ooo/oxo/apps/earth/cdn/CloudinaryClient.java)、[README.md](README.md)

**问题**：本端 Fetch URL 形如 `.../image/fetch/f_auto,q_auto/https://himawari.asia/...`，与桌面客户端 EarthLiveSharp（ELS）生成的 `.../image/fetch/https%3A//himawari.asia/...` 字符串不一致。同一 Cloud Name 下 Cloudinary 将两端请求视为**不同资源**：跨端缓存无法复用，每帧每瓦片双倍消耗 Transformation Credit，违背《CDN优化指南》的共享缓存原则。

**修复**：
- `buildFetchUrl()` 移除 `/f_auto,q_auto/` 转换段，改为 plain fetch 模式
- 仅将源站 URL 的冒号 `:` 替换为 `%3A`，**斜杠保留字面量**（禁止 `URLEncoder.encode()` 全局转码，与 ELS 规则一致）
- 补充 `originalUrl == null` 守卫
- 新格式：`https://res.cloudinary.com/{cloud}/image/fetch/https%3A//himawari.asia/img/D531106/...`
- `testConnection()` 经 `buildFetchUrl()` 委托自动继承新格式（已核实不附加任何随机数/时间戳 query 参数，缓存键确定性不受影响）

**效果**：两端对同一卫星瓦片生成字节级一致的 URL，同一 Cloud Name 下多端 100% 复用同一份 CDN 缓存——每帧仅首个请求的设备消耗 1 次 Transformation Credit，其余设备由 CDN 边缘节点直接响应（0 转换消耗）。

**取舍**：plain fetch 交付源站原始 PNG（比 WebP/AVIF 大约 60%+），移动端单次流量上升；换取跨端缓存共享与 Credit 消耗减半。经权衡采用共享缓存优先方案。

### 16. 瓦片重试机制优化（瞬时错误识别 + 指数退避）

**文件**：[EarthFetcher.java](app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java)

**问题**：`collectAndStitch()` 对失败瓦片无条件重试 3 次、重试间无间隔、不区分错误类型——HTTP 4xx（如 cloud_name 错误导致的 401/403、瓦片未生成的 404）等不可恢复错误也会空耗 3 轮网络请求，拖慢 SyncAdapter 同步任务。

**修复**：
- 新增 `isTransient(Throwable)`：遍历异常链（含 `GlideException.getRootCauses()`，因 Glide 会包装网络异常）。判定规则对齐 ELS `CloudinaryUpload.IsTransient`：
  - `SocketTimeoutException`（超时）、`SocketException`（含 `ConnectException` 连接失败、连接重置/中断）、HTTP 5xx → **可重试**
  - HTTP 4xx、DNS 解析失败（`UnknownHostException`）→ **不可恢复**
- 重试前引入指数退避：第 1/2/3 次重试分别等待 1s / 2s / 4s（`TILE_RETRY_BASE_DELAY_MS = 1000`）
- 不可恢复错误 → 立即放弃该瓦片并**终止整帧同步**（缺块图像不可用）；异常消息含 "tile" 字样，`EarthSyncImpl` 既有 `ERROR_TILE_FETCH_FAILED` 分类逻辑无需改动
- 取消语义保持：`cancelled` 标志、`CancellationException`、`InterruptedException` 均归一为 `"fetch cancelled"`
- 初始并行收集阶段不变，错误分类只作用于重试阶段

### 17. 其他调整

- `README.md`：顶部英文简介更正为 plain fetch + 共享缓存表述（移除已不实的 "auto format optimization (WebP/AVIF)"）；「核心变更」新增「CDN 缓存共享与重试优化（2026-07-30）」章节；架构图标注「同一 Cloud Name 下多端共享」
- 验证：全局搜索 `f_auto|q_auto` 在 `app/` 下 0 命中；`:app:assembleGoogleDebug` 编译通过；48 小时本地帧缓存逻辑（`earth_{imageId}.png` 命名与 `cleanOldCache`）未受影响

### 18. 恢复源站直连模式（双模式：CDN / 源站）

**文件**：[EarthFetcher.java](app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java)、[EarthSyncImpl.java](app/src/main/java/ooo/oxo/apps/earth/EarthSyncImpl.java)、[MainActivity.java](app/src/main/java/ooo/oxo/apps/earth/MainActivity.java)、`values/strings.xml`、`values-zh-rCN/strings.xml`、[README.md](README.md)

**动机**：此前为严格 CDN-only，`cloud_name` 未配置时同步直接被拒。对于没有 Cloudinary 账户的用户（或 CDN 故障场景），应用完全不可用。本次打开源站获取能力，**Cloud Name 留空 = 直连 himawari.asia 源站**，填入 = 走 Cloudinary CDN，无需新增开关。

**改动**：
- `EarthFetcher` 新增 `resolveTileUrl(originUrl)`：`cdnClient.hasValidCloudName()` 为 true 时包装为 Cloudinary fetch URL，否则返回源站裸 URL；`fetchAndStitch()` 与重试路径统一经此解析；日志区分 `cdn` / `origin` 模式
- `EarthSyncImpl` 移除 `ERROR_CDN_NOT_CONFIGURED` 提前返回块（空 cloud_name 现为合法配置）；同步日志补充 `mode=cdn|origin`
- `MainActivity` 移除启动时 "Cloud Name 未配置" 的误导性 Toast
- 设置项 summary 文案更新为「留空则直连源站获取」（中英双语），删除废弃的 `cdn_cloud_name_missing` 字符串

**健壮性说明**：源站直连与 CDN 走同一 Glide 下载路径，自动继承第 16 项的瞬时错误识别与 1s/2s/4s 退避重试，以及 48 小时本地帧缓存。实测源站当前健康度欠佳（TLS 握手约 35% 抖动、偶发 50x、TTFB 2–7s），直连模式下偶发同步失败属预期，由重试与下轮调度兜底。

**保留项**：`ERROR_CDN_NOT_CONFIGURED` 常量及通知映射分支不再产生但保留（避免 public API 连锁改动）；`testConnection` 在空 cloud name 时返回 "Cloud Name is empty"，语义不变。

---

## 源站下载健壮性修复（2026-07-30）

### 19. 源站瓦片下载失败修复（三端统一）

**涉及项目**：Mantou Earth (Android)、EarthLiveSharp (Windows)、EarthLiveSharp-CDNonly (Windows)

**问题**：源站直连模式下瓦片下载几乎必然失败。Android 端日志显示所有瓦片报 `HttpException(status code: -1)`，且首次重试即被误判为不可恢复错误终止。

**根因**：

| # | 问题 | 影响范围 |
|---|------|----------|
| 1 | Android `isTransient()` 将 HTTP status=-1（连接级别失败，无 HTTP 响应）误判为不可重试（-1 不 >= 500） | Android |
| 2 | Glide 默认超时仅 2.5s，源站 TTFB 2–7s，连接在服务器响应前即被断开 | Android |
| 3 | Windows 源站路径使用裸 `WebClient.DownloadFile()`，无超时控制、无重试 | EarthLiveSharp |
| 4 | Windows API 时间戳请求无重试，瞬时连接失败即放弃整轮更新 | 两个 Windows 版本 |

**修复**：

**Android（`EarthFetcher.java`）**：
- `isTransient()`：`code < 0 || code >= 500` 视为瞬时；新增 `UnknownHostException` 显式排除（DNS 不可恢复）；新增 `IOException` 兜底（SSLException、EOFException 等）
- Glide 请求设置 `HttpGlideUrlLoader.TIMEOUT = 30s`（替代默认 2.5s）
- 瓦片下载从并行改为**逐一顺序下载**（`downloadTileWithRetry()`），降低源站并发连接压力

**EarthLiveSharp（`Program.cs`）**：
- `GetImageID()`：新增 3 次重试 + 2s/4s 退避 + 15s 超时 + `using` 资源释放
- `SaveImage()`：源站路径从裸 `WebClient` 改为 `DownloadTileWithRetry()`（30s 超时 + 3 次重试 + 2s/4s/8s 退避），与 CDN 路径统一
- 方法重命名：`DownloadTileCdn` → `DownloadTileWithRetry`（CDN/源站通用）

**EarthLiveSharp-CDNonly（`Program.cs`）**：
- `GetLatestImageId()`：新增 3 次重试 + 2s/4s 退避，超时 10s→15s
- 源站路径从 `DownloadFile()`（无重试）改为 `DownloadFileWithRetry()`（3 次重试）

**三端统一行为**：

| 能力 | 规格 |
|------|------|
| 瓦片下载方式 | 逐一顺序（任何时刻仅 1 个活跃连接） |
| 瓦片 HTTP 超时 | 30s（连接 + 读取） |
| 瓦片重试 | 3 次，指数退避（Android 1s/2s/4s，Windows 2s/4s/8s） |
| 瞬时错误判定 | 超时、连接失败、连接关闭、HTTP 5xx、status=-1 |
| 不可恢复错误 | HTTP 4xx、DNS 解析失败 → 立即终止本帧 |
| API 时间戳重试 | 3 次 + 指数退避 |

**实测**：源站健康度仍欠佳（偶发 503、TTFB 2–7s），但重试机制可正确识别瞬时错误并退避重试，不再误判终止。
