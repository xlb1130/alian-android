package com.alian.assistant.core.mcp

import android.content.Context
import com.alian.assistant.core.mcp.models.MCPServerConfig
import com.alian.assistant.core.mcp.models.MCPToolDefinition
import com.alian.assistant.core.tools.ToolResult
import com.alian.assistant.data.repository.MCPRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * MCP 管理器（单例）
 * 统一管理所有 MCP Server 配置和工具调用
 */
class MCPManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "MCPManager"
        
        // 保活检查间隔（5分钟）
        private const val KEEPALIVE_INTERVAL = 5 * 60 * 1000L
        
        // 连接重试间隔（30秒）
        private const val RETRY_INTERVAL = 30 * 1000L
        
        @Volatile
        private var instance: MCPManager? = null
        
        /**
         * 初始化 MCPManager
         */
        fun init(context: Context): MCPManager {
            return instance ?: synchronized(this) {
                instance ?: MCPManager(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * 获取 MCPManager 实例
         */
        fun getInstance(): MCPManager {
            return instance ?: throw IllegalStateException("MCPManager not initialized. Call init() first.")
        }
        
        /**
         * 检查是否已初始化
         */
        fun isInitialized(): Boolean = instance != null
    }
    
    private val repository = MCPRepository(context)
    
    // MCP 客户端缓存
    private val clients = mutableMapOf<String, MCPClient>()

    // 工具定义缓存
    private val toolsCache = mutableMapOf<String, List<MCPToolDefinition>>()

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 保活任务
    private var keepaliveJob: Job? = null
    
    // 是否已完成预初始化
    private val _isPreInitialized = MutableStateFlow(false)
    val isPreInitialized: StateFlow<Boolean> = _isPreInitialized.asStateFlow()
    
    // Server 连接状态 Map
    private val _serverConnectionStates = MutableStateFlow<Map<String, MCPClient.ConnectionState>>(emptyMap())
    val serverConnectionStates: StateFlow<Map<String, MCPClient.ConnectionState>> = _serverConnectionStates.asStateFlow()
    
    // 初始化错误信息
    private val _initializationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val initializationErrors: StateFlow<Map<String, String>> = _initializationErrors.asStateFlow()
    
    /**
     * 获取所有 MCP Server 配置
     */
    fun getAllServers(): List<MCPServerConfig> {
        return repository.getAllServers()
    }
    
    /**
     * 获取已启用的 MCP Server 配置
     */
    fun getEnabledServers(): List<MCPServerConfig> {
        return repository.getEnabledServers()
    }
    
    /**
     * 添加 MCP Server（从 JSON 解析）
     */
    suspend fun addServerFromJson(json: String): Result<MCPServerConfig> {
        android.util.Log.d(TAG, "开始添加 MCP Server，JSON: $json")
        return try {
            val config = MCPServerConfig.fromJson(json)
            android.util.Log.d(TAG, "JSON 解析结果: ${config.isSuccess}")
            if (config.isFailure) {
                android.util.Log.e(TAG, "JSON 解析失败: ${config.exceptionOrNull()?.message}", config.exceptionOrNull())
                return Result.failure(config.exceptionOrNull() ?: Exception("JSON 解析失败"))
            }
            
            val serverConfig = config.getOrNull()!!
            android.util.Log.d(TAG, "Server 配置: name=${serverConfig.name}, url=${serverConfig.url}, transport=${serverConfig.transport}")
            
            if (!serverConfig.isValid()) {
                android.util.Log.e(TAG, "配置无效")
                return Result.failure(Exception("Invalid MCP Server configuration"))
            }
            
            android.util.Log.d(TAG, "开始保存配置")
            repository.saveServer(config.getOrNull()!!)
            android.util.Log.d(TAG, "配置保存成功")
            
            android.util.Log.d(TAG, "Added MCP Server: ${serverConfig.name}")
            Result.success(serverConfig)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to add MCP Server", e)
            Result.failure(e)
        }
    }
    
    /**
     * 启用/禁用 MCP Server
     */
    suspend fun setServerEnabled(serverId: String, enabled: Boolean) {
        val server = repository.getServerById(serverId)
            ?: return
        
        val updatedServer = server.copy(enabled = enabled)
        repository.updateServer(updatedServer)
        
        if (enabled) {
            // 启用时，预热加载该 Server
            preInitializeServer(serverId)
        } else {
            // 禁用时，断开连接并释放资源
            clients[serverId]?.disconnect()
            clients.remove(serverId)
            toolsCache.remove(serverId)
            
            // 更新连接状态
            val connectionStates = _serverConnectionStates.value.toMutableMap()
            connectionStates.remove(serverId)
            _serverConnectionStates.value = connectionStates
            
            // 清除错误信息
            val errors = _initializationErrors.value.toMutableMap()
            errors.remove(serverId)
            _initializationErrors.value = errors
        }
        
        android.util.Log.d("MCPManager", "Server ${server.name} ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * 更新 MCP Server 配置
     */
    suspend fun updateServer(config: MCPServerConfig) {
        // 断开旧连接
        clients[config.id]?.disconnect()
        clients.remove(config.id)
        toolsCache.remove(config.id)
        
        // 更新配置
        repository.updateServer(config)
        
        android.util.Log.d("MCPManager", "Updated MCP Server: ${config.name}")
    }
    
    /**
     * 删除 MCP Server
     */
    suspend fun removeServer(serverId: String) {
        // 断开连接
        clients[serverId]?.disconnect()
        clients.remove(serverId)
        toolsCache.remove(serverId)
        
        // 删除配置
        repository.deleteServer(serverId)
        
        android.util.Log.d("MCPManager", "Removed MCP Server: $serverId")
    }
    
    /**
     * 获取所有可用的 MCP 工具
     */
    suspend fun getAvailableTools(): List<MCPToolDefinition> {
        val enabledServers = getEnabledServers()
        val allTools = mutableListOf<MCPToolDefinition>()
        
        for (server in enabledServers) {
            val tools = getToolsForServer(server)
            allTools.addAll(tools)
        }
        
        return allTools
    }
    
    /**
     * 获取指定 Server 的工具
     */
    private suspend fun getToolsForServer(server: MCPServerConfig): List<MCPToolDefinition> {
        // 使用缓存
        toolsCache[server.id]?.let { return it }
        
        // 检查客户端是否已就绪
        val existingClient = clients[server.id]
        if (existingClient?.isReady() == true) {
            // 客户端已就绪，直接获取工具
            val toolsResult = existingClient.listTools()
            if (toolsResult.isSuccess) {
                val tools = toolsResult.getOrNull() ?: emptyList()
                toolsCache[server.id] = tools
                return tools
            }
        }
        
        // 客户端未就绪，需要连接并初始化
        val client = getClientForServer(server)
        
        // 如果客户端已连接但未初始化，直接初始化
        if (client.connectionState.value == MCPClient.ConnectionState.CONNECTED && !client.isReady()) {
            val initResult = client.initialize()
            if (initResult.isFailure) {
                android.util.Log.e(TAG, "Failed to initialize ${server.name}", initResult.exceptionOrNull())
                return emptyList()
            }
        } else if (client.connectionState.value != MCPClient.ConnectionState.CONNECTED) {
            // 需要重新连接
            val connectResult = client.connect()
            
            if (connectResult.isFailure) {
                android.util.Log.e(TAG, "Failed to connect to ${server.name}", connectResult.exceptionOrNull())
                return emptyList()
            }
            
            // 初始化 MCP 会话（必须先初始化才能调用其他方法）
            val initResult = client.initialize()
            if (initResult.isFailure) {
                android.util.Log.e(TAG, "Failed to initialize ${server.name}", initResult.exceptionOrNull())
                return emptyList()
            }
        }

        val toolsResult = client.listTools()
        if (toolsResult.isFailure) {
            android.util.Log.e(TAG, "Failed to list tools from ${server.name}", toolsResult.exceptionOrNull())
            return emptyList()
        }
        
        val tools = toolsResult.getOrNull() ?: emptyList()
        toolsCache[server.id] = tools
        
        return tools
    }
    
    /**
     * 获取指定 Server 的 MCP 客户端
     */
    private fun getClientForServer(server: MCPServerConfig): MCPClient {
        return clients.getOrPut(server.id) {
            MCPClient(server)
        }
    }
    
    /**
     * 执行 MCP 工具
     */
    suspend fun executeTool(
        toolName: String,
        params: Map<String, Any?>
    ): ToolResult {
        return try {
            // 查找工具所属的 Server
            val enabledServers = getEnabledServers()
            var toolServer: MCPServerConfig? = null
            var toolDefinition: MCPToolDefinition? = null
            
            for (server in enabledServers) {
                val tools = getToolsForServer(server)
                val found = tools.find { it.name == toolName }
                if (found != null) {
                    toolServer = server
                    toolDefinition = found
                    break
                }
            }
            
            if (toolServer == null || toolDefinition == null) {
                return ToolResult.Error("Tool not found: $toolName")
            }
            
            // 执行工具
            val client = getClientForServer(toolServer)
            val result = client.callTool(toolName, params)
            
            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                return ToolResult.Error("Failed to execute tool: $error")
            }
            
            val data = result.getOrNull()
            ToolResult.Success(data, "Tool executed successfully")
        } catch (e: Exception) {
            android.util.Log.e("MCPManager", "Failed to execute tool: $toolName", e)
            ToolResult.Error("Tool execution failed: ${e.message}")
        }
    }
    
    /**
     * 获取工具描述（给 LLM）
     */
    suspend fun getToolDescriptions(): String {
        val tools = getAvailableTools()
        if (tools.isEmpty()) {
            return ""
        }
        
        return tools.joinToString("\n\n") { tool ->
            """
            |工具: ${tool.name}
            |说明: ${tool.description}
            |参数: ${tool.inputSchema.toString()}
            """.trimMargin()
        }
    }
    
    /**
     * 获取工具定义（OpenAI Function Calling 格式）
     */
    suspend fun getToolsAsJsonArray(): JSONArray {
        val tools = getAvailableTools()
        val toolsArray = JSONArray()
        
        tools.forEach { tool ->
            toolsArray.put(tool.toOpenAIFunction())
        }
        
        return toolsArray
    }
    
    /**
     * 获取已启用的工具
     */
    suspend fun getEnabledTools(): List<MCPToolDefinition> {
        return getAvailableTools()
    }
    
    /**
     * 刷新所有工具缓存
     */
    suspend fun refreshTools() {
        toolsCache.clear()
        android.util.Log.d(TAG, "Tools cache cleared")
    }
    
    /**
     * 断开所有连接
     */
    fun disconnectAll() {
        stopKeepalive()
        clients.values.forEach { it.disconnect() }
        clients.clear()
        toolsCache.clear()
        _serverConnectionStates.value = emptyMap()
        _isPreInitialized.value = false
        android.util.Log.d(TAG, "All clients disconnected")
    }
    
    /**
     * 重新连接所有已启用的 Server
     */
    suspend fun reconnectAll() {
        disconnectAll()
        val enabledServers = getEnabledServers()
        for (server in enabledServers) {
            try {
                val client = getClientForServer(server)
                client.connect()
                android.util.Log.d(TAG, "Reconnected to ${server.name}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to reconnect to ${server.name}", e)
            }
        }
    }
    
    /**
     * 获取服务器列表的 Flow
     */
    fun getServersFlow(): Flow<List<MCPServerConfig>> {
        return repository.serversFlow
    }

    // ==================== 预初始化和保活 ====================
    
    /**
     * 预初始化单个 MCP Server
     * 用于用户手动开启 MCP Server 时预热加载
     */
    fun preInitializeServer(serverId: String) {
        scope.launch {
            val server = repository.getServerById(serverId)
            if (server == null) {
                android.util.Log.e(TAG, "Server not found: $serverId")
                return@launch
            }
            
            if (!server.enabled) {
                android.util.Log.d(TAG, "Server ${server.name} is disabled, skip pre-initialization")
                return@launch
            }
            
            android.util.Log.d(TAG, "开始预热 MCP Server: ${server.name}")
            
            val connectionStates = _serverConnectionStates.value.toMutableMap()
            val errors = _initializationErrors.value.toMutableMap()
            
            try {
                val client = getClientForServer(server)
                connectionStates[server.id] = MCPClient.ConnectionState.CONNECTING
                _serverConnectionStates.value = connectionStates.toMap()
                
                // 连接
                val connectResult = client.connect()
                if (connectResult.isFailure) {
                    val error = connectResult.exceptionOrNull()?.message ?: "连接失败"
                    errors[server.id] = error
                    connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                    _serverConnectionStates.value = connectionStates.toMap()
                    _initializationErrors.value = errors
                    android.util.Log.e(TAG, "Failed to connect to ${server.name}: $error")
                    return@launch
                }
                
                // 初始化
                val initResult = client.initialize()
                if (initResult.isFailure) {
                    val error = initResult.exceptionOrNull()?.message ?: "初始化失败"
                    errors[server.id] = error
                    connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                    _serverConnectionStates.value = connectionStates.toMap()
                    _initializationErrors.value = errors
                    android.util.Log.e(TAG, "Failed to initialize ${server.name}: $error")
                    return@launch
                }
                
                // 获取工具列表
                val toolsResult = client.listTools()
                if (toolsResult.isSuccess) {
                    val tools = toolsResult.getOrNull() ?: emptyList()
                    toolsCache[server.id] = tools
                    android.util.Log.d(TAG, "Server ${server.name} 已加载 ${tools.size} 个工具")
                }
                
                // 更新状态
                connectionStates[server.id] = MCPClient.ConnectionState.CONNECTED
                errors.remove(server.id)
                _serverConnectionStates.value = connectionStates.toMap()
                _initializationErrors.value = errors
                
                android.util.Log.d(TAG, "Server ${server.name} 预热成功")
                
            } catch (e: Exception) {
                errors[server.id] = e.message ?: "未知错误"
                connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                _serverConnectionStates.value = connectionStates.toMap()
                _initializationErrors.value = errors
                android.util.Log.e(TAG, "Server ${server.name} 预热异常", e)
            }
        }
    }
    
    /**
     * 预初始化所有已启用的 MCP Server
     * 在应用启动时调用，提前建立连接并获取工具列表
     */
    fun preInitializeServers() {
        scope.launch {
            android.util.Log.d(TAG, "开始预初始化 MCP Servers...")
            val enabledServers = getEnabledServers()
            
            if (enabledServers.isEmpty()) {
                android.util.Log.d(TAG, "没有已启用的 MCP Server，跳过预初始化")
                _isPreInitialized.value = true
                return@launch
            }
            
            android.util.Log.d(TAG, "预初始化 ${enabledServers.size} 个 MCP Server")
            
            val errors = mutableMapOf<String, String>()
            val connectionStates = mutableMapOf<String, MCPClient.ConnectionState>()
            
            // 并行初始化所有 Server
            enabledServers.forEach { server ->
                launch {
                    try {
                        val client = getClientForServer(server)
                        connectionStates[server.id] = MCPClient.ConnectionState.CONNECTING
                        _serverConnectionStates.value = connectionStates.toMap()
                        
                        // 连接
                        val connectResult = client.connect()
                        if (connectResult.isFailure) {
                            val error = connectResult.exceptionOrNull()?.message ?: "连接失败"
                            errors[server.id] = error
                            connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                            _serverConnectionStates.value = connectionStates.toMap()
                            android.util.Log.e(TAG, "Failed to connect to ${server.name}: $error")
                            return@launch
                        }
                        
                        // 初始化
                        val initResult = client.initialize()
                        if (initResult.isFailure) {
                            val error = initResult.exceptionOrNull()?.message ?: "初始化失败"
                            errors[server.id] = error
                            connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                            _serverConnectionStates.value = connectionStates.toMap()
                            android.util.Log.e(TAG, "Failed to initialize ${server.name}: $error")
                            return@launch
                        }
                        
                        // 获取工具列表
                        val toolsResult = client.listTools()
                        if (toolsResult.isSuccess) {
                            val tools = toolsResult.getOrNull() ?: emptyList()
                            toolsCache[server.id] = tools
                            android.util.Log.d(TAG, "Server ${server.name} 已加载 ${tools.size} 个工具")
                        }
                        
                        connectionStates[server.id] = MCPClient.ConnectionState.CONNECTED
                        _serverConnectionStates.value = connectionStates.toMap()
                        android.util.Log.d(TAG, "Server ${server.name} 预初始化成功")
                        
                    } catch (e: Exception) {
                        errors[server.id] = e.message ?: "未知错误"
                        connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                        _serverConnectionStates.value = connectionStates.toMap()
                        android.util.Log.e(TAG, "Server ${server.name} 预初始化异常", e)
                    }
                }
            }
            
            // 等待所有初始化完成
            delay(1000)
            
            _initializationErrors.value = errors
            _isPreInitialized.value = true
            
            val successCount = enabledServers.size - errors.size
            android.util.Log.d(TAG, "MCP 预初始化完成: 成功 $successCount/${enabledServers.size}")
            
            // 启动保活任务
            startKeepalive()
        }
    }
    
    /**
     * 启动保活任务
     * 定期检查连接状态并重连断开的 Server
     */
    fun startKeepalive() {
        if (keepaliveJob?.isActive == true) {
            android.util.Log.d(TAG, "保活任务已在运行")
            return
        }
        
        keepaliveJob = scope.launch {
            android.util.Log.d(TAG, "启动 MCP 保活任务")
            
            while (isActive) {
                delay(KEEPALIVE_INTERVAL)
                
                try {
                    android.util.Log.d(TAG, "执行 MCP 保活检查...")
                    checkAndReconnect()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "保活检查异常", e)
                }
            }
        }
    }
    
    /**
     * 停止保活任务
     */
    fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        android.util.Log.d(TAG, "停止 MCP 保活任务")
    }
    
    /**
     * 检查并重连断开的 Server
     */
    private suspend fun checkAndReconnect() {
        val enabledServers = getEnabledServers()
        val connectionStates = _serverConnectionStates.value.toMutableMap()
        
        for (server in enabledServers) {
            val client = clients[server.id]
            val currentState = client?.connectionState?.value ?: MCPClient.ConnectionState.DISCONNECTED
            
            // 如果客户端不存在或连接断开，尝试重连
            if (client == null || currentState != MCPClient.ConnectionState.CONNECTED || !client.isReady()) {
                android.util.Log.d(TAG, "Server ${server.name} 连接断开，尝试重连...")
                
                connectionStates[server.id] = MCPClient.ConnectionState.CONNECTING
                _serverConnectionStates.value = connectionStates.toMap()
                
                try {
                    // 断开旧连接
                    client?.disconnect()
                    
                    // 创建新连接
                    val newClient = getClientForServer(server)
                    val connectResult = newClient.connect()
                    
                    if (connectResult.isFailure) {
                        connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                        _serverConnectionStates.value = connectionStates.toMap()
                        android.util.Log.e(TAG, "重连 ${server.name} 失败: ${connectResult.exceptionOrNull()?.message}")
                        continue
                    }
                    
                    // 初始化
                    val initResult = newClient.initialize()
                    if (initResult.isFailure) {
                        connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                        _serverConnectionStates.value = connectionStates.toMap()
                        android.util.Log.e(TAG, "重连 ${server.name} 初始化失败: ${initResult.exceptionOrNull()?.message}")
                        continue
                    }
                    
                    // 刷新工具缓存
                    val toolsResult = newClient.listTools()
                    if (toolsResult.isSuccess) {
                        toolsCache[server.id] = toolsResult.getOrNull() ?: emptyList()
                    }
                    
                    connectionStates[server.id] = MCPClient.ConnectionState.CONNECTED
                    _serverConnectionStates.value = connectionStates.toMap()
                    android.util.Log.d(TAG, "Server ${server.name} 重连成功")
                    
                } catch (e: Exception) {
                    connectionStates[server.id] = MCPClient.ConnectionState.ERROR
                    _serverConnectionStates.value = connectionStates.toMap()
                    android.util.Log.e(TAG, "重连 ${server.name} 异常", e)
                }
            }
        }
    }
    
    /**
     * 获取指定 Server 的连接状态
     */
    fun getServerConnectionState(serverId: String): MCPClient.ConnectionState {
        return _serverConnectionStates.value[serverId] ?: MCPClient.ConnectionState.DISCONNECTED
    }
    
    /**
     * 获取指定 Server 的初始化错误
     */
    fun getServerError(serverId: String): String? {
        return _initializationErrors.value[serverId]
    }
    
    /**
     * 检查所有 Server 是否已就绪
     */
    fun areAllServersReady(): Boolean {
        val enabledServers = getEnabledServers()
        if (enabledServers.isEmpty()) return true
        
        return enabledServers.all { server ->
            val client = clients[server.id]
            client?.isReady() == true
        }
    }
    
    /**
     * 获取就绪的 Server 数量
     */
    fun getReadyServerCount(): Int {
        val enabledServers = getEnabledServers()
        return enabledServers.count { server ->
            clients[server.id]?.isReady() == true
        }
    }
}