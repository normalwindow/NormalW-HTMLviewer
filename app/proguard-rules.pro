# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# WebView 注入的 JS 接口方法名必须保留(混淆会改名导致 JS 调用失败)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# CodeMirror 编辑器桥接类(防御性保留)
-keep class xyz.normalwindow.htmlviewer.ui.editor.EditorJsBridge { *; }

# GeckoView 自带 consumer rules(AAR 内 proguard.txt),此处无需额外配置
