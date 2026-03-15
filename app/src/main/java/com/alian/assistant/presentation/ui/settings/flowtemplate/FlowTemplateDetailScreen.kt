package com.alian.assistant.presentation.ui.settings.flowtemplate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alian.assistant.R
import com.alian.assistant.core.flow.model.*
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import com.alian.assistant.presentation.ui.screens.components.MenuItem
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.FlowTemplateViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * 流程模板详情页面
 */
@Composable
fun FlowTemplateDetailScreen(
    templateId: String,
    onNavigateBack: () -> Unit,
    onExecute: (String) -> Unit,
    viewModel: FlowTemplateViewModel = viewModel()
) {
    val uiState by viewModel.detailUiState.collectAsState()
    var showExportSuccess by remember { mutableStateOf(false) }
    val colors = BaoziTheme.colors

    // 系统返回键支持
    BackHandler(onBack = onNavigateBack)

    LaunchedEffect(templateId) {
        viewModel.loadTemplateDetail(templateId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部导航栏 - 使用与设置页面一致的 AlianAppBar
        AlianAppBar(
            title = uiState.template?.name ?: stringResource(R.string.flow_template_detail),
            onMenuClick = onNavigateBack,
            menuIcon = Icons.Default.KeyboardArrowLeft,
            showMoreMenu = true,
            moreMenuItems = listOf(
                MenuItem(
                    text = stringResource(R.string.flow_template_delete),
                    icon = Icons.Default.Delete,
                    onClick = { viewModel.showDeleteConfirm() }
                )
            )
        )

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.template != null -> {
                val template = uiState.template!!
                TemplateDetailContent(
                    template = template,
                    onExecute = {
                        val executeInstruction = template.description.ifBlank { template.name }
                        onExecute(executeInstruction)
                    },
                    onExport = {
                        viewModel.exportTemplate(templateId) { json ->
                            showExportSuccess = json != null
                        }
                    }
                )
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.hideDeleteConfirm() },
            title = { Text(stringResource(R.string.flow_template_delete)) },
            text = { Text(stringResource(R.string.flow_template_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTemplate(templateId)
                        onNavigateBack()
                    }
                ) {
                    Text(
                        text = stringResource(android.R.string.ok),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideDeleteConfirm() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 导出成功提示
    if (showExportSuccess) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            showExportSuccess = false
        }
        Snackbar(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(stringResource(R.string.flow_template_export_success))
        }
    }
}

@Composable
private fun TemplateDetailContent(
    template: FlowTemplate,
    onExecute: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 基本信息卡片
        item {
            BasicInfoCard(template)
        }

        // 执行统计卡片
        item {
            StatisticsCard(template.statistics)
        }

        // 匹配规则卡片
        item {
            MatchingRuleCard(template.matchingRule)
        }

        // 执行步骤卡片
        item {
            StepsCard(template.steps)
        }

        // 操作按钮
        item {
            ActionButtons(
                onExecute = onExecute,
                onExport = onExport
            )
        }
    }
}

@Composable
private fun BasicInfoCard(template: FlowTemplate) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.flow_template_basic_info),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            InfoRow(
                label = stringResource(R.string.flow_template_name),
                value = template.name
            )
            InfoRow(
                label = stringResource(R.string.flow_template_category),
                value = getCategoryName(template.category)
            )
            InfoRow(
                label = stringResource(R.string.flow_template_target_app),
                value = template.targetApp.appName
            )
            InfoRow(
                label = stringResource(R.string.flow_template_created_at),
                value = formatDate(template.createdAt)
            )
            InfoRow(
                label = stringResource(R.string.flow_template_version),
                value = "v${template.version}"
            )
        }
    }
}

@Composable
private fun StatisticsCard(statistics: FlowStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.flow_template_statistics),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // 第一行统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard(
                    title = stringResource(R.string.flow_template_total_executions),
                    value = "${statistics.totalExecutions} ${stringResource(R.string.unit_times)}",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatCard(
                    title = stringResource(R.string.flow_template_success_rate),
                    value = "${(statistics.successRate * 100).toInt()}%",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatCard(
                    title = stringResource(R.string.flow_template_saved_cost),
                    value = "~${statistics.tokensSaved / 1000}k",
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 第二行统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard(
                    title = stringResource(R.string.flow_template_skipped_screenshots),
                    value = "${statistics.screenshotsSkipped} ${stringResource(R.string.unit_times)}",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatCard(
                    title = stringResource(R.string.flow_template_skipped_vlm),
                    value = "${statistics.vlmCallsSkipped} ${stringResource(R.string.unit_times)}",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatCard(
                    title = stringResource(R.string.flow_template_saved_time),
                    value = "~${(statistics.screenshotsSkipped + statistics.vlmCallsSkipped) * 3}s",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MatchingRuleCard(matchingRule: MatchingRuleDto) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.flow_template_matching_rule),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // 触发关键词
            Text(
                text = stringResource(R.string.flow_template_trigger_keywords),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                matchingRule.keywords.forEach { keyword ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(keyword) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepsCard(steps: List<FlowStep>) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "${stringResource(R.string.flow_template_steps)} (${steps.size} ${stringResource(R.string.unit_steps)})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            steps.forEachIndexed { index, step ->
                StepItem(
                    step = step,
                    stepNumber = index + 1,
                    isLast = index == steps.size - 1
                )
            }
        }
    }
}

@Composable
private fun StepItem(
    step: FlowStep,
    stepNumber: Int,
    isLast: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 步骤序号和连接线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(getNodeTypeColor(step.nodeType)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 步骤内容
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = getActionDescription(step.action),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = getNodeTypeText(step.nodeType),
                style = MaterialTheme.typography.labelSmall,
                color = getNodeTypeColor(step.nodeType)
            )
            if (step.successCount + step.failureCount > 0) {
                Text(
                    text = "成功率: ${(step.successRate * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionButtons(
    onExecute: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onExecute,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.flow_template_execute))
        }
        
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.flow_template_export))
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// ========== 辅助函数 ==========

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

private fun formatDate(timestamp: Long): String {
    return dateFormat.format(Date(timestamp))
}

private fun getCategoryName(category: FlowCategory): String {
    return when (category) {
        FlowCategory.NAVIGATION -> "导航"
        FlowCategory.FOOD_ORDERING -> "点餐"
        FlowCategory.SHOPPING -> "购物"
        FlowCategory.TRANSPORTATION -> "出行"
        FlowCategory.ENTERTAINMENT -> "娱乐"
        FlowCategory.UTILITIES -> "工具"
        FlowCategory.GENERAL -> "其他"
    }
}

private fun getActionDescription(action: StepAction): String {
    return when (action.type) {
        ActionType.TAP -> "点击「${action.target ?: ""}」"
        ActionType.LONG_TAP -> "长按「${action.target ?: ""}」"
        ActionType.SWIPE -> "滑动${action.swipeDirection?.name ?: ""}"
        ActionType.TYPE -> "输入「${action.inputValue ?: ""}」"
        ActionType.BACK -> "返回"
        ActionType.HOME -> "回到主页"
        ActionType.OPEN_APP -> "打开应用"
        ActionType.WAIT -> "等待"
        ActionType.SCROLL_TO_FIND -> "滚动查找「${action.target ?: ""}」"
        ActionType.UNKNOWN -> "未知操作"
    }
}

@Composable
private fun getNodeTypeColor(nodeType: NodeType): androidx.compose.ui.graphics.Color {
    return when (nodeType) {
        NodeType.ENTRY -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        NodeType.BRANCH -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        NodeType.KEY_OPERATION -> androidx.compose.ui.graphics.Color(0xFFF44336)
        NodeType.INTERMEDIATE -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        NodeType.RECOVERY -> androidx.compose.ui.graphics.Color(0xFF9C27B0)
        NodeType.COMPLETION -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    }
}

@Composable
private fun getNodeTypeText(nodeType: NodeType): String {
    return when (nodeType) {
        NodeType.ENTRY -> "入口节点"
        NodeType.BRANCH -> "分支节点"
        NodeType.KEY_OPERATION -> "关键操作"
        NodeType.INTERMEDIATE -> "中间节点"
        NodeType.RECOVERY -> "恢复节点"
        NodeType.COMPLETION -> "完成节点"
    }
}
