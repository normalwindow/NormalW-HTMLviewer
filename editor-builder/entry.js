// HTML Viewer 编辑器入口:打包 CodeMirror 6 为离线单文件 bundle
// 通过 window.HVEditor 暴露桥接 API,由 Android 侧 WebView 调用。
import { EditorView, basicSetup } from "codemirror";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { javascript } from "@codemirror/lang-javascript";
import { oneDark } from "@codemirror/theme-one-dark";
import { EditorState, Compartment } from "@codemirror/state";
import { keymap } from "@codemirror/view";
import { indentWithTab, undo, redo } from "@codemirror/commands";
import { searchKeymap, openSearchPanel } from "@codemirror/search";

let view = null;
// ---- 禁用 EditContext(Android 滚动/触摸根因) ----
// Chromium>=126 的 Android WebView 上 CM6 会自动启用 EditContext API,
// 它有一系列已知缺陷(点击空行滚动回顶部、触摸滚动失效/异常等,
// 见 codemirror/dev#1676 与 CM6 论坛 "unusual scrolls on Android Webview")。
// 官方唯一 workaround 即 EditorView.EDIT_CONTEXT = false:
// 回退 contenteditable 路径后触摸事件正常派发,下方"触摸滚动接管"
// 才能生效(否则接管收不到事件,上下滚动完全失效)。
// 代价:部分 Android IME 组合输入退化——但滚动可用性优先,
// 且 contenteditable 老路径此前版本一直正常。
EditorView.EDIT_CONTEXT = false;
const themeComp = new Compartment();
const fontSizeComp = new Compartment();
const wrapComp = new Compartment();
const tabSizeComp = new Compartment();
/** 语法高亮可降级(大文件禁用,显著降低 DOM/解析压力) */
const langComp = new Compartment();

// 内容拉取分块(字符数):远小于 Android JavascriptInterface 的 Binder 传输上限
// (32KB 单事务,几乎不可能触发 Binder 压力下的偶发失败)
const FETCH_CHUNK = 32000;
// 大文本插入分片(字符数):分片 dispatch 避免渲染进程一次性处理巨型变更
const INSERT_CHUNK = 50000;
// 超过该字符数禁用语法高亮(约 1MB 源码)
const HIGHLIGHT_LIMIT = 1000000;
// 保存推送分块(字符数):绕开 evaluateJavascript 返回值的 Binder 限制
const SAVE_CHUNK = 64000;

function fontSizeTheme(px) {
  return EditorView.theme({
    ".cm-content": { fontSize: px + "px" },
  });
}

// ---- 页面级背景同步(浅色/深色) ----
// oneDark 只覆盖 .cm-editor 内部,body/html/#editor 需手动切换,
// 否则 dark 下加载瞬间/边缘露白。
function applyThemeBg(dark) {
  const bg = dark ? "#282c34" : "#ffffff";
  const body = document.body;
  if (body) body.style.background = bg;
  const h = document.documentElement;
  if (h) h.style.background = bg;
  const editor = document.getElementById("editor");
  if (editor) editor.style.background = bg;
}

function createState(opts) {
  return EditorState.create({
    doc: opts.content || "",
    extensions: [
      basicSetup,
      langComp.of(opts.highlight === false ? [] : [html(), css(), javascript()]),
      themeComp.of(opts.dark ? oneDark : []),
      fontSizeComp.of(fontSizeTheme(opts.fontSize || 14)),
      wrapComp.of(opts.wrap ? EditorView.lineWrapping : []),
      tabSizeComp.of(EditorState.tabSize.of(opts.tabSize || 4)),
      keymap.of([indentWithTab, ...searchKeymap]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged && window.HVBridge && window.HVBridge.onEditorChanged) {
          window.HVBridge.onEditorChanged();
        }
        // 光标上报按帧节流:粘贴/快速输入时避免高频桥调用
        if (u.selectionSet || u.docChanged) scheduleCursorReport();
        // 内容变化后同步滚动条滑块(下一帧)
        if (u.docChanged && !sbUpdatePending) {
          sbUpdatePending = true;
          requestAnimationFrame(() => {
            sbUpdatePending = false;
            sbUpdate();
          });
        }
      }),
    ],
  });
}

// ---- 光标上报节流(每帧最多一次桥调用) ----
let cursorReportPending = false;
// 滚动条滑块同步节流(内容变化后下一帧更新)
let sbUpdatePending = false;
function scheduleCursorReport() {
  if (cursorReportPending || !view) return;
  cursorReportPending = true;
  requestAnimationFrame(() => {
    cursorReportPending = false;
    if (!view) return;
    const head = view.state.selection.main.head;
    const line = view.state.doc.lineAt(head);
    if (window.HVBridge && window.HVBridge.onCursorChanged) {
      window.HVBridge.onCursorChanged(line.number, head - line.from + 1);
    }
  });
}

// ---- 尺寸强制同步 ----
// 双保险:即使 CSS 布局链异常,也保证 .cm-editor/.cm-scroller 等于 WebView 视口,
// CodeMirror 的视口测量永远正确(视口错误会导致全部行渲染→大文件卡死,
// 或视口不更新→滚动后空白)。
// 注意:尺寸必须以 window.innerWidth/innerHeight 为准,不能读
// #editor.clientHeight——部分 WebView(如 MIUI)上 #editor/.cm-editor 的
// 绝对定位链失效时 clientHeight 等于内容高度(全部行渲染,实测 32507 字符
// 文件 → 16234px),scroller 无垂直溢出 → 无法滚动/滚动条不显示;
// 内联强制视口尺寸后任何布局异常都被修正(绝对定位正常时两者相等)。
function enforceSize() {
  try {
    const editor = document.getElementById("editor");
    const cm = document.querySelector(".cm-editor");
    const scroller = document.querySelector(".cm-scroller");
    if (editor && cm && scroller) {
      const w = window.innerWidth;
      const h = window.innerHeight;
      if (w > 0 && h > 0) {
        editor.style.width = w + "px";
        editor.style.height = h + "px";
        cm.style.width = w + "px";
        cm.style.height = h + "px";
        scroller.style.width = w + "px";
        scroller.style.height = h + "px";
      }
    }
  } catch (e) { /* 布局未就绪时忽略 */ }
  if (view) view.requestMeasure();
  sbUpdate(); // 尺寸变化后同步滚动条滑块
}

// 下一帧调度(rAF 优先,不可用时退化为宏任务)
function nextTick(fn) {
  if (typeof requestAnimationFrame === "function") {
    requestAnimationFrame(fn);
  } else {
    setTimeout(fn, 0);
  }
}

// ---- 代理对安全切片 ----
// UTF-16 代理对(emoji 等)不允许被切在中间:孤立代理项经桥 JSON 序列化
// 可能损坏,保存内容会乱码。起始处不调整——循环按返回长度推进,
// 偏移永远落在完整代理对边界(无重叠/遗漏)。
function safeSlice(text, from, to) {
  if (to < text.length) {
    const c = text.charCodeAt(to - 1);
    if (c >= 0xd800 && c <= 0xdbff) to += 1; // 结尾是高代理 → 并入后半
  }
  return text.slice(from, to);
}

// ---- 分片文本插入 ----
// 大文本拆成多片顺序 dispatch:首片立即执行(用户立刻看到首屏),
// 后续片按帧调度让渲染进程喘息;每片后强制尺寸同步(视口即时更新)。
// 同一 userEvent 的连续变更会被 CM6 history 合并为一次撤销。
function insertTextChunked(text, from, to, userEvent, onDone) {
  if (!view || !text) {
    if (onDone) onDone();
    return;
  }
  const parts = [];
  for (let i = 0; i < text.length; i += INSERT_CHUNK) {
    parts.push(text.slice(i, i + INSERT_CHUNK));
  }
  let idx = 0;
  const finish = () => {
    enforceSize();
    view.focus();
    if (onDone) onDone();
  };
  const step = () => {
    if (!view) {
      if (onDone) onDone();
      return;
    }
    if (idx >= parts.length) {
      finish();
      return;
    }
    const p = parts[idx++];
    try {
      view.dispatch({
        changes: { from: from, to: to, insert: p },
        selection: { anchor: from + p.length, head: from + p.length },
        userEvent: userEvent,
      });
    } catch (e) {
      // 单片 dispatch 异常(理论上不可达)不中断整体加载,
      // 继续后续片,保证内容完整与 onContentLoaded 触发
    }
    from += p.length;
    to = from;
    enforceSize();
    if (idx < parts.length) {
      nextTick(step);
    } else {
      finish();
    }
  };
  step();
}

// ---- 接管粘贴:大文本直接分片 dispatch 到 CodeMirror,绕过 WebView
// InputConnection 的大文本传输路径(粘贴千行代码时渲染进程长时间忙会卡死 UI) ----
document.addEventListener(
  "paste",
  (e) => {
    if (!view) return;
    const text = e.clipboardData && e.clipboardData.getData("text/plain");
    if (text == null) return; // 非文本(如图片)保持默认行为
    e.preventDefault();
    const sel = view.state.selection.main;
    insertTextChunked(text, sel.from, sel.to, "input.paste");
  },
  true
);

// ---- WebView 兼容兜底:滚动/尺寸变化时强制 CM6 重新测量视口 ----
document.addEventListener(
  "scroll",
  () => {
    if (view) view.requestMeasure();
  },
  { passive: true }
);
window.addEventListener("resize", enforceSize);
// 双指缩放(页面 zoom)时布局视口不变但 CSS 像素变化:visualViewport
// resize 触发后强制重测,避免 CM6 视口错乱(滚动空白/文字错位)
if (window.visualViewport) {
  window.visualViewport.addEventListener("resize", enforceSize);
}

// ---- 触摸滚动接管 ----
// 部分 ROM 的 WebView(如 MIUI)在 contenteditable 区域内把触摸拖动当作文本
// 选择,原生滚动完全不生效。这里确定性接管单指滚动:
// 位移超阈值后 preventDefault 并直接操作 scrollTop(不依赖原生滚动),
// 保证任何设备都能上下滑动;阈值内放行轻点/长按/文本选择。
// 注意:部分 WebView 的 touchmove 中 touches 为空(需回退 changedTouches),
// 否则单指判定永远失败、接管不生效。
const tp = { engaged: false, lastX: 0, lastY: 0, startX: 0, startY: 0, ts: 0, mv: 0, ck: 0, pc: 0 };
// 触摸事件可见性标志:Android 侧 onTouchEvent 兜底在每个手势的 DOWN 时
// 异步查询此标志——JS 接管有效(事件到达)时绝不注入滚动,防双重滚动;
// 事件缺失时由 Android 侧直接注入 HVEditor.scrollBy 保证可滚动。
window.__hvTouchSeen = false;
// 触点列表:touches 为空(部分 WebView 行为)时回退 changedTouches
function tpPoints(e) {
  return e.touches && e.touches.length ? e.touches : e.changedTouches || [];
}
// 触摸诊断(每 10 次抬起上报一次,经 onDiag 写入日志)
function tpDiag() {
  if (window.HVBridge && window.HVBridge.onDiag && tp.ts % 10 === 0) {
    window.HVBridge.onDiag(
      "touch ts=" + tp.ts + " mv=" + tp.mv + " engaged=" + (tp.engaged ? 1 : 0) + " view=" + (view ? 1 : 0)
    );
  }
}
// 滚动容器自检:垂直方向无溢出(scrollHeight<=clientHeight)说明 scroller
// 高度异常(被设成内容高度,表现=滚动条缺失/无法下滚),强制 enforceSize
// 修正为视口高度并上报诊断取证。
function checkScroller() {
  try {
    const sc = document.querySelector(".cm-scroller");
    if (sc && sc.scrollHeight <= sc.clientHeight + 1) {
      enforceSize();
      if (window.HVBridge && window.HVBridge.onDiag) {
        const ed = document.getElementById("editor");
        window.HVBridge.onDiag(
          "touch-noscroll scH=" + sc.scrollHeight + " scCH=" + sc.clientHeight +
          " edH=" + (ed ? ed.clientHeight : -1) + " iH=" + window.innerHeight + " ->enforceSize"
        );
      }
    }
  } catch (e) { /* 布局未就绪时忽略 */ }
}
document.addEventListener(
  "touchstart",
  (e) => {
    // 滚动条上的触摸不接管(滚动条自行处理拖动/点击),但仍须置
    // __hvTouchSeen:否则 Android 侧兜底会误判"JS 事件缺失"而在
    // 拖动滚动条时注入 scrollBy,与滚动条绝对赋值叠加(抖动/超速)
    if (e.target && e.target.closest && e.target.closest(".hv-scrollbar")) {
      window.__hvTouchSeen = true;
      return;
    }
    tp.ts++;
    window.__hvTouchSeen = true;
    const pts = tpPoints(e);
    tp.pc = pts.length;
    if (pts.length !== 1) {
      tp.engaged = false;
      return;
    }
    tp.engaged = false;
    tp.lastX = tp.startX = pts[0].clientX;
    tp.lastY = tp.startY = pts[0].clientY;
    checkScroller();
  },
  { passive: true, capture: true }
);
document.addEventListener(
  "touchmove",
  (e) => {
    if (!view) return;
    // 滚动条上的触摸不接管(滚动条自行处理拖动)
    if (e.target && e.target.closest && e.target.closest(".hv-scrollbar")) return;
    tp.mv++;
    const pts = tpPoints(e);
    if (pts.length !== 1) {
      // 多指(缩放)期间:同步 last 坐标并记录触点数,
      // 恢复单指时不把缩放期间的位置差当成本次拖动增量(内容跳跃)
      if (pts.length > 0) {
        tp.lastX = pts[0].clientX;
        tp.lastY = pts[0].clientY;
      }
      tp.pc = pts.length;
      return;
    }
    // 从多指恢复单指:丢弃本帧位移(仅同步坐标),避免一次性注入大位移
    if (tp.pc !== 1) {
      tp.pc = 1;
      tp.engaged = false;
      tp.startX = tp.lastX = pts[0].clientX;
      tp.startY = tp.lastY = pts[0].clientY;
      return;
    }
    const t = pts[0];
    const dy = t.clientY - tp.lastY;
    const dx = t.clientX - tp.lastX;
    const dist = Math.abs(t.clientY - tp.startY) + Math.abs(t.clientX - tp.startX);
    if (!tp.engaged) {
      if (dist < 12) return; // 阈值内放行:轻点/长按/文本选择
      tp.engaged = true;
      if (window.HVBridge && window.HVBridge.onDiag && tp.mv % 5 === 0) {
        window.HVBridge.onDiag("touch-engaged mv=" + tp.mv);
      }
    }
    e.preventDefault();
    const sc = document.querySelector(".cm-scroller");
    if (sc) {
      sc.scrollTop -= dy;
      sc.scrollLeft -= dx;
    }
    tp.lastY = t.clientY;
    tp.lastX = t.clientX;
  },
  { passive: false, capture: true }
);
document.addEventListener(
  "touchend",
  () => {
    tp.engaged = false;
    tpDiag();
  },
  { passive: true, capture: true }
);
// 系统手势打断(下拉通知栏等)时同样复位,避免接管状态残留
document.addEventListener(
  "touchcancel",
  () => {
    tp.engaged = false;
  },
  { passive: true, capture: true }
);

// ---- 自定义滚动条(vscode 风格) ----
// 部分 WebView/ROM 在编辑区内的触摸滚动完全失效(触摸事件不派发或
// 坐标异常),接管/兜底均依赖事件流。滚动条拖动直接对
// .cm-scroller.scrollTop 绝对赋值,不依赖任何触摸事件流,
// 任何设备必然生效;菜单可隐藏(Android 侧 HVEditor.setScrollbar)。
const sb = {
  bar: null, thumb: null, dragging: false,
  y0: 0, top0: 0, barH: 0, thumbH: 0,
};
function initScrollbar() {
  if (sb.bar) return;
  const st = document.createElement("style");
  st.textContent =
    ".hv-scrollbar{position:fixed;top:0;right:0;bottom:0;width:12px;z-index:999;" +
    "background:transparent;touch-action:none;display:block}" +
    ".hv-scrollbar-thumb{position:absolute;left:2px;right:2px;border-radius:4px;" +
    "background:rgba(128,128,128,.35);min-height:32px}" +
    ".hv-scrollbar-thumb.hv-sb-drag{background:rgba(128,128,128,.6)}" +
    // 搜索面板的关闭按钮在右上角(right:4px),会被滚动条盖住无法点击,
    // 面板右缘让出滚动条宽度
    ".cm-panel.cm-search{padding-right:14px}";
  document.head.appendChild(st);
  const bar = document.createElement("div");
  bar.className = "hv-scrollbar";
  const thumb = document.createElement("div");
  thumb.className = "hv-scrollbar-thumb";
  bar.appendChild(thumb);
  document.body.appendChild(bar);
  sb.bar = bar;
  sb.thumb = thumb;
  bar.addEventListener("pointerdown", onSbDown);
  bar.addEventListener("pointermove", onSbMove);
  bar.addEventListener("pointerup", onSbUp);
  bar.addEventListener("pointercancel", onSbUp);
  // 捕获意外丢失(WebView 异常)时同样复位拖动状态
  bar.addEventListener("lostpointercapture", onSbUp);
  // scroller 滚动时同步滑块位置(scroll 事件不冒泡,须直接绑定)
  const sc = sbScroller();
  if (sc) sc.addEventListener("scroll", sbUpdate);
  sbUpdate();
  setTimeout(sbUpdate, 300);
}
function sbScroller() {
  return document.querySelector(".cm-scroller");
}
// 滑块位置/尺寸 = f(scrollTop, scrollHeight, clientHeight)
function sbUpdate() {
  if (!sb.bar) return;
  const sc = sbScroller();
  if (!sc) return;
  sb.barH = sb.bar.clientHeight;
  const sh = sc.scrollHeight;
  const ch = sc.clientHeight;
  if (sh <= ch + 1) {
    // 无垂直溢出:隐藏滑块,且整个条不拦截触摸/点击
    // (否则编辑器最右 12px 一列的光标定位会被透明条吃掉)
    sb.thumb.style.display = "none";
    sb.bar.style.pointerEvents = "none";
    return;
  }
  sb.thumb.style.display = "block";
  sb.bar.style.pointerEvents = "auto";
  const th = Math.max(32, (sb.barH * ch) / sh);
  sb.thumbH = th;
  const maxTop = Math.max(0, sb.barH - th);
  const maxScroll = sh - ch;
  const top = maxScroll > 0 ? (sc.scrollTop / maxScroll) * maxTop : 0;
  sb.thumb.style.height = th + "px";
  sb.thumb.style.top = top + "px";
}
// 滑块顶部位置 → scrollTop(绝对赋值,拖动/点击轨道共用)
function sbSetThumbTop(top) {
  const sc = sbScroller();
  if (!sc) return;
  const maxTop = Math.max(0, sb.barH - sb.thumbH);
  const ratio = maxTop > 0 ? top / maxTop : 0;
  sc.scrollTop = ratio * (sc.scrollHeight - sc.clientHeight);
}
function onSbDown(e) {
  if (!sb.thumb || sb.thumb.style.display === "none") return;
  e.preventDefault();
  e.stopPropagation();
  const t = sb.thumb;
  const y = e.clientY - sb.bar.getBoundingClientRect().top;
  if (y >= t.offsetTop && y <= t.offsetTop + t.offsetHeight) {
    // 落在滑块上:开始拖动
    sb.dragging = true;
    sb.y0 = e.clientY;
    sb.top0 = parseFloat(t.style.top) || 0;
    t.classList.add("hv-sb-drag");
    try { t.setPointerCapture(e.pointerId); } catch (err) { /* 捕获失败仍可拖动 */ }
  } else {
    // 点击轨道:直接跳到该位置(滑块中心对齐触点)
    const top = Math.max(0, Math.min(sb.barH - sb.thumbH, y - sb.thumbH / 2));
    sbSetThumbTop(top);
  }
}
function onSbMove(e) {
  if (!sb.dragging) return;
  e.preventDefault();
  e.stopPropagation();
  const top = Math.max(0, Math.min(sb.barH - sb.thumbH, sb.top0 + (e.clientY - sb.y0)));
  sbSetThumbTop(top);
  // 滑块即时跟手(scroll 事件异步派发,这里直接同步,避免滞后一帧)
  sb.thumb.style.top = top + "px";
}
function onSbUp(e) {
  if (!sb.dragging) return;
  sb.dragging = false;
  sb.thumb.classList.remove("hv-sb-drag");
  try { sb.thumb.releasePointerCapture(e.pointerId); } catch (err) { /* 忽略 */ }
}

// ---- 格式化(prettier 由 Android 侧首次调用时动态加载) ----
const FORMAT_VENDOR = [
  "vendor/prettier.js",
  "vendor/prettier-plugins-html.js",
  "vendor/prettier-plugins-postcss.js",
  "vendor/prettier-plugins-babel.js",
  "vendor/prettier-plugins-estree.js",
];
function loadFormatVendor(cb) {
  if (window.prettier) {
    cb();
    return;
  }
  let i = 0;
  const next = () => {
    if (i >= FORMAT_VENDOR.length) {
      cb();
      return;
    }
    const s = document.createElement("script");
    s.src = FORMAT_VENDOR[i++];
    s.onload = next;
    s.onerror = next; // 单个失败不阻断其余,最终以 window.prettier 为准
    document.head.appendChild(s);
  };
  next();
}

// ---- 错误详情:优先完整调用栈(含桥异常上下文),供 Android 侧日志定位 ----
function errorDetail(e) {
  if (e && e.stack) return String(e.stack);
  return String(e);
}

window.HVEditor = {
  /**
   * 自定义滚动条显示开关(菜单切换,状态由 Android 侧持久化)
   */
  setScrollbar(visible) {
    if (sb.bar) {
      sb.bar.style.display = visible ? "block" : "none";
      // 重新显示后立即刷新滑块(隐藏期间 barH=0,滑块状态陈旧)
      sbUpdate();
    }
  },

  /**
   * Android 侧滚动兜底(WebView.onTouchEvent 检测到 JS 触摸事件缺失时调用):
   * 直接驱动 .cm-scroller 的 scrollTop/scrollLeft,不依赖触摸事件派发,
   * 保证任何 WebView/ROM 下编辑模式都能上下滚动。
   * 方向与触摸接管一致:dy>0 内容下移(手指上滑),dx>0 内容右移。
   */
  scrollBy(dy, dx) {
    if (!view) return;
    const sc = document.querySelector(".cm-scroller");
    if (!sc) return;
    sc.scrollTop += dy;
    sc.scrollLeft += dx;
    view.requestMeasure();
  },

  init(opts) {
    if (view) return;
    const bridge = window.HVBridge;
    try {
      // 立即用空文档创建编辑器:界面立刻渲染(避免大内容传输失败导致白屏),
      // 内容随后由 loadContent() 分块拉取填充。
      const initial = (opts && opts.content) || "";
      applyThemeBg(!!(opts && opts.dark));
      view = new EditorView({
        state: createState(Object.assign({}, opts || {}, { content: initial })),
        parent: document.getElementById("editor"),
      });
      // 自定义滚动条:默认显示,菜单可隐藏(Android 侧 setScrollbar 切换)
      initScrollbar();
      if (opts && opts.scrollbar === false) {
        sb.bar.style.display = "none";
      }
      // 初始化后立即强制尺寸同步(防布局未完成时视口为 0),多时机兜底
      nextTick(enforceSize);
      setTimeout(enforceSize, 300);
      setTimeout(enforceSize, 800);
      // 白屏诊断:上报 CM6 状态供 Android 侧日志定位
      setTimeout(() => {
        if (window.HVBridge && window.HVBridge.onDiag) {
          const cm = document.querySelector(".cm-editor");
          const sc = document.querySelector(".cm-scroller");
          const ed = document.getElementById("editor");
          window.HVBridge.onDiag(
            "init-check view=" + !!view +
            " cm=" + !!cm +
            " cmH=" + (cm ? cm.clientHeight : -1) +
            " scH=" + (sc ? sc.scrollHeight : -1) +
            " scCH=" + (sc ? sc.clientHeight : -1) +
            " edH=" + (ed ? ed.clientHeight : -1) +
            " iH=" + window.innerHeight
          );
        }
      }, 800);
      if (bridge && bridge.onEditorReady) {
        bridge.onEditorReady();
      }
      view.focus();
    } catch (e) {
      // 初始化异常:上报 Android 侧重试,避免静默白屏
      view = null;
      if (bridge && bridge.onContentLoadError) {
        bridge.onContentLoadError(String(e));
      }
    }
  },

  /**
   * 分块拉取初始内容(每块远小于 Android JavascriptInterface 的 Binder
   * 传输上限,大文件安全)。失败自动重试 3 次,仍失败则通知 Android 侧。
   */
  loadContent() {
    const bridge = window.HVBridge;
    if (!view || !bridge || !bridge.getContentSize) return;
    let attempt = 0;
    const tryLoad = () => {
      try {
        const total = bridge.getContentSize();
        // Java 侧异常时返回 -1(返回 0 会被当成空文件)
        if (total < 0) throw new Error("内容尺寸获取失败");
        if (total <= 0) {
          // 空文件:直接标记加载完成——否则 contentLoaded 永假,
          // Android 侧保存被门控拒绝(保存失效)
          if (bridge.onContentLoaded) bridge.onContentLoaded();
          return;
        }
        const parts = [];
        // 按返回长度推进:Android 侧块末尾可能并入代理对后半,
        // 固定步长会造成重叠(内容重复)/遗漏
        for (let i = 0; i < total; ) {
          const chunk = bridge.getContentChunk(i, Math.min(FETCH_CHUNK, total - i));
          // Java 侧异常返回 null/空:抛错进重试(空串会导致死循环)
          if (chunk == null || chunk === "") {
            throw new Error("内容分块获取失败 @" + i);
          }
          parts.push(chunk);
          i += chunk.length;
        }
        const text = parts.join("");
        // 大文件禁用语法高亮:DOM/解析压力大减,打开与编辑显著提速
        if (text.length > HIGHLIGHT_LIMIT && view) {
          view.dispatch({ effects: langComp.reconfigure([]) });
        }
        insertTextChunked(text, 0, view.state.doc.length, "input", () => {
          if (bridge.onContentLoaded) bridge.onContentLoaded();
        });
      } catch (e) {
        if (++attempt < 3) {
          setTimeout(tryLoad, 500);
        } else if (bridge.onContentLoadError) {
          bridge.onContentLoadError(errorDetail(e));
        }
      }
    };
    tryLoad();
  },

  setContent(text) {
    if (!view) return;
    const cur = view.state.doc.toString();
    if (cur === text) return;
    view.dispatch({ changes: { from: 0, to: cur.length, insert: text } });
  },

  /**
   * 降级注入通道:Android 侧在分块拉取反复失败时,通过 evaluateJavascript
   * 一次性传入内容(JSON 字符串),这里分片插入并回调 onContentLoaded。
   * view 未就绪时返回 false(调用方据此判定注入无效)。
   */
  setContentChunked(text) {
    if (!view) return false;
    insertTextChunked(text, 0, view.state.doc.length, "input", () => {
      if (window.HVBridge && window.HVBridge.onContentLoaded) {
        window.HVBridge.onContentLoaded();
      }
    });
    return true;
  },

  getContent() {
    return view ? view.state.doc.toString() : "";
  },

  /**
   * 分块推送保存内容:大文件经 evaluateJavascript 返回值传输会超 Binder
   * 限制(约 1MB),改为 JS 主动分块调用桥接口,由 Android 侧拼接后写盘。
   */
  saveContent() {
    const bridge = window.HVBridge;
    if (!view || !bridge || !bridge.saveBegin) return;
    const text = view.state.doc.toString();
    bridge.saveBegin(text.length);
    // 按返回长度推进:safeSlice 可能并入代理对后半,固定步长会丢块/重叠
    for (let i = 0; i < text.length; ) {
      const part = safeSlice(text, i, Math.min(i + SAVE_CHUNK, text.length));
      bridge.saveChunk(i, part);
      i += part.length;
    }
    bridge.saveCommit();
  },

  getCursor() {
    if (!view) return { line: 1, col: 1 };
    const pos = view.state.selection.main.head;
    const line = view.state.doc.lineAt(pos);
    return { line: line.number, col: pos - line.from + 1 };
  },

  setDark(dark) {
    applyThemeBg(dark);
    if (view) view.dispatch({ effects: themeComp.reconfigure(dark ? oneDark : []) });
  },

  setFontSize(px) {
    if (view) view.dispatch({ effects: fontSizeComp.reconfigure(fontSizeTheme(px)) });
  },

  setWrap(wrap) {
    if (view) view.dispatch({ effects: wrapComp.reconfigure(wrap ? EditorView.lineWrapping : []) });
  },

  setTabSize(n) {
    if (view) view.dispatch({ effects: tabSizeComp.reconfigure(EditorState.tabSize.of(n)) });
  },

  openSearch() {
    if (view) openSearchPanel(view);
  },

  undo() {
    if (view) undo(view);
  },

  redo() {
    if (view) redo(view);
  },

  /**
   * 一键整理格式(lang: "html" | "css" | "js")。
   * 返回 Promise:成功 resolve true,失败 resolve 错误信息字符串。
   */
  format(lang) {
    return new Promise((resolve) => {
      if (!view) {
        resolve("编辑器未就绪");
        return;
      }
      loadFormatVendor(() => {
        (async () => {
          try {
            if (!window.prettier) {
              resolve("格式化组件加载失败");
              return;
            }
            const parser = lang === "css" ? "css" : lang === "js" ? "babel" : "html";
            const plugins = [];
            if (window.prettierPlugins) {
              if (parser === "html") plugins.push(window.prettierPlugins.html);
              else if (parser === "css") plugins.push(window.prettierPlugins.postcss);
              else plugins.push(window.prettierPlugins.babel, window.prettierPlugins.estree);
            }
            const code = view.state.doc.toString();
            const out = await window.prettier.format(code, {
              parser: parser,
              plugins: plugins,
              tabWidth: view.state.tabSize,
            });
            if (out !== code) {
              view.dispatch({ changes: { from: 0, to: code.length, insert: out }, userEvent: "input" });
            }
            resolve(true);
          } catch (e) {
            resolve(String((e && e.message) || e));
          }
        })();
      });
    });
  },
};
