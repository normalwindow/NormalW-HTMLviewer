# NW-HTMLviewer

![Version](https://img.shields.io/badge/version-1.0.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![minSdk](https://img.shields.io/badge/minSdk-26-brightgreen)
![License](https://img.shields.io/badge/license-GPL--3.0-green)

一款工业级的移动端 **HTML 编辑器与全屏预览** 应用(原生 Material 3 风格)。内置 CodeMirror 6 代码编辑、双渲染内核(系统 WebView / GeckoView)、离线资源缓存与完整的文件管理能力。

<p align="center">
  <img src="readme/lander1.webp" alt="NW-HTMLviewer 预览" width="80%">
</p>

## 功能特性

- **代码编辑**:内置 CodeMirror 6(离线打包),支持 HTML/CSS/JS 语法高亮、行号、自动缩进、括号匹配、查找替换、撤销/重做、一键整理格式(内置 prettier)
- **全屏预览**:沉浸式预览(隐藏系统栏),支持分屏实时预览(编辑即所见,防抖刷新)与全屏预览
- **浏览器式预览**:前进/后退/刷新、UA 标识切换、JS 开关、沉浸模式、模拟鼠标(触摸板式光标/单击/双指滚动)、控制台与报错/警告抽屉
- **双渲染内核**:
  - 轻量模式:系统 WebView(Chromium,零额外开销)
  - 兼容模式:内置 GeckoView 153(独立内核,渲染行为固定,持久磁盘缓存)
- **离线资源缓存**:网络资源(CDN 等)首次下载后固化到 HTML 同目录隐藏文件夹 `.htmlviewer_cache`,URL 不变时离线直接使用,可开关、可手动清理刷新
- **文件管理**:目录浏览、列表/网格视图、实时搜索、最近打开、收藏、多选批量操作(删除/移动/复制/分享)、删除可撤销(5 秒回收站)、内置模板新建
- **编码兼容**:自动检测 UTF-8/GBK/UTF-16(BOM 优先),支持 GBK 存量 HTML 文件,可一键转存 UTF-8
- **人性化设置**:主题(跟随系统/浅色/深色 + 动态取色)、编辑器字号/缩进/自动保存/自动换行、沉浸模式
- **国内环境适配**:Gradle 依赖全部走阿里云镜像;界面全中文

<p align="center">
  <img src="readme/lander2.webp" alt="NW-HTMLviewer 预览" width="80%">
</p>

## 技术栈

| 组件 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3(动态取色) |
| 架构 | MVVM + Repository + Hilt 依赖注入 |
| 本地存储 | Room(文件元数据)+ DataStore(设置) |
| 编辑器 | CodeMirror 6(assets 离线 bundle) |
| 内核 | WebView(Renderer 抽象)/ GeckoView 153 |
| 构建 | Kotlin 2.3.21 / AGP 8.13 / KSP 2.3.11 / minSdk 26 / targetSdk 36 |

## 架构

```
app/src/main/java/xyz/normalwindow/htmlviewer/
├── data/
│   ├── db/          Room:文件元数据(收藏/最近/编码/行数)
│   ├── settings/    DataStore:用户偏好
│   ├── file/        FileRepository + 编码检测 + 回收站
│   ├── template/    内置模板库(assets/templates)
│   └── di/          Hilt 模块
├── render/          Renderer 抽象:WebViewRenderer / GeckoRenderer
├── ui/
│   ├── home/        文件管理首页(四页签 + 多选 + 批量操作)
│   ├── editor/      CodeMirror 编辑器 + 全屏/分屏预览
│   ├── settings/    设置中心(内核/外观/编辑器/预览)
│   ├── navigation/  NavHost(home / editor / preview)
│   └── components/  通用组件(空态/骨架屏/文件行)
└── theme/           Material 3 主题(动态取色 + 明暗)
```

渲染内核通过 `Renderer` 接口抽象:预览场景按设置选择轻量/兼容内核,统一提供 `loadHtml/loadFile/reload/destroy` 能力。

## 构建

```bash
# Debug(国内镜像自动生效)
./gradlew assembleDebug

# 单元测试
./gradlew testDebugUnitTest

# Release(按 ABI 拆分,arm64-v8a / armeabi-v7a / x86 / x86_64)
./gradlew assembleRelease

# Lite 版本(仅系统 WebView,不含 GeckoView 内核,体积小约 90%+)
./gradlew assembleLiteDebug
# 或 assembleLiteRelease(全量版默认 assembleFullDebug/assembleFullRelease)
```

> Release 产物默认使用 debug 签名,便于本地直接构建安装;正式上架请在 `signingConfigs` 中配置正式 keystore 并替换 `build.gradle.kts` 中的引用。

## Release(发布版本 v1.0.0)

- 发布产物位于 [GitHub Releases](https://github.com/normalwindow/NW-HTMLviewer/releases),按 ABI 拆分(arm64-v8a / armeabi-v7a / x86 / x86_64),**仅需下载 arm64-v8a 包**即可在绝大多数主流设备安装
- 提供两种发行版:
  - **Full 版**(`1.0.0`):含 GeckoView 兼容内核,功能完整(约 185 MB)
  - **Lite 版**(`1.0.0-lite`):仅系统 WebView,体积小约 99%(约 2.2 MB),不包含 GeckoView 兼容模式
- 版本变更记录见 [CHANGELOG.md](CHANGELOG.md)

## 重新构建 CodeMirror bundle

```bash
cd editor-builder
npm install          # 使用 npmmirror 镜像
npm run build        # 产物写入 app/src/main/assets/editor/
```

## 真机验证清单

- [x] 新建/编辑/保存 HTML,自动保存与手动保存
- [x] 深色模式与动态取色
- [x] 分屏实时预览与全屏预览(沉浸式、工具条切换)
- [x] 设置中切换 GeckoView 内核后预览生效(首次初始化较慢属正常)
- [x] 删除文件后 Snackbar 撤销恢复
- [ ] GBK 编码文件打开与转存 UTF-8
- [ ] Gecko 内核压力测试
- [ ] WebView 低版本测试

## TODO

- [ ] 控制台样式加强
- [ ] 颜色&配色美化
- [ ] 文件/文件夹导入
- [ ] 接入百度网盘api实现同步
- [ ] English Version

## 已知限制

- GeckoView 无公共 `evaluateJavascript`/console/请求拦截 API(需 WebExtension),模拟鼠标、控制台收集、离线资源缓存仅系统 WebView 内核支持;Gecko 内核使用持久磁盘缓存(HTTP 缓存头仍优先)
- 文件根目录为应用专属目录(`Android/data/<pkg>/files/HTMLviewer`),无需存储权限;外部目录浏览(Saf)未实现
- GeckoView 无法加载 `file:///android_asset/`,内存 HTML 预览用 `Loader.data()` 加载(相对资源路径在分屏预览中不解析)

## 许可证

本项目基于 [GPL-3.0](LICENSE) 开源协议发布。
