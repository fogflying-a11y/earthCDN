# Himawari 瓦片下载地址迁移记录

> 记录日期：2026-06-08
> 影响项目：所有依赖 `himawari8-dl.nict.go.jp` 获取卫星图像的应用

---

## 1. 问题描述

### 1.1 现象

应用在同步壁纸时报错，无法获取新的卫星图像。通过调试发现 `himawari8-dl.nict.go.jp` 返回 **HTTP 403 Forbidden**。

### 1.2 影响范围

- 直接通过 `himawari8-dl.nict.go.jp` 下载瓦片的应用
- 通过 Cloudinary Fetch CDN 代理该域名的应用
- 所有使用旧 URL 格式的壁纸应用（如 `himawaripy`、`himawari-8-chrome`、`himawari-8-wallpaper` 等）

---

## 2. 根因分析

### 2.1 NICT 域名状态对比（2026-06-08 测试）

| 域名 | 用途 | 状态 | 说明 |
|------|------|------|------|
| `himawari8-dl.nict.go.jp` | 旧瓦片下载服务器 | **403 Forbidden** | 已完全封锁，所有人无法访问 |
| `himawari8.nict.go.jp` | 展示页面 | **200 OK** | 可正常访问瓦片 |
| `himawari.asia` | 备用/CDN 域名 | **200 OK** | NICT 科学云项目托管 |
| `himawari9.nict.go.jp` | Himawari-9 展示页 | **200 OK** | 新一代卫星 |

### 2.2 封锁并非针对 Cloudinary

测试结论：
- `himawari8-dl.nict.go.jp` **对所有人都返回 403**（包括浏览器直接访问）
- NICT 不是在封锁 CDN，而是该下载子域名已经**整体下线**

### 2.3 背景：Himawari-8 → Himawari-9 切换

根据 [NOAA CoastWatch](https://coastwatch.noaa.gov/cwn/news/2025-11-21/switch-himawari-8-himawari-9.html) 等来源确认：
- 2025 年 11 月 26 日，Himawari-9 正式替代 Himawari-8 作为主要业务卫星
- NICT 随后可能逐步下线了旧的下载端点

---

## 3. 解决方案

### 3.1 新 URL 格式

将代码中的瓦片下载 URL 从：

```
https://himawari8-dl.nict.go.jp/himawari8/img/D531106/{grid}d/550/{yyyy}/{MM}/{dd}/{HHmmss}_{x}_{y}.png
```

改为：

```
https://himawari8.nict.go.jp/img/D531106/{grid}d/550/{yyyy}/{MM}/{dd}/{HHmmss}_{x}_{y}.png
```

### 3.2 关键变化

| 变化项 | 旧值 | 新值 |
|--------|------|------|
| 域名 | `himawari8-dl.nict.go.jp` | `himawari8.nict.go.jp` |
| 路径前缀 | `/himawari8/img/` | `/img/` |

### 3.3 验证结果

```
# 新 URL 测试
URL: https://himawari8.nict.go.jp/img/D531106/1d/550/2026/06/08/040000_0_0.png
HTTP状态码: 200
响应大小: 461,793 bytes
Content-Type: image/png
```

---

## 4. 代码变更（本项目）

### 4.1 修改文件：`EarthFetcher.java`

```diff
--- a/app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java
+++ b/app/src/main/java/ooo/oxo/apps/earth/EarthFetcher.java
@@ -45,7 +45,7 @@ public class EarthFetcher {
     private static final int TILE_RETRY_COUNT = 3;

     private static final String TILE_URL_TEMPLATE =
-            "https://himawari8-dl.nict.go.jp/himawari8/img/D531106/%dd/550/%04d/%02d/%02d/%s_%d_%d.png";
+            "https://himawari8.nict.go.jp/img/D531106/%dd/550/%04d/%02d/%02d/%s_%d_%d.png";

@@ -82,7 +82,7 @@ public class EarthFetcher {
     /**
      * Build the origin Himawari tile URL.
      * imageId format: "2026/05/29/130000"
-     * Result: https://himawari8-dl.nict.go.jp/himawari8/img/D531106/{grid}d/550/{yyyy}/{MM}/{dd}/{HHMMSS}_{x}_{y}.png
+     * Result: https://himawari8.nict.go.jp/img/D531106/{grid}d/550/{yyyy}/{MM}/{dd}/{HHMMSS}_{x}_{y}.png
      */
     private static String buildOriginUrl(String imageId, int grid, int x, int y) {
         ...
-                "https://himawari8-dl.nict.go.jp/himawari8/img/D531106/%s/550/%04d/%02d/%02d/%s_%d_%d.png",
+                "https://himawari8.nict.go.jp/img/D531106/%s/550/%04d/%02d/%02d/%s_%d_%d.png",
```

---

## 5. 其他项目迁移指引

### 5.1 需要搜索替换的关键词

```bash
# 搜索旧域名
grep -r "himawari8-dl.nict.go.jp" --include="*.py" --include="*.js" --include="*.java" --include="*.kt" --include="*.swift" .

# 搜索旧路径模式
grep -r "/himawari8/img/D531106" .
```

### 5.2 替换规则

```python
# Python 示例
old_url = "https://himawari8-dl.nict.go.jp/himawari8/img/D531106/..."
new_url = "https://himawari8.nict.go.jp/img/D531106/..."

# 简单替换
new_url = old_url.replace(
    "himawari8-dl.nict.go.jp/himawari8/",
    "himawari8.nict.go.jp/"
)
```

```javascript
// JavaScript 示例
const newUrl = oldUrl.replace(
    'himawari8-dl.nict.go.jp/himawari8/',
    'himawari8.nict.go.jp/'
);
```

### 5.3 已知受影响项目

| 项目 | 语言 | 仓库 |
|------|------|------|
| `himawaripy` | Python | [ Saroth/himawaripy](https://gitee.com/irontec/himawaripy) |
| `himawari-8-chrome` | JavaScript | [domoritz/himawari-8-chrome](https://github.com/domoritz/himawari-8-chrome) |
| `himawari-8-wallpaper` | - | [FrancoisMentec/himawari-8-wallpaper](https://github.com/FrancoisMentec/himawari-8-wallpaper) |
| `himawari.js` | Node.js | [jakiestfu/himawari.js](https://github.com/jakiestfu/himawari.js/) |
| `himawari-urls` | npm | [ungoldman/himawari-urls](https://github.com/ungoldman/himawari-urls) |
| `himawari8_service` | - | [khvysofq/himawari8_service](https://github.com/khvysofq/himawari8_service) |

---

## 6. 备用数据源

如果 `himawari8.nict.go.jp` 未来也出现问题，可考虑以下替代方案：

| 数据源 | URL | 说明 |
|--------|-----|------|
| `himawari.asia` | `https://himawari.asia/img/D531106/...` | NICT 科学云项目，URL 格式兼容 |
| JMA MSC | `https://www.data.jma.go.jp/mscweb/data/himawari/` | 日本气象厅，整图而非瓦片 |
| JAXA P-Tree | `https://www.eorc.jaxa.jp/ptree/` | 需要注册 |
| AWS Open Data | `https://registry.opendata.aws/noaa-himawari/` | 原始数据，需解析 |

### 6.1 himawari.asia 兼容性测试

```
URL: https://himawari.asia/img/D531106/1d/550/2026/06/08/040000_0_0.png
HTTP状态码: 200
响应大小: 461,793 bytes
```

URL 格式与 `himawari8.nict.go.jp` 完全兼容，可直接替换。

---

## 7. Cloudinary Fetch CDN 注意事项

### 7.1 域名白名单

如果使用 Cloudinary Fetch 代理新域名，需要在 Cloudinary 控制台配置：

1. 登录 [Cloudinary Console](https://cloudinary.com/console)
2. 进入 **Settings → Security → Allowed fetch domains**
3. 添加 `himawari8.nict.go.jp` 和/或 `himawari.asia`

### 7.2 测试结果

```
# 直接访问源站
himawari8.nict.go.jp → 200 OK

# 通过 Cloudinary Fetch（未配置白名单）
res.cloudinary.com/.../fetch/.../himawari8.nict.go.jp → 404 Not Found
x-cld-error: Resource not found
```

---

## 8. 获取最新时间戳

```bash
# himawari.asia 提供 latest.json 接口
curl https://himawari.asia/img/D531106/latest.json

# 返回示例：
# {"date":"2026-06-08 06:30:00","file":"PI_H09_20260608_0630_TRC_FLDK_R10_PGPFD.png"}
```

---

## 9. 参考资源

- [NOAA CoastWatch - Switch from Himawari-8 to Himawari-9](https://coastwatch.noaa.gov/cwn/news/2025-11-21/switch-himawari-8-himawari-9.html)
- [Open-Meteo GitHub Issue #1683 - JMA Himawari data broken](https://github.com/open-meteo/open-meteo/issues/1683)
- [JMA Meteorological Satellite Center](https://www.data.jma.go.jp/mscweb/data/himawari/)
- [NICT Himawari-8 Real-time Web](https://himawari8.nict.go.jp/)
- [awesome-himawari8 - 同类项目汇总](https://github.com/casouri/awesome-himawari8)
