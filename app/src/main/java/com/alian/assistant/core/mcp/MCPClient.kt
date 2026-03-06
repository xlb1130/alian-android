package com.alian.assistant.core.mcp

import com.alian.assistant.core.mcp.models.MCPMessage
import com.alian.assistant.core.mcp.models.MCPMethod
import com.alian.assistant.core.mcp.models.MCPServerConfig
import com.alian.assistant.core.mcp.models.MCPToolDefinition
import com.alian.assistant.core.mcp.models.MCPTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 客户端
 * 支持 WebSocket、Streamable HTTP、SSE 三种传输协议
 */
class MCPClient(private val config: MCPServerConfig) {

    companion object {
        private const val TAG = "MCPClient"
        private const val CONNECT_TIMEOUT = 30_000L
        private const val READ_TIMEOUT = 60_000L
        private const val WRITE_TIMEOUT = 30_000L
        private const val RESPONSE_TIMEOUT = 60_000L
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

    // SSE 工厂（okhttp-sse）
    private val sseFactory = EventSources.createFactory(httpClient)

    // WebSocket 连接
    private var webSocket: WebSocket? = null

    // SSE 连接
    private var sseEventSource: EventSource? = null

    // 请求 ID 计数器
    private val requestId = AtomicInteger(0)

    // 待处理的请求（用于 WebSocket/SSE）
    private val pendingRequests = ConcurrentHashMap<Int, PendingRequest>()

    // MCP Session ID（用于 HTTP/SSE 传输）
    private var sessionId: String? = null

    // 是否主动断开连接
    @Volatile
    private var isDisconnecting = false

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
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING

            val connectResult = when (config.transport) {
                MCPTransport.WEBSOCKET -> connectWebSocket()
                MCPTransport.STREAMABLE_HTTP -> connectHTTP()
                MCPTransport.SSE -> connectSSE()
            }

            if (connectResult.isFailure) {
                throw connectResult.exceptionOrNull() ?: Exception("Failed to connect")
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
    private suspend fun connectWebSocket(): Result<Unit> = withContext(Dispatchers.IO) {
        val openSignal = CompletableDeferred<Unit>()

        val request = Request.Builder()
            .url(config.url)
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                android.util.Log.d(TAG, "WebSocket opened: ${config.url}")
                _connectionState.value = ConnectionState.CONNECTED
                if (!openSignal.isCompleted) {
                    openSignal.complete(Unit)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleJsonRpcPayload(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                android.util.Log.d(TAG, "WebSocket closing: $code - $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!isDisconnecting) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                android.util.Log.d(TAG, "WebSocket closed: $code - $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val error = Exception(t.message ?: "WebSocket connection failed", t)
                android.util.Log.e(TAG, "WebSocket failure", t)
                if (!openSignal.isCompleted) {
                    openSignal.completeExceptionally(error)
                }
                _connectionState.value = ConnectionState.ERROR
                failPendingRequests(error)
            }
        }

        return@withContext try {
            isDisconnecting = false
            webSocket = wsClient.newWebSocket(request, listener)
            withTimeout(CONNECT_TIMEOUT) { openSignal.await() }
            Result.success(Unit)
        } catch (e: Exception) {
            webSocket?.close(1000, "connect failed")
            webSocket = null
            Result.failure(e)
        }
    }

    /**
     * Streamable HTTP 连接（不需要保持长连接）
     */
    private fun connectHTTP(): Result<Unit> {
        return Result.success(Unit)
    }

    /**
     * SSE 连接（Server-Sent Events）
     * 建立长期事件流通道，用于接收异步 JSON-RPC 响应
     */
    private suspend fun connectSSE(): Result<Unit> = withContext(Dispatchers.IO) {
        val openSignal = CompletableDeferred<Unit>()

        sseEventSource?.cancel()
        sseEventSource = null

        val request = Request.Builder()
            .url(config.url)
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
                addHeader("Accept", "text/event-stream")
                addHeader("Cache-Control", "no-cache")
                sessionId?.let { addHeader("mcp-session-id", it) }
            }
            .get()
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                android.util.Log.d(TAG, "SSE opened: ${config.url}")
                _connectionState.value = ConnectionState.CONNECTED
                if (!openSignal.isCompleted) {
                    openSignal.complete(Unit)
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                handleSSEPayload(data)
            }

            override fun onClosed(eventSource: EventSource) {
                if (!isDisconnecting) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                android.util.Log.d(TAG, "SSE closed: ${config.url}")
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val message = buildString {
                    append(t?.message ?: "SSE connection failed")
                    if (response != null) {
                        append(" (HTTP ")
                        append(response.code)
                        append(")")
                    }
                }
                val error = Exception(message, t)
                android.util.Log.e(TAG, "SSE failure", error)
                if (!openSignal.isCompleted) {
                    openSignal.completeExceptionally(error)
                }
                if (!isDisconnecting) {
                    _connectionState.value = ConnectionState.ERROR
                }
                failPendingRequests(error)
            }
        }

        return@withContext try {
            isDisconnecting = false
            sseEventSource = sseFactory.newEventSource(request, listener)
            withTimeout(CONNECT_TIMEOUT) { openSignal.await() }
            Result.success(Unit)
        } catch (e: Exception) {
            sseEventSource?.cancel()
            sseEventSource = null
            Result.failure(e)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isDisconnecting = true
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        sseEventSource?.cancel()
        sseEventSource = null
        failPendingRequests(Exception("Connection closed"))
        sessionId = null // 清除 session ID
        isInitialized = false // 重置初始化状态
        _connectionState.value = ConnectionState.DISCONNECTED
        android.util.Log.d(TAG, "Disconnected from MCP Server: ${config.name}")
    }

    /**
     * 发送请求（通用方法）
     */
    suspend fun sendRequest(
        method: String,
        params: JSONObject = JSONObject()
    ): Result<Any?> = withContext(Dispatchers.IO) {
        try {
            when (config.transport) {
                MCPTransport.WEBSOCKET -> sendWebSocketRequest(method, params)
                MCPTransport.STREAMABLE_HTTP -> sendHTTPRequest(method, params)
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
    ): Result<Any?> = withContext(Dispatchers.IO) {
        val id = requestId.incrementAndGet()
        val request = MCPMessage.Request(
            id = id,
            method = method,
            params = params
        )

        return@withContext try {
            val completable = CompletableDeferred<MCPMessage.Response>()

            pendingRequests[id] = PendingRequest(
                onResponse = { completable.complete(it) },
                onError = { completable.completeExceptionally(it) }
            )

            val sent = webSocket?.send(request.toJson().toString()) ?: false
            if (!sent) {
                pendingRequests.remove(id)
                throw Exception("WebSocket not connected")
            }

            val response = withTimeout(RESPONSE_TIMEOUT) { completable.await() }
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
     * 发送 Streamable HTTP 请求
     */
    private suspend fun sendHTTPRequest(
        method: String,
        params: JSONObject
    ): Result<Any?> = withContext(Dispatchers.IO) {
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
                // MCP Server 要求同时接受 json 和 event-stream
                addHeader("Accept", "application/json, text/event-stream")
                // 添加 mcp-session-id header（如果是初始化请求，则不添加）
                if (method != MCPMethod.INITIALIZE && sessionId != null) {
                    addHeader("mcp-session-id", sessionId!!)
                }
            }
            .post(requestBodyTyped)

        return@withContext try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            try {
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.code} - ${response.body?.string()}")
                    )
                }

                updateSessionIdFromHeaders(response)

                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) {
                    return@withContext Result.success(null)
                }

                val json = JSONObject(responseBody)
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
     * 发送 SSE 请求
     * 发送走 HTTP POST，响应优先从 SSE 事件流按 id 回传。
     */
    private suspend fun sendSSERequest(
        method: String,
        params: JSONObject
    ): Result<Any?> = withContext(Dispatchers.IO) {
        // 确保 SSE 事件流已连接
        if (sseEventSource == null || _connectionState.value != ConnectionState.CONNECTED) {
            val connectResult = connectSSE()
            if (connectResult.isFailure) {
                return@withContext Result.failure(
                    connectResult.exceptionOrNull() ?: Exception("Failed to connect SSE stream")
                )
            }
            _connectionState.value = ConnectionState.CONNECTED
        }

        val id = requestId.incrementAndGet()
        val request = MCPMessage.Request(
            id = id,
            method = method,
            params = params
        )
        val requestBody = request.toJson().toString()
        android.util.Log.d(TAG, "Sending SSE $method request: $requestBody")

        val completable = CompletableDeferred<MCPMessage.Response>()
        pendingRequests[id] = PendingRequest(
            onResponse = { completable.complete(it) },
            onError = { completable.completeExceptionally(it) }
        )

        val requestBuilder = Request.Builder()
            .url(config.url)
            .apply {
                config.getRequestHeaders().forEach { (key, value) ->
                    addHeader(key, value)
                }
                addHeader("Accept", "application/json, text/event-stream")
                addHeader("Content-Type", "application/json")
                if (method != MCPMethod.INITIALIZE && sessionId != null) {
                    addHeader("mcp-session-id", sessionId!!)
                }
            }
            .post(requestBody.toRequestBody("application/json".toMediaType()))

        return@withContext try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            try {
                if (!response.isSuccessful) {
                    pendingRequests.remove(id)
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.code} - ${response.body?.string()}")
                    )
                }

                val previousSessionId = sessionId
                updateSessionIdFromHeaders(response)
                if (method == MCPMethod.INITIALIZE && sessionId != null && sessionId != previousSessionId) {
                    // initialize 后如果拿到了新的 session id，重建 SSE 连接让后续事件带上会话上下文
                    val reconnectResult = connectSSE()
                    if (reconnectResult.isFailure) {
                        android.util.Log.w(
                            TAG,
                            "Failed to reconnect SSE with session id: ${reconnectResult.exceptionOrNull()?.message}"
                        )
                    }
                }

                // 兼容部分服务端：POST 直接返回 JSON 或 event-stream
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isNotBlank()) {
                    if (responseBody.trimStart().startsWith("{")) {
                        handleJsonRpcPayload(responseBody)
                    } else {
                        parseSSEEvents(responseBody).forEach { payload ->
                            handleSSEPayload(payload)
                        }
                    }
                }
            } finally {
                response.close()
            }

            val mcpResponse = withTimeout(RESPONSE_TIMEOUT) { completable.await() }
            if (mcpResponse.isError) {
                Result.failure(Exception(mcpResponse.error?.toString() ?: "Unknown error"))
            } else {
                Result.success(mcpResponse.result)
            }
        } catch (e: Exception) {
            pendingRequests.remove(id)
            Result.failure(e)
        }
    }

    private fun handleSSEPayload(payload: String) {
        val trimmed = payload.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") {
            return
        }
        handleJsonRpcPayload(trimmed)
    }

    private fun handleJsonRpcPayload(payload: String) {
        try {
            val json = JSONObject(payload)
            val id = json.optInt("id", -1)
            if (id == -1) {
                // 通知类消息无 id，当前客户端无需处理
                return
            }

            val response = MCPMessage.Response.fromJson(json)
            val pending = pendingRequests.remove(id) ?: return
            if (response.isError) {
                pending.onError(Exception(response.error?.toString() ?: "Unknown error"))
            } else {
                pending.onResponse(response)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse JSON-RPC payload: $payload", e)
        }
    }

    private fun failPendingRequests(exception: Exception) {
        pendingRequests.values.forEach { pending ->
            pending.onError(exception)
        }
        pendingRequests.clear()
    }

    private fun updateSessionIdFromHeaders(response: Response) {
        val responseSessionId = response.header("mcp-session-id")
        if (!responseSessionId.isNullOrBlank()) {
            sessionId = responseSessionId
            android.util.Log.d(TAG, "Received mcp-session-id: $responseSessionId")
        }
    }

    /**
     * 解析 SSE 事件流文本
     * SSE 格式:
     * event: message
     * data: {...}
     *
     */
    private fun parseSSEEvents(sseResponse: String): List<String> {
        val events = mutableListOf<String>()
        val lines = sseResponse.split("\n")
        val dataBuffer = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("data:") -> {
                    dataBuffer.append(line.substringAfter("data:").trimStart())
                    dataBuffer.append('\n')
                }

                line.isBlank() -> {
                    if (dataBuffer.isNotEmpty()) {
                        events.add(dataBuffer.toString().trimEnd())
                        dataBuffer.clear()
                    }
                }
            }
        }

        if (dataBuffer.isNotEmpty()) {
            events.add(dataBuffer.toString().trimEnd())
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
    private suspend fun sendInitializedNotification(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val notification = MCPMessage.Notification(
                method = MCPMethod.NOTIFICATIONS_INITIALIZED,
                params = JSONObject()
            )
            val requestBody = notification.toJson().toString()
            android.util.Log.d(TAG, "Sending initialized notification: $requestBody")

            when (config.transport) {
                MCPTransport.WEBSOCKET -> {
                    val sent = webSocket?.send(requestBody) ?: false
                    if (!sent) {
                        return@withContext Result.failure(Exception("WebSocket not connected"))
                    }
                    Result.success(Unit)
                }

                MCPTransport.STREAMABLE_HTTP,
                MCPTransport.SSE -> {
                    if (config.transport == MCPTransport.SSE && (sseEventSource == null || _connectionState.value != ConnectionState.CONNECTED)) {
                        val connectResult = connectSSE()
                        if (connectResult.isFailure) {
                            return@withContext Result.failure(
                                connectResult.exceptionOrNull() ?: Exception("Failed to connect SSE stream")
                            )
                        }
                    }

                    val requestBuilder = Request.Builder()
                        .url(config.url)
                        .apply {
                            config.getRequestHeaders().forEach { (key, value) ->
                                addHeader(key, value)
                            }
                            addHeader("Accept", "application/json, text/event-stream")
                            if (sessionId != null) {
                                addHeader("mcp-session-id", sessionId!!)
                            }
                        }
                        .post(requestBody.toRequestBody("application/json".toMediaType()))

                    val response = httpClient.newCall(requestBuilder.build()).execute()
                    try {
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(
                                Exception("HTTP error: ${response.code} - ${response.body?.string()}")
                            )
                        }
                        updateSessionIdFromHeaders(response)
                        Result.success(Unit)
                    } finally {
                        response.close()
                    }
                }
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
                android.util.Log.w(
                    TAG,
                    "Failed to send initialized notification, but continuing...",
                    notifyResult.exceptionOrNull()
                )
            }

            isInitialized = true
            android.util.Log.d(TAG, "Initialized MCP connection: ${config.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to initialize MCP connection", e)
            Result.failure(e)
        }
    }
}
