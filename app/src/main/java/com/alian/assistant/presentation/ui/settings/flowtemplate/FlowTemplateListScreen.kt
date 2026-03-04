package com.alian.assistant.presentation.ui.settings.flowtemplate

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alian.assistant.R
import com.alian.assistant.core.flow.model.FlowCategory
import com.alian.assistant.core.flow.model.TrustLevel
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import com.alian.assistant.presentation.ui.screens.components.MenuItem
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.FlowTemplateItem
import com.alian.assistant.presentation.viewmodel.FlowTemplateViewModel
import com.alian.assistant.presentation.viewmodel.SortBy
import java.text.SimpleDateFormat
import java.util.*

/**
 * 流程模板列表页面
 */
@Composable
fun FlowTemplateListScreen(
    onNavigateToDetail: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: FlowTemplateViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    val colors = BaoziTheme.colors

    // 系统返回键支持
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部导航栏 - 使用与设置页面一致的 AlianAppBar
        AlianAppBar(
            title = stringResource(R.string.flow_template),
            onMenuClick = onBack,
            menuIcon = Icons.Default.KeyboardArrowLeft,
            showMoreMenu = true,
            moreMenuItems = listOf(
                MenuItem(
                    text = stringResource(R.string.flow_template_filter),
                    icon = Icons.Default.FilterList,
                    onClick = { showFilterDialog = true }
                ),
                MenuItem(
                    text = stringResource(R.string.flow_template_sort),
                    icon = Icons.Default.Sort,
                    onClick = { showSortDialog = true }
                )
            )
        )

        // 搜索框
        SearchBar(
            query = viewModel.searchQuery,
            onQueryChange = { viewModel.search(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 分类标签
        CategoryTabs(
            selectedCategory = viewModel.selectedCategory,
            onCategorySelected = { viewModel.selectCategory(it) }
        )

        // 内容区域
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.filteredTemplates.isEmpty() -> {
                EmptyState(
                    hasTemplates = uiState.templates.isNotEmpty(),
                    searchQuery = viewModel.searchQuery
                )
            }
            else -> {
                TemplateList(
                    templates = uiState.filteredTemplates,
                    onTemplateClick = onNavigateToDetail
                )
            }
        }
    }

    // 筛选对话框
    if (showFilterDialog) {
        FilterDialog(
            currentTrustLevel = viewModel.selectedTrustLevel,
            onTrustLevelSelected = { 
                viewModel.selectTrustLevel(it)
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }

    // 排序对话框
    if (showSortDialog) {
        SortDialog(
            currentSortBy = viewModel.sortBy,
            onSortSelected = { 
                viewModel.updateSortBy(it)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.flow_template_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun CategoryTabs(
    selectedCategory: FlowCategory?,
    onCategorySelected: (FlowCategory?) -> Unit
) {
    val categories = listOf(null to R.string.category_all) + FlowCategory.values().map { category ->
        category to when (category) {
            FlowCategory.NAVIGATION -> R.string.category_navigation
            FlowCategory.FOOD_ORDERING -> R.string.category_food_ordering
            FlowCategory.SHOPPING -> R.string.category_shopping
            FlowCategory.TRANSPORTATION -> R.string.category_transportation
            FlowCategory.ENTERTAINMENT -> R.string.category_entertainment
            FlowCategory.UTILITIES -> R.string.category_utilities
            FlowCategory.GENERAL -> R.string.category_general
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(categories) { (category, labelRes) ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}

@Composable
private fun TemplateList(
    templates: List<FlowTemplateItem>,
    onTemplateClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(templates, key = { it.id }) { template ->
            TemplateCard(
                template = template,
                onClick = { onTemplateClick(template.id) }
            )
        }
    }
}

@Composable
private fun TemplateCard(
    template: FlowTemplateItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // 分类图标
                    CategoryIcon(
                        category = template.category,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = template.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = template.targetAppName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 可信度徽章
                TrustLevelBadge(trustLevel = template.trustLevel)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 统计信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatItem(
                    label = stringResource(R.string.flow_template_total_executions),
                    value = "${template.totalExecutions} ${stringResource(R.string.unit_times)}"
                )
                StatItem(
                    label = stringResource(R.string.flow_template_success_rate),
                    value = "${(template.successRate * 100).toInt()}%",
                    valueColor = when {
                        template.successRate >= 0.9f -> MaterialTheme.colorScheme.primary
                        template.successRate >= 0.7f -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                StatItem(
                    label = stringResource(R.string.flow_template_saved_cost),
                    value = "~${template.savedCost.toInt()} ${stringResource(R.string.unit_yuan)}"
                )
            }

            // 最近执行时间
            if (template.lastExecutionTime > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.flow_template_last_execution,
                        formatRelativeTime(template.lastExecutionTime)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryIcon(
    category: FlowCategory,
    modifier: Modifier = Modifier
) {
    val (icon, backgroundColor) = when (category) {
        FlowCategory.NAVIGATION -> Icons.Default.Map to 0xFF4CAF50
        FlowCategory.FOOD_ORDERING -> Icons.Default.Restaurant to 0xFFFF9800
        FlowCategory.SHOPPING -> Icons.Default.ShoppingCart to 0xFFE91E63
        FlowCategory.TRANSPORTATION -> Icons.Default.DirectionsCar to 0xFF2196F3
        FlowCategory.ENTERTAINMENT -> Icons.Default.Movie to 0xFF9C27B0
        FlowCategory.UTILITIES -> Icons.Default.Build to 0xFF607D8B
        FlowCategory.GENERAL -> Icons.Default.Apps to 0xFF9E9E9E
    }
    
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color(backgroundColor.toInt()))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TrustLevelBadge(trustLevel: TrustLevel) {
    val (text, color) = when (trustLevel) {
        TrustLevel.HIGHLY_TRUSTED -> stringResource(R.string.trust_level_highly_trusted) to 
            MaterialTheme.colorScheme.primary
        TrustLevel.TRUSTED -> stringResource(R.string.trust_level_trusted) to 
            MaterialTheme.colorScheme.secondary
        TrustLevel.PROBATIONARY -> stringResource(R.string.trust_level_probationary) to 
            MaterialTheme.colorScheme.tertiary
        TrustLevel.NEW -> stringResource(R.string.trust_level_new) to 
            MaterialTheme.colorScheme.outline
        TrustLevel.UNRELIABLE -> stringResource(R.string.trust_level_unreliable) to 
            MaterialTheme.colorScheme.error
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun EmptyState(
    hasTemplates: Boolean,
    searchQuery: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (hasTemplates) Icons.Default.SearchOff else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (hasTemplates) 
                    stringResource(R.string.flow_template_not_found)
                else 
                    stringResource(R.string.flow_template_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasTemplates) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.flow_template_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterDialog(
    currentTrustLevel: TrustLevel?,
    onTrustLevelSelected: (TrustLevel?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_template_filter)) },
        text = {
            Column {
                Text(
                    text = "可信度",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                listOf<TrustLevel?>(
                    null,
                    TrustLevel.HIGHLY_TRUSTED,
                    TrustLevel.TRUSTED,
                    TrustLevel.PROBATIONARY,
                    TrustLevel.NEW
                ).forEach { level ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrustLevelSelected(level) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTrustLevel == level,
                            onClick = { onTrustLevelSelected(level) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = level?.let { 
                                when (it) {
                                    TrustLevel.HIGHLY_TRUSTED -> stringResource(R.string.trust_level_highly_trusted)
                                    TrustLevel.TRUSTED -> stringResource(R.string.trust_level_trusted)
                                    TrustLevel.PROBATIONARY -> stringResource(R.string.trust_level_probationary)
                                    TrustLevel.NEW -> stringResource(R.string.trust_level_new)
                                    TrustLevel.UNRELIABLE -> stringResource(R.string.trust_level_unreliable)
                                }
                            } ?: stringResource(R.string.category_all)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun SortDialog(
    currentSortBy: SortBy,
    onSortSelected: (SortBy) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_template_sort)) },
        text = {
            Column {
                listOf(
                    SortBy.RECENTLY_USED to R.string.sort_by_recently_used,
                    SortBy.USAGE_FREQUENCY to R.string.sort_by_usage_frequency,
                    SortBy.SUCCESS_RATE to R.string.sort_by_success_rate,
                    SortBy.SAVED_COST to R.string.sort_by_saved_cost,
                    SortBy.CREATED_TIME to R.string.sort_by_created_time
                ).forEach { (sortBy, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSortSelected(sortBy) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSortBy == sortBy,
                            onClick = { onSortSelected(sortBy) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(labelRes))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

// ========== 辅助函数 ==========

private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        diff < 604800_000 -> "${diff / 86400_000} 天前"
        else -> dateFormat.format(Date(timestamp))
    }
}
