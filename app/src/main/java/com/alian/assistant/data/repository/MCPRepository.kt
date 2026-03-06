package com.alian.assistant.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.alian.assistant.core.mcp.models.MCPServerConfig
import com.alian.assistant.core.mcp.models.MCPTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * MCP 配置持久化存储
 * 使用 EncryptedSharedPreferences 加密存储敏感信息（API Key）
 */
class MCPRepository(context: Context) {
    
    // 普通存储（用于非敏感数据）
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("mcp_settings", Context.MODE_PRIVATE)
    
    // 加密存储（用于敏感数据如 API Key）
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                "mcp_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("MCPRepository", "Failed to create encrypted prefs", e)
            prefs
        }
    }
    
    private val _serversFlow = MutableStateFlow<List<MCPServerConfig>>(emptyList())
    val serversFlow: Flow<List<MCPServerConfig>> = _serversFlow
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    init {
        // 初始化时加载配置
        loadServers()
        
        // 添加默认的 12306-mcp Server（如果不存在）
        addDefaultServerIfNeeded()
    }
    
    /**
     * 添加默认的 MCP Servers（如果不存在）
     */
    private fun addDefaultServerIfNeeded() {
        val serverIds = prefs.getStringSet("mcp_server_ids", emptySet()) ?: emptySet()
        
        // 检查是否已存在特定名称的 Server
        fun hasServerWithName(name: String): Boolean {
            return serverIds.any { serverId ->
                val configJson = prefs.getString("mcp_server_$serverId", null)
                configJson?.let { jsonStr ->
                    try {
                        val configObj = JSONObject(jsonStr)
                        configObj.getString("name") == name
                    } catch (e: Exception) {
                        false
                    }
                } ?: false
            }
        }
        
        // 添加 12306-mcp Server
        if (!hasServerWithName("12306-mcp")) {
            android.util.Log.d("MCPRepository", "添加默认的 12306-mcp Server")
            
            val defaultServerJson = """{
                                          "mcpServers": {
                                            "12306-mcp": {
                                              "type": "streamable_http",
                                              "url": "https://mcp.api-inference.modelscope.net/替换成你的/mcp"
                                            }
                                          }
                                        }"""
            
            try {
                val config = MCPServerConfig.fromJson(defaultServerJson)
                if (config.isSuccess) {
                    val serverConfig = config.getOrNull()!!
                    saveServer(serverConfig.copy(enabled = false))
                    android.util.Log.d("MCPRepository", "默认 12306-mcp Server 已添加")
                }
            } catch (e: Exception) {
                android.util.Log.e("MCPRepository", "添加默认 Server 失败", e)
            }
        }
        
        // 添加 streamableHTTP Server
        if (!hasServerWithName("amap-maps-streamableHTTP")) {
            android.util.Log.d("MCPRepository", "添加默认的 streamableHTTP Server")
            
            val amapServerJson = """{
                                      "mcpServers": {
                                        "amap-maps-streamableHTTP": {
                                        "type": "streamable_http",
                                          "url": "https://mcp.amap.com/mcp?key=替换成你的"
                                        }
                                      }
                                    }"""
            
            try {
                val config = MCPServerConfig.fromJson(amapServerJson)
                if (config.isSuccess) {
                    val serverConfig = config.getOrNull()!!
                    saveServer(serverConfig.copy(enabled = false))
                    android.util.Log.d("MCPRepository", "默认 amap_server_sse Server 已添加")
                }
            } catch (e: Exception) {
                android.util.Log.e("MCPRepository", "添加 amap_server_sse Server 失败", e)
            }
        }
        
        // 添加 bing_cn_server Server
        if (!hasServerWithName("bing-cn-mcp-server")) {
            android.util.Log.d("MCPRepository", "添加默认的 bing_cn_server Server")
            
            val bingServerJson = """{
                                      "mcpServers": {
                                        "bing-cn-mcp-server": {
                                          "type": "streamable_http",
                                          "url": "https://mcp.api-inference.modelscope.net/替换成你的/mcp"
                                        }
                                      }
                                    }"""
            
            try {
                val config = MCPServerConfig.fromJson(bingServerJson)
                if (config.isSuccess) {
                    val serverConfig = config.getOrNull()!!
                    saveServer(serverConfig.copy(enabled = false))
                    android.util.Log.d("MCPRepository", "默认 bing_cn_server Server 已添加")
                }
            } catch (e: Exception) {
                android.util.Log.e("MCPRepository", "添加 bing_cn_server Server 失败", e)
            }
        }
    }
    
    /**
     * 加载所有 MCP Server 配置
     */
    private fun loadServers() {
        val serverIds = prefs.getStringSet("mcp_server_ids", emptySet()) ?: emptySet()
        val servers = serverIds.mapNotNull { serverId ->
            loadServer(serverId)
        }
        _serversFlow.value = servers
    }
    
    /**
     * 加载单个 MCP Server 配置
     */
    private fun loadServer(serverId: String): MCPServerConfig? {
        return try {
            val configJson = prefs.getString("mcp_server_${serverId}", null) ?: return null
            val configObj = JSONObject(configJson)
            
            // 读取加密的 API Key
            val apiKey = securePrefs.getString("mcp_server_${serverId}_api_key", null)
            
            return MCPServerConfig(
                id = configObj.getString("id"),
                name = configObj.getString("name"),
                description = configObj.optString("description", null),
                url = configObj.getString("url"),
                transport = MCPServerConfig.fromJson(configJson).getOrNull()?.transport
                    ?: MCPTransport.STREAMABLE_HTTP,
                enabled = configObj.getBoolean("enabled"),
                apiKey = apiKey,
                headers = configObj.optJSONObject("headers")?.let { headersObj ->
                    val headers = mutableMapOf<String, String>()
                    headersObj.keys().forEach { key ->
                        headers[key] = headersObj.getString(key)
                    }
                    headers.takeIf { it.isNotEmpty() }
                },
                createdAt = configObj.getLong("created_at"),
                updatedAt = configObj.getLong("updated_at")
            )
        } catch (e: Exception) {
            android.util.Log.e("MCPRepository", "Failed to load server: $serverId", e)
            null
        }
    }
    
    /**
     * 保存 MCP Server 配置
     */
    fun saveServer(config: MCPServerConfig) {
        try {
            // 保存配置到普通存储
            val configJson = JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("description", config.description)
                put("url", config.url)
                // 同时保存 transport 和 type，兼容应用内字段与标准 MCP 配置字段
                put("transport", config.transport.wireValue)
                put("type", config.transport.wireValue)
                put("enabled", config.enabled)
                put("created_at", config.createdAt)
                put("updated_at", config.updatedAt)
                
                // 保存 headers
                config.headers?.let { headers ->
                    val headersObj = JSONObject()
                    headers.forEach { (key, value) ->
                        headersObj.put(key, value)
                    }
                    put("headers", headersObj)
                }
            }
            
            prefs.edit()
                .putString("mcp_server_${config.id}", configJson.toString())
                .apply()
            
            // 保存敏感的 API Key 到加密存储
            config.apiKey?.let { apiKey ->
                securePrefs.edit()
                    .putString("mcp_server_${config.id}_api_key", apiKey)
                    .apply()
            }
            
            // 更新服务器 ID 列表
            val serverIds = prefs.getStringSet("mcp_server_ids", emptySet())?.toMutableSet() 
                ?: mutableSetOf()
            serverIds.add(config.id)
            prefs.edit().putStringSet("mcp_server_ids", serverIds).apply()
            
            // 重新加载并更新 Flow
            loadServers()
            
            android.util.Log.d("MCPRepository", "Saved server: ${config.name}")
        } catch (e: Exception) {
            android.util.Log.e("MCPRepository", "Failed to save server", e)
            throw e
        }
    }
    
    /**
     * 更新 MCP Server 配置
     */
    fun updateServer(config: MCPServerConfig) {
        // 更新 updated_at 时间戳
        val updatedConfig = config.copy(updatedAt = System.currentTimeMillis())
        saveServer(updatedConfig)
    }
    
    /**
     * 删除 MCP Server 配置
     */
    fun deleteServer(serverId: String) {
        try {
            // 删除配置
            prefs.edit().remove("mcp_server_$serverId").apply()
            
            // 删除加密的 API Key
            securePrefs.edit().remove("mcp_server_${serverId}_api_key").apply()
            
            // 从服务器 ID 列表中移除
            val serverIds = prefs.getStringSet("mcp_server_ids", emptySet())?.toMutableSet() 
                ?: mutableSetOf()
            serverIds.remove(serverId)
            prefs.edit().putStringSet("mcp_server_ids", serverIds).apply()
            
            // 重新加载并更新 Flow
            loadServers()
            
            android.util.Log.d("MCPRepository", "Deleted server: $serverId")
        } catch (e: Exception) {
            android.util.Log.e("MCPRepository", "Failed to delete server", e)
            throw e
        }
    }
    
    /**
     * 获取所有 MCP Server 配置
     */
    fun getAllServers(): List<MCPServerConfig> {
        return _serversFlow.value
    }
    
    /**
     * 获取已启用的 MCP Server 配置
     */
    fun getEnabledServers(): List<MCPServerConfig> {
        return _serversFlow.value.filter { it.enabled }
    }
    
    /**
     * 根据 ID 获取 MCP Server 配置
     */
    fun getServerById(serverId: String): MCPServerConfig? {
        return _serversFlow.value.find { it.id == serverId }
    }
    
    /**
     * 清空所有 MCP Server 配置
     */
    fun clearAllServers() {
        try {
            val serverIds = prefs.getStringSet("mcp_server_ids", emptySet()) ?: emptySet()
            
            serverIds.forEach { serverId ->
                prefs.edit().remove("mcp_server_$serverId").apply()
                securePrefs.edit().remove("mcp_server_${serverId}_api_key").apply()
            }
            
            prefs.edit().remove("mcp_server_ids").apply()
            
            _serversFlow.value = emptyList()
            
            android.util.Log.d("MCPRepository", "Cleared all servers")
        } catch (e: Exception) {
            android.util.Log.e("MCPRepository", "Failed to clear all servers", e)
            throw e
        }
    }
}
