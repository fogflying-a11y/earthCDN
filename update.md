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
- 保存按钮上方新增 "长按按钮进入 CDN 配置" 提示（中英文双语）
