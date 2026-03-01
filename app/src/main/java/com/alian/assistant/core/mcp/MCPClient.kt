package com.alian.assistant.core.mcp

import com.alian.assistant.core.mcp.models.MCPMessage
import com.alian.assistant.core.mcp.models.MCPMethod
import com.alian.assistant.core.mcp.models.MCPServerConfig
import com.alian.assistant.core.mcp.models.MCPToolDefinition
import com.alian.assistant.core.mcp.models.MCPTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 客户端
 * 支持 WebSocket 和 HTTP 两种传输协议
 */
class MCPClient(private val config: MCPServerConfig) {
    
    companion object {
        private const val TAG = "MCPClient"
        private const val CONNECT_TIMEOUT = 30_000L
        private const val READ_TIMEOUT = 60_000L
        private const val WRITE_TIMEOUT = 30_000L
        // MCP 协议版本（最新稳定版本）
        private const val PROTOCOL_VERSION = "2025-03-26"
    }
    
    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // 是否已完成 MCP 初始化
    private var isInitialized = false
    
    // 获取服务器配置
    val serverConfig: MCPServerConfig get() = config
    
    // 是否已初始化完成
    fun isReady(): Boolean = isInitialized && _connectionState.value == ConnectionState.CONNECTED
    
    // HTTP 客户端
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()
    
    // WebSocket 客户端
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()
    
    // WebSocket 连接
    private var webSocket: WebSocket? = null
    
    // 请求 ID 计数器
    private val requestId = AtomicInteger(0)
    
    // 待处理的请求（用于 WebSocket）
    private val pendingRequests = mutableMapOf<Int, PendingRequest>()

    // MCP Session ID（用于 HTTP 传输）
    private var sessionId: String? = null
    
    /**
     * 连接状态
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    /**
     * 待处理的请求
     */
    private data class PendingRequest(
        val onResponse: (MCPMessage.Response) -> Unit,
        val onError: (Exception) -> Unit
    )
    
    /**
     * 连接到 MCP Server
     */
    suspend fun connect(): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING

            when (config.transport) {
                MCPTransport.WEBSOCKET -> connectWebSocket()
                MCPTransport.HTTP -> connectHTTP()
                MCPTransport.SSE -> connectSSE()
            }

            _connectionState.value = ConnectionState.CONNECTED
            android.util.Log.d(TAG, "Connected to MCP Server: ${config.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            android.util.Log.e(TAG, "Failed to connect to MCP Server: ${config.name}", e)
            Result.failure(e)
        }
    }
    
    /**
     * WebSocket 连接
     */
    private fun connectWebSocket(): Result<Unit> {
        val request = Request.Builder()
            .url(config.url)
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                android.util.Log.d(TAG, "WebSocket opened: ${config.url}")
                _connectionState.value = ConnectionState.CONNECTED
            }
            
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val id = json.optInt("id", -1)
                    
                    if (id != -1) {
                        val response = MCPMessage.Response.fromJson(json)
                        pendingRequests.remove(id)?.let { pending ->
                            if (response.isError) {
                                pending.onError(Exception(response.error?.toString() ?: "Unknown error"))
                            } else {
                                pending.onResponse(response)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to parse WebSocket message", e)
                }
            }
            
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                android.util.Log.d(TAG, "WebSocket closing: $code - $reason")
            }
            
            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e(TAG, "WebSocket failure", t)
                _connectionState.value = ConnectionState.ERROR
                
                // 通知所有待处理的请求失败
                pendingRequests.values.forEach { pending ->
                    pending.onError(Exception(t.message ?: "WebSocket connection failed"))
                }
                pendingRequests.clear()
            }
        }
        
        webSocket = wsClient.newWebSocket(request, listener)
        return Result.success(Unit)
    }
    
    /**
     * HTTP 连接（验证连接）
     */
    private fun connectHTTP(): Result<Unit> {
        val request = Request.Builder()
            .url("${config.url}/health")  // 假设有健康检查端点
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .get()
            .build()
        
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                return Result.success(Unit)
            } else {
                // 如果没有健康检查端点，假设连接成功
                return Result.success(Unit)
            }
        } catch (e: Exception) {
            // 即使健康检查失败，也假设连接成功（因为可能是端点不存在）
            return Result.success(Unit)
        }
    }
    
    /**
     * SSE 连接（Server-Sent Events）
     * SSE 使用 HTTP POST 请求，但响应是事件流
     */
    private fun connectSSE(): Result<Unit> {
        // SSE 连接不需要预连接，在发送请求时建立
        // 这里只是验证连接是否可用
        return Result.success(Unit)
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        pendingRequests.clear()
        sessionId = null  // 清除 session ID
        isInitialized = false  // 重置初始化状态
        _connectionState.value = ConnectionState.DISCONNECTED
        android.util.Log.d(TAG, "Disconnected from MCP Server: ${config.name}")
    }
    
    /**
     * 发送请求（通用方法）
     */
    suspend fun sendRequest(
        method: String,
        params: JSONObject = JSONObject()
    ): Result<Any?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            when (config.transport) {
                MCPTransport.WEBSOCKET -> sendWebSocketRequest(method, params)
                MCPTransport.HTTP -> sendHTTPRequest(method, params)
                MCPTransport.SSE -> sendSSERequest(method, params)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to send request: $method", e)
            Result.failure(e)
        }
    }
    
    /**
     * 发送 WebSocket 请求
     */
    private suspend fun sendWebSocketRequest(
        method: String,
        params: JSONObject
    ): Result<Any?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = requestId.incrementAndGet()
        val request = MCPMessage.Request(
            id = id,
            method = method,
            params = params
        )
        
        return@withContext try {
            val completable = kotlinx.coroutines.CompletableDeferred<MCPMessage.Response>()
            
            pendingRequests[id] = PendingRequest(
                onResponse = { completable.complete(it) },
                onError = { completable.completeExceptionally(it) }
            )
            
            webSocket?.send(request.toJson().toString())
                ?: throw Exception("WebSocket not connected")
            
            val response = completable.await()
            
            if (response.isError) {
                Result.failure(Exception(response.error?.toString() ?: "Unknown error"))
            } else {
                Result.success(response.result)
            }
        } catch (e: Exception) {
            pendingRequests.remove(id)
            Result.failure(e)
        }
    }
    
    /**
     * 发送 HTTP 请求
     */
    private suspend fun sendHTTPRequest(
        method: String,
        params: JSONObject
    ): Result<Any?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = requestId.incrementAndGet()
        val request = MCPMessage.Request(
            id = id,
            method = method,
            params = params
        )
        
        val requestBody = request.toJson().toString()
        android.util.Log.d(TAG, "Sending $method request: $requestBody")
        
        val requestBodyTyped = requestBody.toRequestBody("application/json".toMediaType())
        
        val requestBuilder = Request.Builder()
            .url(config.url)
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
                // 添加 Accept header（MCP Server 要求同时接受 json 和 event-stream）
                addHeader("Accept", "application/json, text/event-stream")
                // 添加 mcp-session-id header（如果是初始化请求，则不添加）
                if (method != MCPMethod.INITIALIZE && sessionId != null) {
                    addHeader("mcp-session-id", sessionId!!)
                }
            }
            .post(requestBodyTyped)
        
        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()
            
             try {
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.code} - $responseBody")
                    )
                }
                
                // 从响应头中提取 mcp-session-id（初始化响应）
                val responseSessionId = response.header("mcp-session-id")
                if (responseSessionId != null) {
                    sessionId = responseSessionId
                    android.util.Log.d(TAG, "Received mcp-session-id: $responseSessionId")
                }
                
                val json = JSONObject(responseBody ?: "")
                val mcpResponse = MCPMessage.Response.fromJson(json)
                
                if (mcpResponse.isError) {
                    Result.failure(Exception(mcpResponse.error?.toString() ?: "Unknown error"))
                } else {
                    Result.success(mcpResponse.result)
                }
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 发送 SSE 请求（Server-Sent Events）
     * SSE 使用 HTTP POST 请求，响应是事件流格式
     * 如果 POST 返回 405，则回退到 GET 方法
     */
    private suspend fun sendSSERequest(
        method: String,
        params: JSONObject
    ): Result<Any?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = requestId.incrementAndGet()
        val request = MCPMessage.Request(
            id = id,
            method = method,
            params = params
        )
        
        val requestBody = request.toJson().toString()
        android.util.Log.d(TAG, "Sending SSE $method request: $requestBody")
        
        // 先尝试 POST 请求
        val postResult = trySendSSERequest("POST", requestBody)
        
        // 如果 POST 返回 405，则尝试 GET 请求
        if (postResult.isFailure) {
            val exception = postResult.exceptionOrNull()
            if (exception?.message?.contains("405") == true) {
                android.util.Log.d(TAG, "POST returned 405, trying GET method")
                return@withContext trySendSSERequest("GET", requestBody)
            }
        }
        
        postResult
    }
    
    /**
     * 尝试发送 SSE 请求（内部方法，支持指定 HTTP 方法）
     */
    private suspend fun trySendSSERequest(
        httpMethod: String,
        requestBody: String
    ): Result<Any?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(config.url)
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
                // SSE 需要 Accept: text/event-stream
                addHeader("Accept", "text/event-stream")
                
                // POST 请求需要 Content-Type 和请求体
                if (httpMethod == "POST") {
                    addHeader("Content-Type", "application/json")
                    val requestBodyTyped = requestBody.toRequestBody("application/json".toMediaType())
                    post(requestBodyTyped)
                } else {
                    // GET 请求不需要请求体，将参数放在 URL 查询字符串中
                    get()
                }
            }
        
        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            
            try {
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string()
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.code} - $responseBody")
                    )
                }
                
                // 从响应头中提取 mcp-session-id（初始化响应）
                val responseSessionId = response.header("mcp-session-id")
                if (responseSessionId != null) {
                    sessionId = responseSessionId
                    android.util.Log.d(TAG, "Received mcp-session-id: $responseSessionId")
                }
                
                // SSE 响应是事件流，需要解析
                val responseBody = response.body?.string() ?: ""
                
                // SSE 格式: data: {json}\n\n
                val events = parseSSEEvents(responseBody)
                
                if (events.isEmpty()) {
                    return@withContext Result.failure(Exception("No SSE events received"))
                }
                
                // 取最后一个事件作为结果
                val lastEvent = events.last()
                val json = JSONObject(lastEvent)
                val mcpResponse = MCPMessage.Response.fromJson(json)
                
                if (mcpResponse.isError) {
                    Result.failure(Exception(mcpResponse.error?.toString() ?: "Unknown error"))
                } else {
                    Result.success(mcpResponse.result)
                }
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 解析 SSE 事件流
     * SSE 格式: data: {json}\n\n
     */
    private fun parseSSEEvents(sseResponse: String): List<String> {
        val events = mutableListOf<String>()
        val lines = sseResponse.split("\n")
        var currentData = StringBuilder()
        
        for (line in lines) {
            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()
                if (data.isNotEmpty()) {
                    currentData.append(data)
                }
            } else if (line.isEmpty() && currentData.isNotEmpty()) {
                // 空行表示事件结束
                events.add(currentData.toString())
                currentData.clear()
            }
        }
        
        // 处理最后一个事件（如果没有以空行结尾）
        if (currentData.isNotEmpty()) {
            events.add(currentData.toString())
        }
        
        return events
    }
    
    /**
     * 获取工具列表
     */
    suspend fun listTools(): Result<List<MCPToolDefinition>> {
        return try {
            val result = sendRequest(MCPMethod.TOOLS_LIST)
            
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
            }
            
            val data = result.getOrNull()
            if (data !is JSONObject) {
                return Result.failure(Exception("Invalid response format"))
            }
            
            val toolsArray = data.optJSONArray("tools")
            if (toolsArray == null) {
                return Result.success(emptyList())
            }
            
            val tools = mutableListOf<MCPToolDefinition>()
            for (i in 0 until toolsArray.length()) {
                val toolJson = toolsArray.getJSONObject(i)
                val tool = MCPToolDefinition(
                    name = toolJson.getString("name"),
                    description = toolJson.getString("description"),
                    inputSchema = toolJson.getJSONObject("inputSchema"),
                    serverId = config.id
                )
                tools.add(tool)
            }
            
            android.util.Log.d(TAG, "Listed ${tools.size} tools from ${config.name}")
            Result.success(tools)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to list tools", e)
            Result.failure(e)
        }
    }
    
    /**
     * 调用工具
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>
    ): Result<Any?> {
        return try {
            val params = JSONObject().apply {
                put("name", name)
                put("arguments", JSONObject(arguments))
            }
            
            val result = sendRequest(MCPMethod.TOOLS_CALL, params)
            
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
            }
            
            android.util.Log.d(TAG, "Called tool '$name' from ${config.name}")
            result
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to call tool: $name", e)
            Result.failure(e)
        }
    }

    /**
     * 发送初始化完成通知
     * MCP 协议要求：initialize 成功后必须发送 notifications/initialized 通知
     */
    private suspend fun sendInitializedNotification(): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val notification = MCPMessage.Notification(
                method = MCPMethod.NOTIFICATIONS_INITIALIZED,
                params = JSONObject()
            )
            
            val requestBody = notification.toJson().toString()
            android.util.Log.d(TAG, "Sending initialized notification: $requestBody")
            
            val requestBodyTyped = requestBody.toRequestBody("application/json".toMediaType())
            
            val requestBuilder = Request.Builder()
                .url(config.url)
                .apply {
                    config.getRequestHeaders().forEach { (key, value) ->
                        addHeader(key, value)
                    }
                    addHeader("Accept", "application/json, text/event-stream")
                    // 通知也需要携带 session ID
                    if (sessionId != null) {
                        addHeader("mcp-session-id", sessionId!!)
                    }
                }
                .post(requestBodyTyped)
            
            val response = httpClient.newCall(requestBuilder.build()).execute()
            
            try {
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.code} - ${response.body?.string()}")
                    )
                }
                
                android.util.Log.d(TAG, "Sent initialized notification successfully")
                Result.success(Unit)
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to send initialized notification", e)
            Result.failure(e)
        }
    }
    
    /**
     * 初始化 MCP 连接
     */
    suspend fun initialize(): Result<Unit> {
        return try {
            val params = JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject().apply {
                    put("tools", JSONObject())
                })
                put("clientInfo", JSONObject().apply {
                    put("name", "Alian Assistant")
                    put("version", "1.0.0")
                })
            }
            
            val result = sendRequest(MCPMethod.INITIALIZE, params)
            
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
            }
            
            // MCP 协议要求：initialize 成功后必须发送 notifications/initialized 通知
            val notifyResult = sendInitializedNotification()
            if (notifyResult.isFailure) {
                android.util.Log.w(TAG, "Failed to send initialized notification, but continuing...", notifyResult.exceptionOrNull())
            }
            // 标记初始化完成
            isInitialized = true
            android.util.Log.d(TAG, "Initialized MCP connection: ${config.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to initialize MCP connection", e)
            Result.failure(e)
        }
    }
}