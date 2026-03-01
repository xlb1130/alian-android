package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.core.mcp.MCPManager
import com.alian.assistant.core.mcp.models.MCPServerConfig
import com.alian.assistant.core.mcp.models.MCPTransport
import kotlinx.coroutines.launch

/**
 * MCP 设置页面
 * 管理 MCP Server 配置
 */
@Composable
fun MCPSettingsContent(
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<MCPServerConfig>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<MCPServerConfig?>(null) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<MCPServerConfig?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isInitialized by remember { mutableStateOf(MCPManager.isInitialized()) }

    // 加载服务器列表
    LaunchedEffect(Unit) {
        if (MCPManager.isInitialized()) {
            isInitialized = true
            MCPManager.getInstance().getServersFlow().collect { serverList ->
                servers = serverList
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isInitialized) {
                ErrorState(
                    message = "MCP Manager 未初始化",
                    details = "请检查应用初始化逻辑"
                )
            } else if (servers.isEmpty()) {
                EmptyState(
                    onAddServer = { showAddDialog = true }
                )
            } else {
                // 添加按钮
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加 MCP Server")
                }

                servers.forEach { server ->
                    ServerCard(
                        server = server,
                        isExpanded = expandedServerId == server.id,
                        onToggle = {
                            expandedServerId = if (expandedServerId == server.id) null else server.id
                        },
                        onEnabledChange = { enabled ->
                            scope.launch {
                                MCPManager.getInstance().setServerEnabled(server.id, enabled)
                            }
                        },
                        onEdit = {
                            showEditDialog = server
                        },
                        onDelete = {
                            showDeleteDialog = server
                        }
                    )
                }
            }
        }
    }

    // 添加 MCP Server 对话框
    if (showAddDialog) {
        AddMCPServerDialog(
            onDismiss = {
                showAddDialog = false
                errorMessage = null
            },
            onConfirm = { json ->
                android.util.Log.d("MCPSettingsContent", "开始添加 MCP Server，JSON: $json")
                scope.launch {
                    if (!MCPManager.isInitialized()) {
                        android.util.Log.e("MCPSettingsContent", "MCP Manager 未初始化")
                        errorMessage = "MCP Manager 未初始化"
                        return@launch
                    }

                    android.util.Log.d("MCPSettingsContent", "调用 addServerFromJson")
                    val result = MCPManager.getInstance().addServerFromJson(json)
                    android.util.Log.d("MCPSettingsContent", "addServerFromJson 结果: isSuccess=${result.isSuccess}, exception=${result.exceptionOrNull()?.message}")
                    
                    if (result.isSuccess) {
                        android.util.Log.d("MCPSettingsContent", "添加成功")
                        showAddDialog = false
                        errorMessage = null
                    } else {
                        android.util.Log.e("MCPSettingsContent", "添加失败: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
                        errorMessage = result.exceptionOrNull()?.message ?: "添加失败"
                    }
                }
            },
            errorMessage = errorMessage
        )
    }

    // 删除确认对话框
    if (showDeleteDialog != null) {
        DeleteConfirmDialog(
            server = showDeleteDialog!!,
            onDismiss = { showDeleteDialog = null },
            onConfirm = {
                // 捕获当前要删除的 server，避免在协程执行期间 showDeleteDialog 被清空
                val serverToDelete = showDeleteDialog
                if (serverToDelete != null) {
                    scope.launch {
                        if (MCPManager.isInitialized()) {
                            MCPManager.getInstance().removeServer(serverToDelete.id)
                        }
                        showDeleteDialog = null
                        if (expandedServerId == serverToDelete.id) {
                            expandedServerId = null
                        }
                    }
                }
            }
        )
    }

    // 编辑 MCP Server 对话框
    if (showEditDialog != null) {
        EditMCPServerDialog(
            server = showEditDialog!!,
            onDismiss = {
                showEditDialog = null
                errorMessage = null
            },
            onConfirm = { json ->
                android.util.Log.d("MCPSettingsContent", "开始更新 MCP Server，JSON: $json")
                // 捕获当前要编辑的 server，避免在协程执行期间 showEditDialog 被清空
                val serverToEdit = showEditDialog
                if (serverToEdit != null) {
                    scope.launch {
                        if (!MCPManager.isInitialized()) {
                            android.util.Log.e("MCPSettingsContent", "MCP Manager 未初始化")
                            errorMessage = "MCP Manager 未初始化"
                            return@launch
                        }

                        val result = com.alian.assistant.core.mcp.models.MCPServerConfig.fromJson(json)
                        if (result.isFailure) {
                            android.util.Log.e("MCPSettingsContent", "JSON 解析失败: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
                            errorMessage = result.exceptionOrNull()?.message ?: "JSON 解析失败"
                            return@launch
                        }

                        val updatedConfig = result.getOrNull()!!
                        
                        // 保持原有的 ID 和 enabled 状态
                        val finalConfig = updatedConfig.copy(
                        id = serverToEdit.id,
                        enabled = serverToEdit.enabled
                    )

                    android.util.Log.d("MCPSettingsContent", "更新配置: ${finalConfig.name}")
                    MCPManager.getInstance().updateServer(finalConfig)
                    android.util.Log.d("MCPSettingsContent", "更新成功")
                    showEditDialog = null
                    errorMessage = null
                    }
                }
            },
            errorMessage = errorMessage
        )
    }
}

/**
 * 空状态
 */
@Composable
private fun EmptyState(
    onAddServer: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Text(
            text = "还没有添加 MCP Server",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "添加 MCP Server\n让 AI 能够调用外部工具和服务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onAddServer,
            modifier = Modifier.padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加 MCP Server")
        }
    }
}

/**
 * 错误状态
 */
@Composable
private fun ErrorState(
    message: String,
    details: String = ""
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * MCP Server 卡片
 */
@Composable
private fun ServerCard(
    server: MCPServerConfig,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (server.enabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 头部
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标和名称
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = if (server.enabled) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (server.description != null) {
                            Text(
                                text = server.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 启用开关和编辑/删除按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = onEnabledChange
                    )
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // URL 和协议类型
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoRow(
                        label = "URL",
                        value = server.url,
                        isUrl = true
                    )
                    InfoRow(
                        label = "协议",
                        value = when (server.transport) {
                            MCPTransport.WEBSOCKET -> "WebSocket"
                            MCPTransport.HTTP -> "HTTP"
                            MCPTransport.SSE -> "SSE"
                        }
                    )
                    if (server.apiKey != null) {
                        InfoRow(
                            label = "API Key",
                            value = "••••••••••••",
                            isSensitive = true
                        )
                    }
                    if (!server.headers.isNullOrEmpty()) {
                        InfoRow(
                            label = "自定义 Headers",
                            value = "${server.headers.size} 个"
                        )
                    }
                }
            }
        }
    }
}

/**
 * 信息行
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    isUrl: Boolean = false,
    isSensitive: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isUrl) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isUrl) FontWeight.Medium else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 添加 MCP Server 对话框
 */
@Composable
private fun AddMCPServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    errorMessage: String? = null
) {
    var jsonInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val displayError = errorMessage ?: validationError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP Server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "请粘贴 MCP Server 的 JSON 配置：",
                    style = MaterialTheme.typography.bodyMedium
                )

                JsonEditor(
                    value = jsonInput,
                    onValueChange = {
                        jsonInput = it
                        validationError = null
                    },
                    placeholder = """{
  "name": "My Server",
  "url": "wss://...",
  "transport": "websocket",
  "apiKey": "your-api-key"
}""",
                    isError = displayError != null,
                    errorMessage = displayError
                )

                Text(
                    text = "💡 支持的认证方式：\n• apiKey: API 密钥（自动加密存储）\n• headers: 自定义请求头（如 Bearer Token）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (jsonInput.isBlank()) {
                        validationError = "请输入 JSON 配置"
                        return@TextButton
                    }

                    try {
                        // 验证 JSON 格式
                        org.json.JSONObject(jsonInput)
                        onConfirm(jsonInput)
                    } catch (e: Exception) {
                        validationError = "JSON 格式错误: ${e.message}"
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 删除确认对话框
 */
@Composable
private fun DeleteConfirmDialog(
    server: MCPServerConfig,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 MCP Server") },
        text = {
            Text("确定要删除「${server.name}」吗？此操作无法撤销。")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 编辑 MCP Server 对话框
 */
@Composable
private fun EditMCPServerDialog(
    server: MCPServerConfig,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    errorMessage: String? = null
) {
    var jsonInput by remember { mutableStateOf(server.toJson()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val displayError = errorMessage ?: validationError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 MCP Server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "编辑「${server.name}」的 JSON 配置：",
                    style = MaterialTheme.typography.bodyMedium
                )

                JsonEditor(
                    value = jsonInput,
                    onValueChange = {
                        jsonInput = it
                        validationError = null
                    },
                    placeholder = server.toJson(),
                    isError = displayError != null,
                    errorMessage = displayError
                )

                Text(
                    text = "💡 提示：\n• 修改后点击保存生效\n• URL 和 transport 字段修改后需要重新连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (jsonInput.isBlank()) {
                        validationError = "请输入 JSON 配置"
                        return@TextButton
                    }

                    try {
                        // 验证 JSON 格式
                        org.json.JSONObject(jsonInput)
                        onConfirm(jsonInput)
                    } catch (e: Exception) {
                        validationError = "JSON 格式错误: ${e.message}"
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
