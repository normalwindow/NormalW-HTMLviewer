# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式,版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 说明:发布版本号(当前 `v1.1.3`)与本地 debug 开发版本号序列相互独立、互不通用。

## [1.1.3] - 2026-08-14

### 新增

- **应用内置更新检测**:设置-关于新增"检查更新",通过本仓库 GitHub Releases API 检测最新版本(手动触发);发现新版本时展示版本号/当前版本/更新说明/发布时间/安装包大小,点击"前往下载"跳转浏览器打开匹配当前发行版(Full/Lite)与设备 ABI 的 APK 直链(无匹配时回退 Release 页面);已是最新/网络失败均有明确提示
- 主题模式选项顺序调整为 **浅色 / 跟随系统 / 深色**(分段按钮一行)

### 发布

- 本次 release 仅发布 **Lite 版**(`1.1.3-lite`,versionCode 5):仅系统 WebView,约 2.2 MB;Full 版仍为 1.1.0

## [1.1.2] - 2026-08-14

### 修复

- 应用图标重绘:去掉浏览器窗口白框,红黄绿三点上移,仅保留 `</>` 代码符号,更简洁醒目
- README 图标改为透明背景圆角图(去除四周白边)
- 设置页"语言/默认 UA"下拉菜单宽度取内容完整单行(IntrinsicSize.Max),不再把菜单文字挤成两行
- 设置-编辑器"字号/缩进宽度"滑块恢复为与数值同行显示(1.1.0 布局)

### 新增

- "清理缓存资源"支持按位置选择清理:单击该栏弹出缓存位置列表(勾选后清理所选);长按该栏一键清除全部缓存
- 设置-外观主题模式改为 Material3 分段按钮一行展示(跟随系统/浅色/深色),替代原三行单选列表

### 发布

- 本次 release 仅发布 **Lite 版**(`1.1.2-lite`,versionCode 4):仅系统 WebView,约 2.2 MB;Full 版仍为 1.1.0

## [1.1.1] - 2026-08-14

### 修复

- 设置-编辑器"字号/缩进宽度"滑块改为独占一行,不再与标题/数值挤在一行导致文字折行
- 关于页"开源许可证"可点击,跳转浏览器打开 Apache-2.0 官方许可页面
- 应用图标重绘:浏览器窗口与 `</>` 符号放大并居中(自适应图标安全区内),视觉更饱满;关于页图标前景与背景同尺寸显示
- 新增英文版 README(`README_EN.md`),README 开头居中展示应用图标与版本徽章,支持中/英切换

### 发布

- 本次 release 仅发布 **Lite 版**(`1.1.1-lite`,versionCode 3):仅系统 WebView,约 2.2 MB;Full 版仍为 1.1.0

## [1.1.0] - 2026-08-14

### 新增

- **控制台样式加强**:注入 JS 完整拦截 console API,支持多参数、`%s/%d/%i/%f/%o/%O` 格式化、`%c` 内联样式(颜色/背景/粗体/斜体/下划线)与对象点击展开(对象/数组/错误/函数/循环引用安全);左侧级别标签改为彩色胶囊(错误=红、警告=深橙、信息=主题色、日志=灰、调试=主题色容器),区分度更强;控制台级别与角标颜色更多跟随主题色
- **8 种 Material3 配色方案**:设置-外观新增"配色方案",可选 TonalSpot / Neutral / Vibrant / Expressive / Rainbow / FruitSalad / Monochrome / Fidelity(基于 Google material-color-utilities 算法,种子色=自定义主题色或壁纸主色),选择后整套界面配色随主题色变化,即时生效
- **文件/文件夹导入**:文件页"更多"菜单新增"导入文件"(系统文档选择器,支持多选)与"导入文件夹"(递归复制整棵目录树),同名自动去重,无需存储权限
- **语言选择**:新增简体中文 / English / 跟随系统三档,设置中切换后重建 Activity 即时生效;新增完整英文资源包
- **关于页面**:设置-关于点击"应用版本"进入,展示应用图标、版本号、简介、GitHub 仓库(点击外部浏览器打开)、开源许可与技术栈

### 变更

- 版本号升级至 1.1.0(versionCode 2)
- 新增依赖:material-kolor 2.1.1(色调方案算法)、androidx.documentfile 1.0.1(SAF 导入)

### 修复

- 修复 release 包启动瞬间闪退:MainActivity.attachBaseContext 阶段 `Activity.application` 尚未赋值(为 null),强制转换 `HTMLViewerApp` 抛出 NPE;改为通过 `newBase.applicationContext` 获取应用实例(模拟器 API 36 复现并验证)
- 修复点击设置-关于"应用版本"闪退:AboutScreen 用 `painterResource` 加载自适应图标(adaptive-icon XML)不支持,改为前景 vector + 主题色背景合成 Logo
- 设置页"默认 UA 标识"/"语言"下拉菜单改为右对齐(自定义 Popup 实现,`IntrinsicSize.Min` 避免菜单撑满锚点宽度);语言切换后等菜单收起再重建 Activity,不再出现菜单残留在窗口过渡中的问题
- 默认 UA 标识在英文界面下完整翻译(跟随内核默认→Follow engine default、桌面版 Chrome→Desktop Chrome)

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

[1.0.0]: https://github.com/normalwindow/NormlW-HTMLviewer/releases/tag/v1.0.0
[1.1.0]: https://github.com/normalwindow/NormlW-HTMLviewer/releases/tag/v1.1.0
[1.1.1]: https://github.com/normalwindow/NormlW-HTMLviewer/releases/tag/v1.1.1
[1.1.2]: https://github.com/normalwindow/NormlW-HTMLviewer/releases/tag/v1.1.2
[1.1.3]: https://github.com/normalwindow/NormlW-HTMLviewer/releases/tag/v1.1.3
