package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.core.tools.ToolManager
import com.alian.assistant.core.skills.SkillConfig
import com.alian.assistant.core.skills.SkillManager
import com.alian.assistant.core.skills.SkillRegistry
import com.alian.assistant.data.UserSkillsManager
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar

/**
 * 工具信息（用于展示）
 */
data class ToolInfo(
    val name: String,
    val description: String
)

/**
 * 技能信息（用于展示）
 */
data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val keywords: List<String>,
    val relatedApps: List<String>,
    val isUserCreated: Boolean = false  // 是否为用户自定义
)

/**
 * Agent 角色信息
 */
data class AgentInfo(
    val name: String,
    val icon: String,
    val role: String,
    val description: String,
    val responsibilities: List<String>
)

/**
 * 预定义的 Agents 列表
 */
val agentsList = listOf(
    AgentInfo(
        name = "Manager",
        icon = "🎯",
        role = "规划者",
        description = "负责理解用户意图，制定高层次的执行计划，并跟踪任务进度。",
        responsibilities = listOf(
            "分析用户请求，理解真实意图",
            "将复杂任务分解为可执行的子目标",
            "制定执行计划和步骤顺序",
            "根据执行反馈动态调整计划"
        )
    ),
    AgentInfo(
        name = "Executor",
        icon = "⚡",
        role = "执行者",
        description = "负责分析当前屏幕状态，决定具体的操作动作。",
        responsibilities = listOf(
            "分析屏幕截图，理解界面元素",
            "根据计划选择下一步操作",
            "确定点击、滑动、输入等具体动作",
            "输出精确的操作坐标和参数"
        )
    ),
    AgentInfo(
        name = "Reflector",
        icon = "🔍",
        role = "反思者",
        description = "负责评估操作结果，判断动作是否成功执行。",
        responsibilities = listOf(
            "对比操作前后的屏幕变化",
            "判断操作是否达到预期效果",
            "识别异常情况（如弹窗、错误）",
            "提供反馈帮助调整后续策略"
        )
    ),
    AgentInfo(
        name = "Notetaker",
        icon = "📝",
        role = "记录者",
        description = "负责记录执行过程中的关键信息，供其他 Agent 参考。",
        responsibilities = listOf(
            "记录任务执行的重要节点",
            "保存中间结果和状态信息",
            "为后续步骤提供上下文参考",
            "生成执行摘要和日志"
        )
    )
)

/**
 * 能力展示页面
 *
 * 展示 Agents 和 Tools（只读）
 */
@Composable
fun CapabilitiesScreen(
    onBack: () -> Unit = {},
    onCreateSkill: () -> Unit = {},  // 导航到创建 Skill 页面
    onEditSkill: (SkillConfig) -> Unit = {},  // 导航到编辑 Skill 页面
    onDeleteSkill: (String, () -> Unit) -> Unit = { _, _ -> }  // 删除 Skill 回调，带完成回调
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 页面可见性状态（用于动画）
    var isPageVisible by remember { mutableStateOf(false) }
    
    // 刷新 key，用于删除后触发重组
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        isPageVisible = true
    }

    // 支持侧边屏划屏返回
    BackHandler(enabled = true) {
        performLightHaptic(context)
        onBack()
    }

    // 获取 Tools（每次重组时重新获取，确保数据最新）
    val tools = if (ToolManager.isInitialized()) {
        ToolManager.getInstance().getAvailableTools().map { tool ->
            ToolInfo(name = tool.name, description = tool.description)
        }
    } else {
        emptyList()
    }

    // 额外的内置工具（不在 ToolManager 中但是系统能力）
    val builtInTools = listOf(
        ToolInfo("screenshot", "截取当前屏幕，获取界面图像供 AI 分析"),
        ToolInfo("tap", "点击屏幕指定坐标位置"),
        ToolInfo("swipe", "在屏幕上滑动，支持上下左右方向"),
        ToolInfo("type", "输入文本内容到当前焦点位置"),
        ToolInfo("press_key", "按下系统按键（Home、Back、Enter 等）")
    )

    val allTools = tools + builtInTools

    // 获取 Skills（每次重组时重新获取，确保新增/删除的 skill 能立即显示）
    val skills = if (SkillManager.isInitialized()) {
        val registry = SkillRegistry.getInstance()
        SkillManager.getInstance().getAllSkills().map { skill ->
            SkillInfo(
                id = skill.config.id,
                name = skill.config.name,
                description = skill.config.description,
                category = skill.config.category,
                keywords = skill.config.keywords,
                relatedApps = skill.config.relatedApps.map { it.name },
                isUserCreated = registry.isUserCreated(skill.config.id)
            )
        }
    } else {
        emptyList()
    }

    // 监听 refreshKey 变化，触发 skills 列表刷新
    LaunchedEffect(refreshKey) {
        // refreshKey 变化时会触发重组，skills 会重新计算
    }

    // Tab 状态
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Agents (${agentsList.size})", "Tools (${allTools.size})", "Skills (${skills.size})")

    // 页面进入/退出动画
    AnimatedVisibility(
        visible = isPageVisible,
        enter = fadeIn(
            animationSpec = tween(400, easing = EaseOut)
        ),
        exit = fadeOut(
            animationSpec = tween(300, easing = EaseIn)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            // 顶部标题 - 使用与 AlianLocalScreen 一致的 AlianAppBar
            AlianAppBar(
                title = "能力",
                onMenuClick = onBack,
                menuIcon = Icons.Default.KeyboardArrowLeft,
                showMoreMenu = false
            )

            // Tab 切换
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = colors.background,
                contentColor = colors.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) colors.primary else colors.textSecondary
                            )
                        }
                    )
                }
            }

            // 内容区域
            when (selectedTab) {
                0 -> AgentsListView()
                1 -> ToolsListView(tools = allTools)
                2 -> SkillsListView(
                    skills = skills,
                    onCreateSkill = onCreateSkill,
                    onEditSkill = onEditSkill,
                    onDeleteSkill = { skillId ->
                        onDeleteSkill(skillId) {
                            refreshKey++  // 删除完成后刷新列表
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AgentsListView() {
    val colors = BaoziTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 架构说明卡片
        item(key = "arch_intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🧠 多 Agent 协作架构",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "艾莲采用多 Agent 协作架构，每个 Agent 专注于特定职责，通过协作完成复杂的手机自动化任务。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Manager → Executor → Reflector → Notetaker",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textHint
                    )
                }
            }
        }

        // Agent 列表
        items(agentsList, key = { it.name }) { agent ->
            AgentCard(agent = agent)
        }
    }
}

@Composable
fun AgentCard(agent: AgentInfo) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Agent 图标
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = agent.icon,
                        fontSize = 28.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.secondary.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = agent.role,
                                fontSize = 11.sp,
                                color = colors.secondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = agent.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.textHint
                )
            }

            // 展开显示职责列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "职责",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    agent.responsibilities.forEach { responsibility ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                fontSize = 14.sp,
                                color = colors.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = responsibility,
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsListView(tools: List<ToolInfo>) {
    if (tools.isEmpty()) {
        EmptyState(message = "暂无工具")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools, key = { it.name }) { tool ->
                ToolCard(tool = tool)
            }
        }
    }
}

@Composable
fun ToolCard(tool: ToolInfo) {
    val colors = BaoziTheme.colors

    // 根据工具名获取图标
    val toolIcon = when (tool.name) {
        "search_apps" -> "🔍"
        "open_app" -> "📱"
        "deep_link" -> "🔗"
        "clipboard" -> "📋"
        "shell" -> "💻"
        "http" -> "🌐"
        else -> "🔧"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 工具图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.secondary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = toolIcon,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.description,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EmptyState(message: String) {
    val colors = BaoziTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📦",
                fontSize = 64.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
fun SkillsListView(
    skills: List<SkillInfo>,
    onCreateSkill: () -> Unit = {},
    onEditSkill: (SkillConfig) -> Unit = {},
    onDeleteSkill: (String) -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 统计用户自定义数量
    val userSkillCount = skills.count { it.isUserCreated }

    if (skills.isEmpty()) {
        EmptyState(message = "暂无技能")
    } else {
        // 按分类分组
        val skillsByCategory = skills.groupBy { it.category }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 技能说明卡片 + 创建按钮
            item(key = "skills_intro") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🎯 智能技能",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "艾莲内置了多种智能技能，可以自动识别用户意图并调用相应的应用完成任务。",
                                    fontSize = 13.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "共 ${skills.size} 个技能（$userSkillCount 个自定义）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textHint
                            )
                            // 创建按钮
                            Button(
                                onClick = {
                                    performLightHaptic(context)
                                    onCreateSkill()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("创建技能", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 按分类展示技能
            skillsByCategory.forEach { (category, categorySkills) ->
                item(key = "category_$category") {
                    Column {
                        Text(
                            text = category,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        categorySkills.forEach { skill ->
                            SkillCard(
                                skill = skill,
                                onEdit = if (skill.isUserCreated) {
                                    {
                                        performLightHaptic(context)
                                        // 获取完整 SkillConfig 并回调
                                        if (SkillManager.isInitialized()) {
                                            val fullSkill = SkillManager.getInstance().getAllSkills()
                                                .find { it.config.id == skill.id }
                                            fullSkill?.config?.let { onEditSkill(it) }
                                        }
                                    }
                                } else null,
                                onDelete = if (skill.isUserCreated) {
                                    {
                                        performLightHaptic(context)
                                        onDeleteSkill(skill.id)
                                    }
                                } else null
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkillCard(
    skill: SkillInfo,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除技能「${skill.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke()
                    }
                ) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (skill.isUserCreated) {
                colors.secondary.copy(alpha = 0.05f)
            } else {
                colors.backgroundCard
            }
        ),
        border = if (skill.isUserCreated) {
            BorderStroke(1.dp, colors.secondary.copy(alpha = 0.3f))
        } else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = skill.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        if (skill.isUserCreated) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.secondary.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "自定义",
                                    fontSize = 10.sp,
                                    color = colors.secondary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = skill.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 用户自定义技能显示操作按钮
                if (skill.isUserCreated && onEdit != null && onDelete != null) {
                    Row {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "编辑",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = colors.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.textHint
                )
            }

            // 展开显示详细信息
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    // 关键词
                    if (skill.keywords.isNotEmpty()) {
                        Text(
                            text = "关键词",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            skill.keywords.take(5).forEach { keyword ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.primary.copy(alpha = 0.1f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = keyword,
                                        fontSize = 12.sp,
                                        color = colors.primary
                                    )
                                }
                            }
                            if (skill.keywords.size > 5) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.textHint.copy(alpha = 0.1f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "+${skill.keywords.size - 5}",
                                        fontSize = 12.sp,
                                        color = colors.textHint
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 关联应用
                    if (skill.relatedApps.isNotEmpty()) {
                        Text(
                            text = "关联应用",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            skill.relatedApps.forEach { appName ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.secondary.copy(alpha = 0.2f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = appName,
                                        fontSize = 12.sp,
                                        color = colors.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 轻微震动效果
private fun performLightHaptic(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    if (vibrator?.hasVibrator() == true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(50, 150)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}
