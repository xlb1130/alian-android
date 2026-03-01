package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.core.skills.*
import com.alian.assistant.data.UserSkillsManager
import com.alian.assistant.infrastructure.device.AppScanner
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

/**
 * Skill 创建页面
 *
 * 三步流程：
 * 1. 描述功能 - 用户输入或选择场景
 * 2. 选择应用 - AI 生成配置 + 用户选择关联应用
 * 3. 确认保存 - 预览并保存 Skill
 */
@Composable
fun SkillCreatorScreen(
    editingSkill: SkillConfig? = null,  // 编辑模式时传入
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 页面状态
    var isPageVisible by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(0) }

    // Step 1: 功能描述
    var description by remember { mutableStateOf(editingSkill?.description ?: "") }
    var selectedCategory by remember { mutableStateOf(editingSkill?.category ?: "自定义") }

    // Step 2: AI 生成的配置 + 应用选择
    var generatedConfig by remember { mutableStateOf<SkillConfig?>(editingSkill) }
    var isGenerating by remember { mutableStateOf(false) }
    var selectedApps by remember { mutableStateOf<List<AppScanner.AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var deepLinkInput by remember { mutableStateOf("") }

    // Step 3: 最终确认
    var finalConfig by remember { mutableStateOf<SkillConfig?>(null) }

    val isEditMode = editingSkill != null

    // 初始化编辑模式的数据
    LaunchedEffect(editingSkill) {
        if (editingSkill != null) {
            description = editingSkill.description
            selectedCategory = editingSkill.category
            generatedConfig = editingSkill

            // 加载已选应用
            val appScanner = AppScanner(context)
            val installedApps = appScanner.getApps()
            selectedApps = editingSkill.relatedApps.mapNotNull { relatedApp ->
                installedApps.find { it.packageName == relatedApp.packageName }
            }

            // 加载 DeepLink
            deepLinkInput = editingSkill.relatedApps.firstOrNull()?.deepLink ?: ""
        }
    }

    LaunchedEffect(Unit) {
        isPageVisible = true
    }

    // 后退键处理
    BackHandler(enabled = true) {
        performLightHaptic(context)
        if (currentStep > 0) {
            currentStep--
        } else {
            onBack()
        }
    }

    AnimatedVisibility(
        visible = isPageVisible,
        enter = fadeIn(animationSpec = tween(400, easing = EaseOut)),
        exit = fadeOut(animationSpec = tween(300, easing = EaseIn))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            // 顶部导航栏
            AlianAppBar(
                title = if (isEditMode) "编辑技能" else "创建技能",
                onMenuClick = {
                    performLightHaptic(context)
                    if (currentStep > 0) {
                        currentStep--
                    } else {
                        onBack()
                    }
                },
                menuIcon = Icons.Default.KeyboardArrowLeft,
                showMoreMenu = false
            )

            // 步骤指示器
            StepIndicator(
                currentStep = currentStep,
                totalSteps = 3,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // 内容区域
            when (currentStep) {
                0 -> StepOneContent(
                    description = description,
                    onDescriptionChange = { description = it },
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it },
                    onNext = {
                        performLightHaptic(context)
                        // 编辑模式直接进入下一步
                        if (isEditMode && generatedConfig != null) {
                            currentStep = 1
                        } else if (description.isNotBlank()) {
                            currentStep = 1
                        }
                    },
                    isEditMode = isEditMode
                )

                1 -> StepTwoContent(
                    generatedConfig = generatedConfig,
                    isGenerating = isGenerating,
                    description = description,
                    selectedCategory = selectedCategory,
                    selectedApps = selectedApps,
                    onAppsSelected = { selectedApps = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    deepLinkInput = deepLinkInput,
                    onDeepLinkChange = { deepLinkInput = it },
                    onConfigGenerated = { generatedConfig = it },
                    onGeneratingChange = { isGenerating = it },
                    onNext = {
                        performLightHaptic(context)
                        if (selectedApps.isNotEmpty() && generatedConfig != null) {
                            // 构建最终配置
                            val relatedApps = selectedApps.mapIndexed { index, app ->
                                RelatedApp(
                                    packageName = app.packageName,
                                    name = app.appName,
                                    type = if (deepLinkInput.isNotBlank()) ExecutionType.DELEGATION else ExecutionType.GUI_AUTOMATION,
                                    deepLink = deepLinkInput.takeIf { it.isNotBlank() },
                                    priority = (selectedApps.size - index) * 10
                                )
                            }
                            finalConfig = generatedConfig!!.copy(relatedApps = relatedApps)
                            currentStep = 2
                        }
                    },
                    onBack = {
                        performLightHaptic(context)
                        currentStep = 0
                    }
                )

                2 -> StepThreeContent(
                    finalConfig = finalConfig,
                    onSave = {
                        performLightHaptic(context)
                        finalConfig?.let { config ->
                            UserSkillsManager.getInstance().saveUserSkill(config)
                            onSaved()
                        }
                    },
                    onBack = {
                        performLightHaptic(context)
                        currentStep = 1
                    }
                )
            }
        }
    }
}

/**
 * 步骤指示器
 */
@Composable
fun StepIndicator(
    currentStep: Int,
    totalSteps: Int = 3,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    val stepLabels = listOf("描述", "配置", "保存")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until totalSteps) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 圆圈 + 数字
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= currentStep) colors.primary
                            else colors.textHint.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (i < currentStep) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "${i + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (i <= currentStep) Color.White else colors.textHint
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 标签
                Text(
                    text = stepLabels[i],
                    fontSize = 13.sp,
                    fontWeight = if (i <= currentStep) FontWeight.Bold else FontWeight.Normal,
                    color = if (i <= currentStep) colors.textPrimary else colors.textHint
                )
            }

            // 连接线
            if (i < totalSteps - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 8.dp)
                        .background(
                            if (i < currentStep) colors.primary
                            else colors.textHint.copy(alpha = 0.3f)
                        )
                )
            }
        }
    }
}

/**
 * 第一步：描述功能
 */
@Composable
fun StepOneContent(
    description: String,
    onDescriptionChange: (String) -> Unit,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onNext: () -> Unit,
    isEditMode: Boolean
) {
    val colors = BaoziTheme.colors

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 说明卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "✨ 描述你想要的功能",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "告诉艾莲你想实现什么功能，AI 会帮你生成技能配置。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // 输入框
        item {
            Column {
                Text(
                    text = "功能描述",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    placeholder = {
                        Text(
                            text = "例如：帮我发微博、帮我点外卖、帮我导航到某个地点...",
                            color = colors.textHint
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.textHint.copy(alpha = 0.3f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = colors.backgroundCard
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // 分类选择
        item {
            Column {
                Text(
                    text = "选择分类",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                val categories = listOf(
                    "自定义", "社交", "购物", "外卖", "出行", "音乐", "视频",
                    "支付", "工具", "阅读", "AI", "生活"
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { category ->
                        CategoryChip(
                            text = category,
                            isSelected = selectedCategory == category,
                            onClick = { onCategorySelected(category) }
                        )
                    }
                }
            }
        }

        // 常用场景
        item {
            Column {
                Text(
                    text = "常用场景",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                val scenarios = listOf(
                    Triple("社交", "发布动态、发消息", Icons.Default.Chat),
                    Triple("购物", "买东西、比价", Icons.Default.ShoppingCart),
                    Triple("出行", "导航、打车", Icons.Default.DirectionsCar),
                    Triple("音乐", "听歌、播放音乐", Icons.Default.MusicNote),
                    Triple("视频", "看视频、追剧", Icons.Default.PlayCircle),
                    Triple("工具", "设置闹钟、记笔记", Icons.Default.Build)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(scenarios) { (name, desc, icon) ->
                        ScenarioCard(
                            name = name,
                            description = desc,
                            icon = icon,
                            onClick = {
                                onDescriptionChange("帮我$desc")
                                onCategorySelected(name)
                            }
                        )
                    }
                }
            }
        }

        // 底部按钮
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.textSecondary
                    )
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    enabled = description.isNotBlank() || isEditMode,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    )
                ) {
                    Text("下一步")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 第二步：选择应用
 */
@Composable
fun StepTwoContent(
    generatedConfig: SkillConfig?,
    isGenerating: Boolean,
    description: String,
    selectedCategory: String,
    selectedApps: List<AppScanner.AppInfo>,
    onAppsSelected: (List<AppScanner.AppInfo>) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    deepLinkInput: String,
    onDeepLinkChange: (String) -> Unit,
    onConfigGenerated: (SkillConfig) -> Unit,
    onGeneratingChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 获取已安装应用
    val installedApps = remember {
        AppScanner(context).getApps()
            .filter { !it.isSystem }
            .sortedBy { it.appName }
    }

    // 搜索过滤
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // AI 生成配置
    LaunchedEffect(description, selectedCategory) {
        if (generatedConfig == null && description.isNotBlank()) {
            onGeneratingChange(true)
            // 模拟 AI 生成（实际项目中应调用 LLM）
            delay(1000)
            val config = generateSkillConfigWithAI(description, selectedCategory, context)
            onConfigGenerated(config)
            onGeneratingChange(false)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // AI 生成状态
        item {
            if (isGenerating) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = colors.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "AI 正在生成配置...",
                            fontSize = 14.sp,
                            color = colors.primary
                        )
                    }
                }
            } else if (generatedConfig != null) {
                // 显示生成的配置
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "配置已生成",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.success
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        ConfigPreviewItem("名称", generatedConfig.name)
                        ConfigPreviewItem("描述", generatedConfig.description)
                        ConfigPreviewItem("分类", generatedConfig.category)
                        if (generatedConfig.keywords.isNotEmpty()) {
                            ConfigPreviewItem("关键词", generatedConfig.keywords.joinToString(", "))
                        }
                    }
                }
            }
        }

        // 选择应用
        item {
            Column {
                Text(
                    text = "选择关联应用",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "选择执行此技能时使用的应用（可多选）",
                    fontSize = 12.sp,
                    color = colors.textHint
                )
            }
        }

        // 搜索框
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用", color = colors.textHint) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.textHint
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除",
                                tint = colors.textHint
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.textHint.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        // 已选应用
        if (selectedApps.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "已选择 (${selectedApps.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedApps) { app ->
                            SelectedAppChip(
                                appName = app.appName,
                                onRemove = {
                                    onAppsSelected(selectedApps - app)
                                }
                            )
                        }
                    }
                }
            }
        }

        // 应用列表
        item {
            Text(
                text = "全部应用 (${filteredApps.size})",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        }

        items(filteredApps.take(20)) { app ->
            AppSelectionItem(
                app = app,
                isSelected = selectedApps.any { it.packageName == app.packageName },
                onClick = {
                    if (selectedApps.any { it.packageName == app.packageName }) {
                        onAppsSelected(selectedApps.filter { it.packageName != app.packageName })
                    } else {
                        onAppsSelected(selectedApps + app)
                    }
                }
            )
        }

        // DeepLink 输入（可选）
        item {
            Column {
                Text(
                    text = "DeepLink（可选）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "如果应用支持 DeepLink 跳转，可在此输入",
                    fontSize = 12.sp,
                    color = colors.textHint
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = deepLinkInput,
                    onValueChange = onDeepLinkChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：weixin://、alipays://", color = colors.textHint) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.textHint.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
        }

        // 底部按钮
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("上一步")
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    enabled = selectedApps.isNotEmpty() && generatedConfig != null,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    )
                ) {
                    Text("下一步")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 第三步：确认保存
 */
@Composable
fun StepThreeContent(
    finalConfig: SkillConfig?,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val colors = BaoziTheme.colors

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 说明卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "配置完成",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.success
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "请确认以下配置，点击保存后即可使用。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // 配置预览
        if (finalConfig != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "技能配置",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        ConfigPreviewItem("ID", finalConfig.id)
                        ConfigPreviewItem("名称", finalConfig.name)
                        ConfigPreviewItem("描述", finalConfig.description)
                        ConfigPreviewItem("分类", finalConfig.category)

                        if (finalConfig.keywords.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "触发关键词",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                finalConfig.keywords.forEach { keyword ->
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
                            }
                        }

                        if (finalConfig.relatedApps.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "关联应用",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            finalConfig.relatedApps.forEach { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (app.type == ExecutionType.DELEGATION)
                                            Icons.Default.Link else Icons.Default.TouchApp,
                                        contentDescription = null,
                                        tint = colors.secondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = app.name,
                                        fontSize = 13.sp,
                                        color = colors.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "(${app.type.name})",
                                        fontSize = 11.sp,
                                        color = colors.textHint
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 使用提示
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.info.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = colors.info,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "使用提示",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.info
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "保存后，你可以在对话中直接说「${finalConfig?.keywords?.firstOrNull() ?: "相关关键词"}」来触发此技能。",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        // 底部按钮
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("上一步")
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("保存")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ========== 辅助组件 ==========

@Composable
private fun CategoryChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Surface(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) colors.primary else colors.backgroundCard,
        border = if (!isSelected) {
            BorderStroke(1.dp, colors.textHint.copy(alpha = 0.3f))
        } else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else colors.textPrimary
            )
        }
    }
}

@Composable
private fun ScenarioCard(
    name: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Card(
        onClick = onClick,
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = colors.textHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConfigPreviewItem(label: String, value: String) {
    val colors = BaoziTheme.colors
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$label：",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.width(60.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun AppSelectionItem(
    app: AppScanner.AppInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                colors.primary.copy(alpha = 0.1f)
            } else {
                colors.backgroundCard
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, colors.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标占位
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.take(1),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    color = colors.textHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectedAppChip(
    appName: String,
    onRemove: () -> Unit
) {
    val colors = BaoziTheme.colors

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.primary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = appName,
                fontSize = 13.sp,
                color = colors.primary
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除",
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ========== AI 生成逻辑 ==========

/**
 * 模拟 AI 生成 Skill 配置
 * 实际项目中应调用 LLM API
 */
private fun generateSkillConfigWithAI(
    description: String,
    category: String,
    context: Context
): SkillConfig {
    // 从描述中提取核心内容（去掉前缀）
    val coreContent = description
        .replace("帮我", "")
        .replace("我想要", "")
        .replace("我想", "")
        .replace("请帮我", "")
        .replace("请", "")
        .trim()

    // 智能提取 name：取逗号或顿号前的内容，或整个核心内容
    val name = coreContent
        .split("、", ",", "，", "和", "与")
        .firstOrNull()?.trim()?.take(8) ?: coreContent.take(8)

    // 生成关键词（包含核心内容的各个部分）
    val keywords = mutableListOf<String>()
    keywords.add(name)
    coreContent.split("、", ",", "，", "和", "与").forEach { 
        if (it.isNotBlank()) keywords.add(it.trim())
    }

    // 添加一些衍生关键词
    if (coreContent.contains("发")) {
        keywords.add("发送")
    }
    if (coreContent.contains("看") || coreContent.contains("播放")) {
        keywords.add("播放")
    }
    if (coreContent.contains("买") || coreContent.contains("购")) {
        keywords.add("购物")
    }

    // 生成 ID
    val id = UserSkillsManager.getInstance().generateUniqueId(name)

    return SkillConfig(
        id = id,
        name = name,
        description = description,  // description 保持用户原始输入
        category = category,
        keywords = keywords.distinct(),
        params = emptyList(),
        relatedApps = emptyList(),
        promptHint = null
    )
}
