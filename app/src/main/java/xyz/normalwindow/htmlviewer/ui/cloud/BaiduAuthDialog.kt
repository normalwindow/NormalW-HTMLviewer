package xyz.normalwindow.htmlviewer.ui.cloud

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import xyz.normalwindow.htmlviewer.R

/**
 * 百度网盘授权对话框:应用内 WebView 打开 OAuth 授权页(redirect_uri=oob)。
 * 授权完成后百度跳转到 oob 结果页,自动从 URL/页面内容提取授权码换取令牌;
 * 自动提取失败时展示手动粘贴输入框兜底。支持一键切换系统浏览器打开
 * (部分账号在系统浏览器中登录更顺畅;授权码复制回本对话框即可)。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BaiduAuthDialog(
    authorizeUrl: String,
    /** 提取到授权码后回传(成功或失败由调用方提示) */
    onCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualCode by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun submitCode(code: String) {
        if (submitted) return
        submitted = true
        onCode(code)
    }

    fun openExternal() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authorizeUrl))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.baidu_auth_title)) },
        text = {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.baidu_auth_in_app_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = ::openExternal) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.baidu_auth_open_external))
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url ?: return false
                                    // oob 结果页:优先从 URL 提取 code 并拦截导航
                                    if (url.host == "openapi.baidu.com" &&
                                        url.path?.contains("/oauth/2.0/oob") == true
                                    ) {
                                        url.getQueryParameter("code")?.let {
                                            submitCode(it)
                                            return true
                                        }
                                        view?.evaluateJavascript("document.body.innerText") { text ->
                                            extractAuthCode(text)?.let(::submitCode)
                                        }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    super.onPageFinished(view, pageUrl)
                                    if (pageUrl?.contains("/oauth/2.0/oob") == true) {
                                        view?.evaluateJavascript("document.body.innerText") { text ->
                                            extractAuthCode(text)?.let(::submitCode)
                                        }
                                    }
                                }
                            }
                            loadUrl(authorizeUrl)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.baidu_auth_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        placeholder = { Text(stringResource(R.string.baidu_auth_code_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = manualCode.isNotBlank() && !submitted,
                        onClick = { submitCode(manualCode.trim()) }
                    ) { Text(stringResource(R.string.baidu_auth_confirm)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** 从 oob 结果页文本中提取授权码(evaluateJavascript 返回带引号的 JSON 字符串字面量) */
internal fun extractAuthCode(jsResult: String?): String? {
    if (jsResult.isNullOrBlank() || jsResult == "null") return null
    // "文本内容" → 还原为真实文本(JSONTokener 处理转义)
    val text = runCatching {
        org.json.JSONTokener(jsResult).nextValue() as? String ?: jsResult
    }.getOrDefault(jsResult)
    // 授权码为 24~64 位字母数字串,取页面中最长的候选
    return Regex("[A-Za-z0-9]{24,64}").findAll(text)
        .map { it.value }
        .maxByOrNull { it.length }
}
