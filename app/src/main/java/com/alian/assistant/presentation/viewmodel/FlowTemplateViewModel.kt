package com.alian.assistant.presentation.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alian.assistant.core.flow.model.*
import com.alian.assistant.data.repository.FlowTemplateRepository
import com.alian.assistant.infrastructure.flow.storage.FlowTemplateStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 流程模板管理 ViewModel
 * 
 * 职责：
 * - 管理模板列表状态
 * - 处理模板的增删改查
 * - 支持搜索、筛选、排序
 */
class FlowTemplateViewModel(application: Application) : AndroidViewModel(application) {

    // Repository
    private val repository: FlowTemplateRepository by lazy {
        FlowTemplateRepository(FlowTemplateStorage(application, Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            isLenient = true
        }))
    }

    // UI 状态
    private val _uiState = MutableStateFlow(FlowTemplateListUiState())
    val uiState: StateFlow<FlowTemplateListUiState> = _uiState.asStateFlow()

    // 原始模板映射（用于保存完整的 FlowTemplate 对象）
    private val templateMap = mutableMapOf<String, FlowTemplate>()

    // 详情页状态
    private val _detailUiState = MutableStateFlow(FlowTemplateDetailUiState())
    val detailUiState: StateFlow<FlowTemplateDetailUiState> = _detailUiState.asStateFlow()

    // 搜索状态
    var searchQuery by mutableStateOf("")
        private set

    // 筛选状态
    var selectedCategory by mutableStateOf<FlowCategory?>(null)
        private set

    var selectedTrustLevel by mutableStateOf<TrustLevel?>(null)
        private set

    // 排序状态
    var sortBy by mutableStateOf(SortBy.RECENTLY_USED)
        private set

    // 加载任务
    private var loadJob: Job? = null

    init {
        loadTemplates()
    }

    /**
     * 加载模板列表
     */
    fun loadTemplates() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                repository.refreshCache()
                
                // 观察 Repository 的模板流
                repository.templatesFlow.collect { templates ->
                    // 更新模板映射
                    templateMap.clear()
                    templates.forEach { template ->
                        templateMap[template.id] = template
                    }
                    
                    val filteredTemplates = filterAndSortTemplates(templates)
                    _uiState.value = _uiState.value.copy(
                        templates = templates.map { it.toUiItem() },
                        filteredTemplates = filteredTemplates.map { it.toUiItem() },
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载模板失败"
                )
            }
        }
    }

    /**
     * 加载模板详情
     */
    fun loadTemplateDetail(templateId: String) {
        viewModelScope.launch {
            _detailUiState.value = _detailUiState.value.copy(isLoading = true, error = null)
            
            try {
                val template = repository.getById(templateId)
                _detailUiState.value = _detailUiState.value.copy(
                    template = template,
                    isLoading = false
                )
            } catch (e: Exception) {
                _detailUiState.value = _detailUiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载模板详情失败"
                )
            }
        }
    }

    /**
     * 搜索模板
     */
    fun search(query: String) {
        searchQuery = query
        applyFilters()
    }

    /**
     * 选择分类筛选
     */
    fun selectCategory(category: FlowCategory?) {
        selectedCategory = category
        applyFilters()
    }

    /**
     * 选择可信度筛选
     */
    fun selectTrustLevel(trustLevel: TrustLevel?) {
        selectedTrustLevel = trustLevel
        applyFilters()
    }

    /**
     * 设置排序方式
     */
    fun updateSortBy(sort: SortBy) {
        sortBy = sort
        applyFilters()
    }

    /**
     * 删除模板
     */
    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            try {
                repository.delete(templateId)
                _detailUiState.value = _detailUiState.value.copy(
                    showDeleteConfirm = false,
                    template = null
                )
            } catch (e: Exception) {
                _detailUiState.value = _detailUiState.value.copy(
                    error = e.message ?: "删除模板失败"
                )
            }
        }
    }

    /**
     * 导出模板
     */
    fun exportTemplate(templateId: String, callback: (String?) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportTemplate(templateId)
            _detailUiState.value = _detailUiState.value.copy(
                showExportSuccess = json != null
            )
            callback(json)
        }
    }

    /**
     * 导入模板
     */
    fun importTemplate(jsonString: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.importTemplate(jsonString)
            callback(result.isSuccess)
        }
    }

    /**
     * 显示删除确认对话框
     */
    fun showDeleteConfirm() {
        _detailUiState.value = _detailUiState.value.copy(showDeleteConfirm = true)
    }

    /**
     * 隐藏删除确认对话框
     */
    fun hideDeleteConfirm() {
        _detailUiState.value = _detailUiState.value.copy(showDeleteConfirm = false)
    }

    /**
     * 重置导出成功状态
     */
    fun resetExportSuccess() {
        _detailUiState.value = _detailUiState.value.copy(showExportSuccess = false)
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
        _detailUiState.value = _detailUiState.value.copy(error = null)
    }

    // ========== 私有方法 ==========

    /**
     * 应用筛选和排序
     */
    private fun applyFilters() {
        val allTemplates = templateMap.values.toList()
        val filtered = filterAndSortTemplates(allTemplates)
        _uiState.value = _uiState.value.copy(
            filteredTemplates = filtered.map { it.toUiItem() }
        )
    }

    /**
     * 筛选和排序模板
     */
    private fun filterAndSortTemplates(templates: List<FlowTemplate>): List<FlowTemplate> {
        var result = templates

        // 搜索筛选
        if (searchQuery.isNotEmpty()) {
            result = result.filter { template ->
                template.name.contains(searchQuery, ignoreCase = true) ||
                template.description.contains(searchQuery, ignoreCase = true) ||
                template.targetApp.appName.contains(searchQuery, ignoreCase = true) ||
                template.matchingRule.keywords.any { it.contains(searchQuery, ignoreCase = true) }
            }
        }

        // 分类筛选
        if (selectedCategory != null) {
            result = result.filter { it.category == selectedCategory }
        }

        // 可信度筛选
        if (selectedTrustLevel != null) {
            result = result.filter { it.statistics.trustLevel == selectedTrustLevel }
        }

        // 排序
        result = when (sortBy) {
            SortBy.RECENTLY_USED -> result.sortedByDescending { it.statistics.lastExecutionTime }
            SortBy.USAGE_FREQUENCY -> result.sortedByDescending { it.statistics.totalExecutions }
            SortBy.SUCCESS_RATE -> result.sortedByDescending { it.statistics.successRate }
            SortBy.SAVED_COST -> result.sortedByDescending { it.statistics.tokensSaved }
            SortBy.CREATED_TIME -> result.sortedByDescending { it.createdAt }
        }

        return result
    }

    // ========== 扩展函数 ==========

    private fun FlowTemplate.toUiItem(): FlowTemplateItem {
        return FlowTemplateItem(
            id = id,
            name = name,
            category = category,
            targetAppName = targetApp.appName,
            targetAppIcon = null, // TODO: 加载应用图标
            trustLevel = statistics.trustLevel,
            totalExecutions = statistics.totalExecutions,
            successRate = statistics.successRate,
            savedCost = calculateSavedCost(),
            lastExecutionTime = statistics.lastExecutionTime
        )
    }

    private fun FlowTemplateItem.toTemplate(): FlowTemplate {
        return templateMap[this.id]
            ?: throw IllegalStateException("Template not found: ${this.id}")
    }

    private fun FlowTemplate.calculateSavedCost(): Float {
        // 估算节省的成本（以元为单位）
        // 假设每次 VLM 调用约消耗 0.01 元
        val vlmCost = statistics.vlmCallsSkipped * 0.01f
        // 假设每次截图节省的时间价值
        val timeCost = statistics.screenshotsSkipped * 0.005f
        return vlmCost + timeCost
    }
}

// ========== UI 状态模型 ==========

/**
 * 模板列表 UI 状态
 */
data class FlowTemplateListUiState(
    val templates: List<FlowTemplateItem> = emptyList(),
    val filteredTemplates: List<FlowTemplateItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * 模板列表项
 */
data class FlowTemplateItem(
    val id: String,
    val name: String,
    val category: FlowCategory,
    val targetAppName: String,
    val targetAppIcon: android.graphics.drawable.Drawable?,
    val trustLevel: TrustLevel,
    val totalExecutions: Int,
    val successRate: Float,
    val savedCost: Float,
    val lastExecutionTime: Long
)

/**
 * 模板详情 UI 状态
 */
data class FlowTemplateDetailUiState(
    val template: FlowTemplate? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val showDeleteConfirm: Boolean = false,
    val showExportSuccess: Boolean = false
)

/**
 * 排序方式
 */
enum class SortBy {
    RECENTLY_USED,    // 最近使用
    USAGE_FREQUENCY,  // 使用频率
    SUCCESS_RATE,     // 成功率
    SAVED_COST,       // 节省成本
    CREATED_TIME      // 创建时间
}
