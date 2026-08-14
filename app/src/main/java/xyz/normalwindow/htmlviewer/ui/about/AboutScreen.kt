package xyz.normalwindow.htmlviewer.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.normalwindow.htmlviewer.BuildConfig
import xyz.normalwindow.htmlviewer.R

/** 关于页:应用信息 / GitHub 仓库 / 开源许可 / 技术栈 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            // 应用图标:adaptive-icon 无法直接 painterResource 加载,
            // 用前景 vector + 主题色背景合成圆形 Logo
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            // GitHub 仓库(点击外部浏览器打开)
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_github)) },
                supportingContent = { Text("github.com/normalwindow/NormlW-HTMLviewer") },
                leadingContent = { Icon(Icons.Filled.Link, contentDescription = null) },
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/normalwindow/NormlW-HTMLviewer"))
                        )
                    }
                }
            )
            HorizontalDivider()
            // 开源许可
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_license)) },
                supportingContent = { Text(stringResource(R.string.about_license_value)) },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) }
            )
            HorizontalDivider()
            // 技术栈
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_tech_stack)) },
                supportingContent = { Text(stringResource(R.string.about_tech_value)) },
                leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) }
            )
            HorizontalDivider()
            // 内核版本
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_engine_versions)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_engine_versions_value,
                            runCatching {
                                androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName
                            }.getOrNull() ?: "-",
                            if (BuildConfig.GECKO_ENABLED) "153.0" else "-"
                        )
                    )
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
