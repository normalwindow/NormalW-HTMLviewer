// 构建 CodeMirror 离线 bundle 到 app/src/main/assets/editor/
// 并复制 prettier(格式化)UMD 文件到 vendor/(编辑器首次格式化时动态加载)
import { build } from "esbuild";
import { cpSync, mkdirSync } from "node:fs";

const outDir = "../app/src/main/assets/editor";
mkdirSync(outDir, { recursive: true });
mkdirSync(outDir + "/vendor", { recursive: true });

await build({
  entryPoints: ["entry.js"],
  bundle: true,
  minify: true,
  format: "iife",
  target: ["chrome70"],
  outfile: outDir + "/bundle.js",
  logLevel: "info",
});

// prettier standalone + 各语言插件(prettier 3 的 .js 为 UMD,
// 分别暴露 window.prettier / window.prettierPlugins.*)
const vendor = [
  ["node_modules/prettier/standalone.js", "vendor/prettier.js"],
  ["node_modules/prettier/plugins/html.js", "vendor/prettier-plugins-html.js"],
  ["node_modules/prettier/plugins/postcss.js", "vendor/prettier-plugins-postcss.js"],
  ["node_modules/prettier/plugins/babel.js", "vendor/prettier-plugins-babel.js"],
  ["node_modules/prettier/plugins/estree.js", "vendor/prettier-plugins-estree.js"],
];
for (const [src, dest] of vendor) {
  cpSync(src, outDir + "/" + dest);
}

cpSync("editor.html", outDir + "/editor.html");
console.log("editor bundle + prettier vendor built ->", outDir);
