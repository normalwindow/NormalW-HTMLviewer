# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式,版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 说明:发布版本号(当前 `v1.0.0`)与本地 debug 开发版本号序列相互独立、互不通用。

## [1.0.0] - 2026-08-11

首个公开发布版本。基于本地开发版稳定功能集固化发布,面向通用 Android 设备(arm64-v8a / armeabi-v7a / x86 / x86_64)。

### 新增

- **代码编辑**:内置 CodeMirror 6 离线 bundle,支持 HTML/CSS/JS 语法高亮、行号、自动缩进、括号匹配、查找替换、撤销/重做、一键格式整理(prettier)
- **分屏实时预览**:编辑即所见,防抖刷新;全屏沉浸式预览(隐藏系统栏)
- **浏览器式预览**:前进/后退/刷新、UA 切换、JS 开关、沉浸模式、模拟鼠标(触摸板光标/单击/双指滚动)、控制台与报错/警告抽屉
- **双渲染内核**:轻量模式(系统 WebView)与兼容模式(内置 GeckoView 153,持久磁盘缓存),设置中可切换
- **离线资源缓存**:CDN 等网络资源首次下载后固化至 `.htmlviewer_cache`,URL 不变时离线直接使用
- **文件管理**:目录浏览、列表/网格视图、实时搜索、最近打开、收藏、多选批量操作(删除/移动/复制/分享)、删除 5 秒可撤销、内置模板新建
- **编码兼容**:自动检测 UTF-8/GBK/UTF-16(BOM 优先),GBK 存量文件可一键转存 UTF-8
- **人性化设置**:跟随系统/浅色/深色主题 + 动态取色、编辑器字号/缩进/自动保存/自动换行、沉浸模式
- **国内环境适配**:Gradle 依赖走阿里云镜像,界面全中文

### 构建产物

- 按 ABI 拆分 Release 包(arm64-v8a / armeabi-v7a / x86 / x86_64),Release 默认使用 debug 签名,正式上架需替换为正式 keystore
- 两种发行版同步发布:**Full 版**(含 GeckoView 兼容内核,约 185 MB)与 **Lite 版**(仅系统 WebView,约 2.2 MB,体积小约 99%)

[1.0.0]: https://github.com/normalwindow/NW-HTMLviewer/releases/tag/v1.0.0
