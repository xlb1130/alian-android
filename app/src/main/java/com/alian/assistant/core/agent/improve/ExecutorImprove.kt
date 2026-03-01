package com.alian.assistant.core.agent.improve

import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.memory.ActionBatch
import org.json.JSONObject

/**
 * Executor 改进版本 - 支持置信度提取和精简 Prompt
 * 
 * 优化点：
 * 1. 精简 Prompt（减少 65% token）
 * 2. 提取置信度（支持 Reflector 跳过）
 * 3. 压缩动作说明
 */
class ExecutorImprove {

    companion object {
        // 精简核心规则，移除重复
        val CORE_RULES = """
CRITICAL RULES (MUST FOLLOW):
1. CAPTCHA/Verification → use take_over, NEVER solve yourself
2. Payment buttons (支付/付款/结算) → use take_over
3. NO repeated same action for same step
4. FIRST STEP: must open_app or system_button(Home)
5. When opening apps, use open_app tool instead of clicking desktop icons
6. Finding app on desktop: Swipe LEFT to find search bar (usually on leftmost page), NOT swipe up to open app drawer
7. Text input: click field first → ensure keyboard visible → type. CRITICAL: Check if input field already contains the target text in the input box area (NOT keyboard suggestions/history). If target text is NOT present in input box, MUST execute type action.
8. If input field has cached text (actual text in the box, NOT keyboard suggestions) → clear it first (long press backspace). DO NOT skip type action if text only appears in keyboard suggestions.
9. If items or content are not fully visible, swipe screen appropriately to get more information
    """.trimIndent()
    }

    /**
     * 生成执行 Prompt（改进版 - 平衡精简和清晰度）
     * @param enableBatchExecution 是否启用批量执行模式
     */
    fun getPrompt(infoPool: InfoPoolImprove, enableBatchExecution: Boolean = true): String = buildString {
        append("### User Request ###\n")
        append("${infoPool.instruction}\n\n")

        append("### Plan ###\n")
        append("${infoPool.plan}\n\n")

        val subgoals = infoPool.plan.split(Regex("(?<=\\d)\\. ")).take(2)
        append("### Current Subgoal ###\n")
        append("${subgoals.joinToString(". ")}\n\n")

        append("### Available Actions ###\n")
        append("- click[x,y]: Click at coordinate. Example: {\"action\": \"click\", \"coordinate\": [500, 800], \"tell_user\": \"message to tell user\"}\n")
        append("  Optional: Add \"target_text\" for element text, \"target_desc\" for description, \"target_resource_id\" for resource ID.\n")
        append("  Example: {\"action\": \"click\", \"coordinate\": [500, 800], \"target_text\": \"确定\", \"tell_user\": \"点击确定\"}\n")
        append("- type[text]: Type text. Example: {\"action\": \"type\", \"text\": \"hello\", \"tell_user\": \"message to tell user\"}\n")
        append("  Optional: Add \"target_text\" for input field text.\n")
        append("- swipe[x1,y1→x2,y2]: Swipe. Example: {\"action\": \"swipe\", \"coordinate\": [500, 800], \"coordinate2\": [500, 300], \"tell_user\": \"message to tell user\"}\n")
        append("- open_app[name]: Open app. Example: {\"action\": \"open_app\", \"text\": \"美团\", \"tell_user\": \"message to tell user\"}\n")
        append("- system_button[Back/Home]: Press button. Example: {\"action\": \"system_button\", \"button\": \"Back\", \"tell_user\": \"message to tell user\"}\n")
        append("- wait[1-10]: Wait seconds. Example: {\"action\": \"wait\", \"duration\": 2, \"tell_user\": \"message to tell user\"}\n")
        append("- take_over[msg]: Request user help. Example: {\"action\": \"take_over\", \"message\": \"help message\", \"tell_user\": \"help message\"}\n")
        append("- answer[text]: Answer question. Example: {\"action\": \"answer\", \"text\": \"答案\", \"tell_user\": \"message to tell user\"}\n")
        if (infoPool.installedApps.isNotEmpty()) {
            append("\nInstalled Apps: ${infoPool.installedApps.take(20)}\n")
        }
        append("\n")

        if (enableBatchExecution) {
            append("### Batch Mode ###\n")
            append("You can return MULTIPLE independent actions in one response.\n")
            append("IMPORTANT: Each action MUST include a \"tell_user\" field (10 Chinese characters or less).\n")
            append("TIP: Add \"target_text\" field to help accessibility element location when clicking buttons.\n")
            append("Format: {\"actions\": [{action1}, {action2}, ...], \"description\": \"...\"}\n")
            append("Example:\n")
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [480, 70], \"target_text\": \"搜索\", \"tell_user\": \"点击搜索框\"},\n")
            append("    {\"action\": \"type\", \"text\": \"便宜的汉堡\", \"tell_user\": \"输入文字\"},\n")
            append("    {\"action\": \"click\", \"coordinate\": [900, 60], \"target_text\": \"搜索\", \"tell_user\": \"点击搜索\"}\n")
            append("  ],\n")
            append("  \"description\": \"点击搜索框，输入文字，点击搜索\"\n")
            append("}\n\n")
        } else {
            append("### Single Action Mode ###\n")
            append("Return exactly ONE action.\n")
            append("IMPORTANT: The action MUST include a \"tell_user\" field (10 Chinese characters or less).\n")
            append("Format: {\"actions\": [{action}], \"description\": \"...\"}\n\n")
        }

        append("### Action History ###\n")
        if (infoPool.actionHistory.isNotEmpty()) {
            val recentActions = infoPool.actionHistory.takeLast(20)
            val recentOutcomes = infoPool.actionOutcomes.takeLast(20)
            val recentSummaries = infoPool.summaryHistory.takeLast(20)
            
            recentActions.forEachIndexed { i, act ->
                val outcome = recentOutcomes.getOrNull(i) ?: "?"
                val summary = recentSummaries.getOrNull(i) ?: ""
                val outcomeDesc = when(outcome) {
                    "A" -> "✓"
                    "B" -> "✗ wrong page"
                    "C" -> "✗ no change"
                    else -> "?"
                }
                append("${i+1}. ${act.type}${formatActionParams(act)} → $outcomeDesc")
                if (summary.isNotEmpty()) append(" ($summary)")
                append("\n")
            }
        } else {
            append("No actions yet.\n")
        }
        append("\n")

        append("### Rules ###\n")
        append(CORE_RULES)
        append("\n\n")

        append("### Thought ###\n")
        append("(Analyze the SCREENSHOT first, then answer briefly)\n")
        append("1. Current state: What's on screen? Any blockers (popup/loading)?\n")
        append("2. Target visible? Need scroll?\n")
        append("3. For type actions: Check if target text is in INPUT BOX (not keyboard suggestions). If NOT in input box, MUST type.\n")
        append("4. Decision: Action + reason\n")
        append("5. Confidence: 0.0-1.0\n\n")

        append("### Action ###\n")
        append("Return JSON with \"actions\" array. Each action MUST include \"tell_user\" field:\n")
        if (enableBatchExecution) {
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 800], \"target_text\": \"确定\", \"tell_user\": \"点击屏幕\"},\n")
            append("    {\"action\": \"type\", \"text\": \"text\", \"tell_user\": \"输入文字\"}\n")
            append("  ],\n")
            append("  \"description\": \"brief description\"\n")
            append("}\n\n")
        } else {
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 800], \"target_text\": \"确定\", \"tell_user\": \"点击屏幕\"}\n")
            append("  ],\n")
            append("  \"description\": \"brief description\"\n")
            append("}\n\n")
        }

        append("### Description ###\n")
        append("Brief description of what you're doing.\n")
        append("### Tell User ###\n")
        append("Each action MUST include a \"tell_user\" field with 10 Chinese characters or less.\n")
    }

    /**
     * 解析执行响应
     * 支持标准格式、直接JSON格式和 MAI-UI 格式
     */
    fun parseResponse(response: String, enableBatchExecution: Boolean = true): ExecutorResult {
        // 检测是否为 MAI-UI 格式
        if (response.contains("<action>") || response.contains("<thinking>")) {
            return parseMAIUIResponse(response)
        }

        // 尝试解析直接的JSON格式 {"actions": [...], "description": "..."}
        val directJsonResult = parseDirectJsonFormat(response, enableBatchExecution)
        if (directJsonResult != null) {
            return directJsonResult
        }

        // 标准格式解析
        val thought = response
            .substringAfter("### Thought", "")
            .substringBefore("### Action")
            .replace("###", "")
            .trim()

        val actionStr = response
            .substringAfter("### Action", "")
            .substringBefore("### Description")
            .replace("###", "")
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val description = response
            .substringAfter("### Description", "")
            .substringBefore("### Tell User")
            .replace("###", "")
            .trim()

        // 提取 tell_user 字段
        val tellUser = response
            .substringAfter("### Tell User", "")
            .replace("###", "")
            .trim()

        // 提取置信度
        val confidence = extractConfidence(thought)

        val action = Action.fromJson(actionStr)

        // 批量执行模式
        if (enableBatchExecution && ActionBatch.isBatchFormat(actionStr)) {
            return parseBatchResponse(thought, actionStr, description, confidence, tellUser)
        }

        // 单个模式
        if (!enableBatchExecution && action != null) {
            val wrappedBatch = ActionBatch(
                actions = listOf(action),
                description = description,
                thought = thought
            )
            return ExecutorResult(
                thought = thought,
                actionStr = actionStr,
                description = description,
                actionBatch = wrappedBatch,
                confidence = confidence,
                tellUser = tellUser
            )
        }

        // 解析失败
        val fallbackBatch = ActionBatch(
            actions = listOf(Action(type = "invalid")),
            description = description,
            thought = thought
        )
        return ExecutorResult(
            thought = thought,
            actionStr = actionStr,
            description = description,
            actionBatch = fallbackBatch,
            confidence = 0.0f
        )
    }

    /**
     * 解析直接的JSON格式 {"actions": [...], "description": "..."}
     */
    private fun parseDirectJsonFormat(response: String, enableBatchExecution: Boolean): ExecutorResult? {
        return try {
            val cleanResponse = response.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            // 检查是否是有效的JSON对象格式
            if (!cleanResponse.startsWith("{") || !cleanResponse.endsWith("}")) {
                return null
            }
            
            val obj = JSONObject(cleanResponse)
            val actionsArray = obj.optJSONArray("actions")
            
            if (actionsArray == null) {
                return null
            }
            
            // 解析动作数组
            val actions = mutableListOf<Action>()
            for (i in 0 until actionsArray.length()) {
                val actionJson = actionsArray.getJSONObject(i)
                val action = Action.fromStandardJson(actionJson)
                if (action != null) {
                    actions.add(action)
                }
            }
            
            val description = obj.optString("description", "")
            
            // 从JSON中提取可能的thought信息
            val thought = obj.optString("thought", "")
            
            // 从JSON中提取tell_user字段（10字以内的操作说明）
            val tellUser = obj.optString("tell_user", "")
            
            // 从JSON中提取可能的置信度信息
            val confidence = if (obj.has("confidence")) {
                obj.optDouble("confidence", 0.5).toFloat()
            } else {
                extractConfidence(thought)
            }
            
            if (actions.isEmpty()) {
                null
            } else {
                val actionBatch = ActionBatch(
                    actions = actions,
                    description = description,
                    thought = thought
                )
                ExecutorResult(
                    thought = thought,
                    actionStr = cleanResponse,
                    description = description,
                    actionBatch = actionBatch,
                    confidence = confidence,
                    tellUser = tellUser
                )
            }
        } catch (e: Exception) {
            // 如果解析失败，返回null让其他解析方法尝试
            null
        }
    }

    /**
     * 解析批量 Action 响应
     */
    private fun parseBatchResponse(
        thought: String,
        actionStr: String,
        description: String,
        confidence: Float,
        tellUser: String = ""
    ): ExecutorResult {
        val actionBatch = ActionBatch.fromJson(actionStr, thought)

        if (actionBatch == null || actionBatch.actions.isEmpty()) {
            val fallbackBatch = ActionBatch(
                actions = listOf(Action(type = "invalid")),
                description = description,
                thought = thought
            )
            return ExecutorResult(
                thought = thought,
                actionStr = actionStr,
                description = description,
                actionBatch = fallbackBatch,
                confidence = 0.0f,
                tellUser = tellUser
            )
        }

        val batchDescription = actionBatch.description.ifEmpty { description }

        return ExecutorResult(
                        thought = thought,
                        actionStr = actionStr,
                        description = batchDescription,
                        actionBatch = actionBatch,
                        confidence = confidence,
                        tellUser = tellUser
                    )    }

    /**
     * 解析 MAI-UI 格式响应
     */
    private fun parseMAIUIResponse(response: String): ExecutorResult {
        val thought = Action.extractThinking(response)
        val action = Action.fromMAIUIFormat(response)
        val confidence = extractConfidence(thought)

        val description = when (action?.type) {
            "click" -> "点击坐标 (${action.x}, ${action.y})"
            "long_press" -> "长按坐标 (${action.x}, ${action.y})"
            "double_tap" -> "双击坐标 (${action.x}, ${action.y})"
            "swipe" -> {
                if (action.direction != null) {
                    "向${action.direction}滑动"
                } else {
                    "从 (${action.x}, ${action.y}) 滑动到 (${action.x2}, ${action.y2})"
                }
            }
            "type" -> "输入文字: ${action.text}"
            "open_app" -> "打开应用: ${action.text}"
            "system_button" -> "按${action.button}键"
            "wait" -> "等待"
            "terminate" -> "任务${if (action.status == "success") "完成" else "失败"}"
            "answer" -> "回答: ${action.text?.take(30)}"
            "take_over" -> "请求用户: ${action.text}"
            else -> action?.type ?: "未知动作"
        }

        val tellUser = when (action?.type) {
            "click" -> "点击屏幕"
            "long_press" -> "长按屏幕"
            "double_tap" -> "双击屏幕"
            "swipe" -> "滑动屏幕"
            "type" -> "输入文字"
            "open_app" -> "打开应用"
            "system_button" -> "按键操作"
            "wait" -> "等待中"
            "terminate" -> "任务结束"
            "answer" -> "回答问题"
            "take_over" -> "需要帮助"
            else -> "执行操作"
        }

        val actionStr = action?.toJson() ?: ""

        val actionBatch = if (action != null) {
            ActionBatch(
                actions = listOf(action),
                description = description,
                thought = thought
            )
        } else {
            ActionBatch(
                actions = listOf(Action(type = "invalid")),
                description = description,
                thought = thought
            )
        }

        return ExecutorResult(thought, actionStr, description, actionBatch, confidence, tellUser)
    }

    /**
     * 格式化动作参数
     */
    private fun formatActionParams(action: Action): String {
        return buildString {
            when (action.type) {
                "click", "long_press", "double_tap" -> {
                    if (action.targetText != null) append("[${action.targetText}]")
                    else if (action.targetDesc != null) append("[${action.targetDesc}]")
                    else append("[${action.x}, ${action.y}]")
                }
                "type" -> {
                    append("[${action.text?.take(20)}]")
                }
                "swipe" -> {
                    append("[${action.x}, ${action.y} → ${action.x2}, ${action.y2}]")
                }
                "open_app" -> {
                    append("[${action.text}]")
                }
                "system_button" -> {
                    append("[${action.button}]")
                }
                "wait" -> {
                    append("[${action.duration}s]")
                }
                "take_over" -> {
                    append("[${action.message}]")
                }
                "answer" -> {
                    append("[${action.text?.take(20)}]")
                }
                else -> {}
            }
        }
    }

    /**
     * 从 thought 中提取置信度
     * 支持格式：
     * - Confidence: 0.9
     * - 置信度: 0.9
     * - confidence: 0.9
     */
    private fun extractConfidence(thought: String): Float {
        val confidencePattern = Regex("""(?:Confidence|置信度|confidence)[:：]\s*([\d.]+)""")
        val match = confidencePattern.find(thought)
        
        val confidence = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
        
        // 限制在 0-1 范围内
        return confidence.coerceIn(0f, 1f)
    }
}

data class ExecutorResult(
    val thought: String,
    val actionStr: String,
    val description: String,
    val actionBatch: ActionBatch,
    val confidence: Float = 0.5f,  // 新增：置信度
    val tellUser: String = ""  // 新增：告知用户的操作说明（10字以内）
)