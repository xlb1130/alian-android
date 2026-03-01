package com.alian.assistant.presentation.ui.screens.components

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sqrt
import java.io.File
import java.net.URL

/**
 * Markdown WebView 预览屏幕
 * 使用 WebView 渲染 Markdown 内容，支持链接点击拦截
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownWebViewScreen(
    url: String,
    title: String = "Markdown 预览",
    onBackClick: () -> Unit,
    onLinkClick: (String) -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Markdown 内容状态
    var markdownContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 下载文件处理函数（带超时控制）
    fun downloadFile(fileUrl: String, fileName: String) {
        scope.launch {
            try {
                Log.d("MarkdownWebViewScreen", "开始下载文件: $fileName, url: $fileUrl")
                
                // 下载文件（带30秒超时）
                val fileContent = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(30000) { // 30秒超时
                        URL(fileUrl).readBytes()
                    } ?: throw Exception("下载超时")
                }
                
                // 确定保存路径
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                // 创建文件
                val file = File(downloadsDir, fileName)
                file.writeBytes(fileContent)
                
                Log.d("MarkdownWebViewScreen", "文件下载成功: ${file.absolutePath}")
                
                // 通知系统扫描新文件
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = Uri.fromFile(file)
                context.sendBroadcast(intent)
                
                // 提示用户
                Toast.makeText(
                    context,
                    "下载成功: $fileName",
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                Log.e("MarkdownWebViewScreen", "下载文件失败: ${e.message}", e)
                Toast.makeText(
                    context,
                    "下载失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 加载 Markdown 内容（带超时控制）
    LaunchedEffect(url) {
        Log.d("MarkdownWebViewScreen", "开始加载 Markdown: $url")
        isLoading = true
        errorMessage = null
        try {
            val content = withContext(Dispatchers.IO) {
                withTimeoutOrNull(30000) { // 30秒超时
                    URL(url).readText(Charsets.UTF_8)
                } ?: throw Exception("加载超时")
            }
            markdownContent = content
            Log.d("MarkdownWebViewScreen", "Markdown 加载成功，长度: ${content.length}")
        } catch (e: Exception) {
            Log.e("MarkdownWebViewScreen", "加载 Markdown 失败: ${e.message}", e)
            if (e.message == "加载超时") {
                errorMessage = "加载超时，请检查网络连接"
            } else {
                errorMessage = "加载失败: ${e.message}"
            }
        } finally {
            isLoading = false
        }
    }

    // 监听系统后退键
    BackHandler {
        onBackClick()
    }

    Scaffold(
        topBar = {
            // 自定义顶部栏，让 title 置顶
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 返回按钮
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.textPrimary
                        )
                    }

                    // 标题
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // 下载按钮
                    IconButton(onClick = {
                        downloadFile(url, title)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "下载",
                            tint = colors.textPrimary
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    // 加载中状态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = colors.primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "加载中...",
                                color = colors.textSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                errorMessage != null -> {
                    // 错误状态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = errorMessage ?: "未知错误",
                                color = colors.error,
                                fontSize = 16.sp
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        // 重新加载（带超时控制）
                                        isLoading = true
                                        errorMessage = null
                                        try {
                                            val content = withContext(Dispatchers.IO) {
                                                withTimeoutOrNull(30000) { // 30秒超时
                                                    URL(url).readText(Charsets.UTF_8)
                                                } ?: throw Exception("加载超时")
                                            }
                                            markdownContent = content
                                        } catch (e: Exception) {
                                            if (e.message == "加载超时") {
                                                errorMessage = "加载超时，请检查网络连接"
                                            } else {
                                                errorMessage = "加载失败: ${e.message}"
                                            }
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary
                                )
                            ) {
                                Text("重试", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
                markdownContent != null -> {
                    // 显示 WebView
                    MarkdownWebView(
                        content = markdownContent!!,
                        onLinkClick = onLinkClick
                    )
                }
            }
        }
    }
}

/**
 * Markdown WebView 组件
 * 使用 WebView 渲染 Markdown 内容
 */
@Composable
fun MarkdownWebView(
    content: String,
    onLinkClick: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE
                settings.cacheMode = WebSettings.LOAD_DEFAULT

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("MarkdownWebView", "页面加载完成")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e("MarkdownWebView", "WebView 加载错误: ${error.description} (code: ${error.errorCode})")
                        // 可以在这里添加错误处理逻辑，比如显示错误提示
                    }

                    @Deprecated("Deprecated in API 24")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        Log.e("MarkdownWebView", "WebView 加载错误 (旧API): $description (code: $errorCode, url: $failingUrl)")
                    }
                }

                // 添加 JavaScript 接口
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onLinkClick(url: String) {
                        Log.d("MarkdownWebView", "链接被点击: $url")
                        onLinkClick(url)
                    }
                }, "Android")

                // 转义 Markdown 内容中的特殊字符
                val escapedContent = content
                    .replace("\\", "\\\\")
                    .replace("`", "\\`")
                    .replace("$", "\\$")
                    .replace("{", "\\{")
                    .replace("}", "\\}")

                // 根据当前主题生成 CSS 样式
                // 使用 backgroundCard 而不是 background，避免白天模式下的纯白背景闪烁
                val backgroundColor = colors.backgroundCard
                val textColor = colors.textPrimary
                val headingColor = if (colors.isDark) {
                    "#e0e0e0"
                } else {
                    "#0f0f0f"
                }
                val borderColor = if (colors.isDark) {
                    "#3e3e3e"
                } else {
                    "#e5e7eb"
                }
                val codeBgColor = if (colors.isDark) {
                    "rgba(110, 118, 129, 0.4)"
                } else {
                    "rgba(0, 0, 0, 0.06)"
                }
                val preBgColor = if (colors.isDark) {
                    "#161b22"
                } else {
                    "#f6f8fa"
                }
                val quoteColor = if (colors.isDark) {
                    "#8b949e"
                } else {
                    "#57606a"
                }
                val quoteBorderColor = if (colors.isDark) {
                    "#3fb950"
                } else {
                    "#0969da"
                }
                val linkColor = if (colors.isDark) {
                    "#64B5F6"
                } else {
                    "#0969da"
                }
                val tableHeaderBg = if (colors.isDark) {
                    "#161b22"
                } else {
                    "#f6f8fa"
                }
                val tableEvenRowBg = if (colors.isDark) {
                    "rgba(110, 118, 129, 0.1)"
                } else {
                    "rgba(0, 0, 0, 0.03)"
                }

                // 加载 Markdown 渲染库和样式（使用本地 marked.js）
                loadDataWithBaseURL(
                    "file:///android_asset/",
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <script src="marked.min.js"></script>
                        <style>
                            * {
                                box-sizing: border-box;
                            }
                            body {
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                                padding: 16px;
                                margin: 0;
                                background-color: ${backgroundColor.toHex()};
                                color: ${textColor.toHex()};
                                line-height: 1.6;
                            }
                            h1, h2, h3, h4, h5, h6 {
                                color: ${headingColor};
                                margin-top: 24px;
                                margin-bottom: 16px;
                                font-weight: 600;
                            }
                            h1 { font-size: 2em; border-bottom: 1px solid ${borderColor}; padding-bottom: 0.3em; }
                            h2 { font-size: 1.5em; border-bottom: 1px solid ${borderColor}; padding-bottom: 0.3em; }
                            h3 { font-size: 1.25em; }
                            h4 { font-size: 1em; }
                            h5 { font-size: 0.875em; }
                            h6 { font-size: 0.85em; color: ${quoteColor}; }
                            p {
                                margin-bottom: 16px;
                            }
                            a {
                                color: ${linkColor};
                                text-decoration: none;
                                cursor: pointer;
                            }
                            a:hover {
                                text-decoration: underline;
                            }
                            code {
                                font-family: 'Courier New', Courier, monospace;
                                background-color: ${codeBgColor};
                                padding: 0.2em 0.4em;
                                border-radius: 6px;
                                font-size: 85%;
                            }
                            pre {
                                background-color: ${preBgColor};
                                padding: 16px;
                                border-radius: 6px;
                                overflow-x: auto;
                                margin-bottom: 16px;
                            }
                            pre code {
                                background-color: transparent;
                                padding: 0;
                                border-radius: 0;
                                font-size: 100%;
                            }
                            blockquote {
                                border-left: 4px solid ${quoteBorderColor};
                                padding-left: 16px;
                                margin-left: 0;
                                color: ${quoteColor};
                                margin-bottom: 16px;
                            }
                            ul, ol {
                                padding-left: 2em;
                                margin-bottom: 16px;
                            }
                            li {
                                margin-bottom: 4px;
                            }
                            table {
                                border-collapse: collapse;
                                width: 100%;
                                margin-bottom: 16px;
                            }
                            th, td {
                                border: 1px solid ${borderColor};
                                padding: 8px 12px;
                                text-align: left;
                            }
                            th {
                                background-color: ${tableHeaderBg};
                                font-weight: 600;
                            }
                            tr:nth-child(even) {
                                background-color: ${tableEvenRowBg};
                            }
                            img {
                                max-width: 100%;
                                height: auto;
                            }
                            hr {
                                border: none;
                                border-top: 1px solid ${borderColor};
                                margin: 24px 0;
                            }
                        </style>
                    </head>
                    <body>
                        <div id="content"></div>
                        <script>
                            // 配置 marked.js
                            marked.setOptions({
                                breaks: true,
                                gfm: true
                            });

                            // 渲染 Markdown
                            document.getElementById('content').innerHTML = marked.parse(`$escapedContent`);

                            // 拦截链接点击
                            document.addEventListener('click', function(e) {
                                var target = e.target;
                                while (target && target.tagName !== 'A') {
                                    target = target.parentElement;
                                }
                                if (target && target.tagName === 'A') {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    Android.onLinkClick(target.href);
                                }
                            });
                        </script>
                    </body>
                    </html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * 将 Color 转换为十六进制字符串
 */
private fun Color.toHex(): String {
    return String.format("#%02X%02X%02X",
        (this.red * 255).toInt(),
        (this.green * 255).toInt(),
        (this.blue * 255).toInt()
    )
}

/**
 * 检测 URL 是否是附件 URL
 * 没有扩展名的 URL 默认视为 Markdown 附件
 */
fun isAttachmentUrl(url: String): Boolean {
    Log.d("isAttachmentUrl", "检查 URL: $url")
    // 去掉查询参数和片段
    val urlWithoutQuery = url.substringBefore("?").substringBefore("#")
    Log.d("isAttachmentUrl", "去掉查询参数后: $urlWithoutQuery")
    
    // 检查是否是 /api/v1/files/ 路径的附件 URL
    if (urlWithoutQuery.contains("/api/v1/files/") || urlWithoutQuery.contains("/files/")) {
        Log.d("isAttachmentUrl", "检测到文件 API 路径，视为附件")
        return true
    }
    
    // 检查是否有文件扩展名
    val lastDotIndex = urlWithoutQuery.lastIndexOf('.')
    if (lastDotIndex == -1 || lastDotIndex == urlWithoutQuery.length - 1) {
        // 没有扩展名，默认视为 Markdown 附件
        Log.d("isAttachmentUrl", "没有扩展名，默认视为 Markdown 附件")
        return true
    }
    
    // 检查常见的附件文件扩展名
    val attachmentExtensions = listOf(
        ".md", ".txt", ".pdf", ".doc", ".docx",
        ".jpg", ".jpeg", ".png", ".gif", ".bmp",
        ".mp4", ".avi", ".mov", ".mkv",
        ".mp3", ".wav", ".ogg",
        ".zip", ".rar", ".7z",
        ".json", ".xml", ".csv"
    )
    val result = attachmentExtensions.any { urlWithoutQuery.lowercase().endsWith(it) }
    Log.d("isAttachmentUrl", "检测结果: $result")
    return result
}