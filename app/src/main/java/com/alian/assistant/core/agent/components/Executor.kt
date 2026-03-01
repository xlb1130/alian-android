package com.alian.assistant.core.agent.components

import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.memory.ActionBatch
import com.alian.assistant.core.agent.memory.InfoPool

/**
 * Executor Agent - 决定具体执行什么动作
 */
class Executor {

    companion object {
        val GUIDELINES = """
General:
- For any pop-up window, close it (e.g., by clicking 'Don't Allow' or 'Accept') before proceeding.
- For requests that are questions, remember to use the `answer` action to reply before finish!
- If the desired state is already achieved, you can just complete the task.

Action Related:
- Use `open_app` to open an app, do not use the app drawer.
- Consider using `swipe` to reveal additional content.
- If swiping doesn't change the page, it may have reached the bottom.

Text Related:
- To input text: first click the input box, make sure keyboard is visible, then use `type` action.
- To clear text: long press the backspace button in the keyboard.

Important Rules:
- When encountering a CAPTCHA/verification code (验证码、图形验证、滑块验证等), MUST use `take_over` action to request user assistance. DO NOT try to solve it yourself.
- DO NOT repeat the same action for the same step. If an action failed, try a different approach or ask for help.
- At the FIRST step of execution, you MUST either `open_app` to open the target app OR use `system_button` with "Home" to return to the home screen. This is mandatory for every new task.
        """.trimIndent()
    }

    /**
     * 生成执行 Prompt
     * @param enableBatchExecution 是否启用批量执行模式
     */
    fun getPrompt(infoPool: InfoPool, enableBatchExecution: Boolean = true): String = buildString {
        append("You are an agent who can operate an Android phone. ")
        append("Decide the next action based on the current state.\n\n")

        append("### User Request ###\n")
        append("${infoPool.instruction}\n\n")

        append("### Overall Plan ###\n")
        append("${infoPool.plan}\n\n")

        append("### Current Subgoal ###\n")
        val subgoals = infoPool.plan.split(Regex("(?<=\\d)\\. ")).take(3)
        append("${subgoals.joinToString(". ")}\n\n")

        append("### Progress Status ###\n")
        if (infoPool.progressStatus.isNotEmpty()) {
            append("${infoPool.progressStatus}\n\n")
        } else {
            append("No progress yet.\n\n")
        }

        append("### Guidelines ###\n")
        append("$GUIDELINES\n\n")

        append("---\n")
        append("Examine all information and decide on the next action.\n\n")

        append("#### Atomic Actions ####\n")
        append("- click(coordinate): Click at (x, y). Example: {\"action\": \"click\", \"coordinate\": [x, y], \"tell_user\": \"帮你点一下\"}\n")
        append("- double_tap(coordinate): Double tap at (x, y) for zoom or like. Example: {\"action\": \"double_tap\", \"coordinate\": [x, y]}\n")
        append("- long_press(coordinate): Long press at (x, y). Example: {\"action\": \"long_press\", \"coordinate\": [x, y]}\n")
        append("- type(text): Type text into activated input box. Example: {\"action\": \"type\", \"text\": \"hello\", \"tell_user\": \"帮你输入\"}\n")
        append("- swipe(coordinate, coordinate2): Swipe from point1 to point2. Example: {\"action\": \"swipe\", \"coordinate\": [x1, y1], \"coordinate2\": [x2, y2]}\n")
        append("- system_button(button): Press Back/Home/Enter. Example: {\"action\": \"system_button\", \"button\": \"Back\"}\n")
        append("- open_app(text): Open an app by name. ALWAYS use this instead of looking for app icons on screen! Example: {\"action\": \"open_app\", \"text\": \"设置\"}\n")
        if (infoPool.installedApps.isNotEmpty()) {
            append("  Available apps: ${infoPool.installedApps}\n")
        }
        append("- wait(duration): Wait for page loading. Duration in seconds (1-10). Example: {\"action\": \"wait\", \"duration\": 3}\n")
        append("- take_over(message): Request user to manually complete login/captcha/verification. Example: {\"action\": \"take_over\", \"message\": \"请完成登录验证\"}\n")
        append("- answer(text): Answer user's question. Example: {\"action\": \"answer\", \"text\": \"The answer is...\"}\n")
        append("\n")
        append("Note: Each action can optionally include a \"tell_user\", which will be used to tell the user what you're about to do in a friendly, helpful assistant tone. Keep it natural and colloquial. Speak like a helpful secretary.\n")
        append("\n")

        // 仅在启用批量执行模式时显示批量 Actions 说明
        if (enableBatchExecution) {
            append("#### Batch Actions (Recommended for Efficiency) ####\n")
            append("You can return MULTIPLE actions at once to speed up execution!\n")
            append("Rules for batch actions:\n")
            append("1. All actions must be based on the CURRENT screenshot (static analysis)\n")
            append("2. Actions must be INDEPENDENT (no dependencies between them)\n")
            append("3. No need to wait for intermediate results\n")
            append("\n")
            append("Suitable scenarios for batch:\n")
            append("- Clicking multiple fixed buttons (e.g., closing multiple popups)\n")
            append("- Input + Confirm (click input → type → click confirm)\n")
            append("- Multiple independent UI operations (e.g., toggling multiple switches)\n")
            append("- Swipe + Click (when target is clearly visible)\n")
            append("\n")
            append("NOT suitable for batch:\n")
            append("- Actions that require waiting for page load\n")
            append("- Actions that depend on previous action results\n")
            append("- Actions involving dynamic element positions\n")
            append("- Actions requiring verification (e.g., payment confirm)\n")
            append("\n")
            append("Batch format example:\n")
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 800], \"tell_user\": \"帮你点一下输入框\"},\n")
            append("    {\"action\": \"type\", \"text\": \"hello\", \"tell_user\": \"帮你输入文字\"},\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 900], \"tell_user\": \"帮你点击提交\"}\n")
            append("  ],\n")
            append("  \"description\": \"输入文本并提交\"\n")
            append("}\n")
            append("\n")
        } else {
            append("#### Single Action Mode ####\n")
            append("You are in SINGLE ACTION mode. Return ONLY ONE action in the actions array.\n")
            append("The actions array MUST contain exactly ONE element.\n")
            append("\n")
            append("Single action format example:\n")
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 800], \"tell_user\": \"帮你点一下\"}\n")
            append("  ],\n")
            append("  \"description\": \"点击按钮\"\n")
            append("}\n")
            append("\n")
        }

        append("#### Sensitive Operations ####\n")
        append("For payment, password, or privacy-related actions, add 'message' field to request user confirmation:\n")
        append("Example: {\"action\": \"click\", \"coordinate\": [500, 800], \"message\": \"确认支付 ¥100\"}\n")
        append("The user will see a confirmation dialog and can choose to confirm or cancel.\n")
        append("\n")

        append("### Latest Action History ###\n")
        if (infoPool.actionHistory.isNotEmpty()) {
            val numActions = minOf(5, infoPool.actionHistory.size)
            val latestActions = infoPool.actionHistory.takeLast(numActions)
            val latestSummaries = infoPool.summaryHistory.takeLast(numActions)
            val latestOutcomes = infoPool.actionOutcomes.takeLast(numActions)
            val latestErrors = infoPool.errorDescriptions.takeLast(numActions)

            latestActions.forEachIndexed { i, act ->
                val outcome = latestOutcomes.getOrNull(i) ?: "?"
                if (outcome == "A") {
                    append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Successful\n")
                } else {
                    append(
                        "- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Failed | Error: ${
                            latestErrors.getOrNull(
                                i
                            )
                        }\n"
                    )
                }
            }
        } else {
            append("No actions have been taken yet.\n")
        }
        append("\n")

        append("---\n")
        append("IMPORTANT:\n")
        append("1. Do NOT repeat previously failed actions. Try a different approach.\n")
        append("2. Prioritize the current subgoal.\n")
        append("3. Always analyze the screen BEFORE deciding on an action.\n")
        append("4. CAPTCHA/VERIFICATION: When you see any verification code, captcha, or slider verification, you MUST use the `take_over` action to request user assistance. Never try to solve it yourself.\n")
        append("5. NO REPEATED ATTEMPTS: For the same step and same operation, do not try the same action multiple times. If it fails once, try a different approach or escalate.\n\n")

        append("Provide your output in the following format:\n\n")
        append("### Thought ###\n")
        append("1. **Screen**: What app/page is currently shown? Is it the expected page for the current subgoal?When it's not possible to determine with 100% certainty based on the page content, the judgment must be made based on the historical operation steps.\n")
        append("2. **Blocker**: Any popup, dialog, keyboard, or loading state that needs to be handled first?\n")
        append("3. **Target**: Is the target element visible? If not, should I scroll or navigate?\n")
        append("4. **Decision**: Based on the above analysis, what action should I take and why?\n\n")

        append("### Action ###\n")
        append("Return a JSON with an \"actions\" array containing your action(s):\n\n")

        if (enableBatchExecution) {
            append("**Multiple actions** (when multiple independent actions can be done together):\n")
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 800], \"tell_user\": \"帮你点一下输入框\"},\n")
            append("    {\"action\": \"type\", \"text\": \"hello\", \"tell_user\": \"帮你输入文字\"},\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 900], \"tell_user\": \"帮你点击提交\"}\n")
            append("  ],\n")
            append("  \"description\": \"输入文本并提交\"\n")
            append("}\n\n")
            append("IMPORTANT: Use multiple actions when possible to improve efficiency!\n\n")
        } else {
            append("**Single action only** (MUST contain exactly ONE action):\n")
            append("{\n")
            append("  \"actions\": [\n")
            append("    {\"action\": \"click\", \"coordinate\": [500, 800], \"tell_user\": \"帮你点一下\"}\n")
            append("  ],\n")
            append("  \"description\": \"点击按钮\"\n")
            append("}\n\n")
            append("IMPORTANT: The actions array MUST contain exactly ONE element!\n\n")
        }

        append("### Description ###\n")
        append("A brief description of the chosen action(s).\n")
    }

    /**
     * 解析执行响应
     * 支持两种格式:
     * 1. 标准格式 (### Thought / ### Action / ### Description)
     * 2. MAI-UI 格式 (<thinking>...</thinking><tool_call>...</tool_call>)
     */
    fun parseResponse(response: String, enableBatchExecution: Boolean = true): ExecutorResult {
        // 检测是否为 MAI-UI 格式
        if (response.contains("<tool_call>") || response.contains("<thinking>")) {
            return parseMAIUIResponse(response)
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
            .replace("###", "")
            .trim()

        val action = Action.Companion.fromJson(actionStr)

        // 仅在启用批量执行模式时检测批量格式
        if (enableBatchExecution && ActionBatch.Companion.isBatchFormat(actionStr)) {
            return parseBatchResponse(thought, actionStr, description)
        }

        // 如果禁用批量模式，将单个 Action 包装为批量格式（统一处理逻辑）
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
                actionBatch = wrappedBatch
            )
        }

        // 降级处理：解析失败时，创建一个包含 invalid action 的 batch
        val fallbackBatch = ActionBatch(
            actions = listOf(Action(type = "invalid")),
            description = description,
            thought = thought
        )
        return ExecutorResult(
            thought = thought,
            actionStr = actionStr,
            description = description,
            actionBatch = fallbackBatch
        )
    }

    /**
     * 解析批量 Action 响应
     */
    private fun parseBatchResponse(
        thought: String,
        actionStr: String,
        description: String
    ): ExecutorResult {
        val actionBatch = ActionBatch.Companion.fromJson(actionStr, thought)

        if (actionBatch == null || actionBatch.actions.isEmpty()) {
            // 解析失败，创建包含 invalid action 的 batch
            val fallbackBatch = ActionBatch(
                actions = listOf(Action(type = "invalid")),
                description = description,
                thought = thought
            )
            return ExecutorResult(
                thought = thought,
                actionStr = actionStr,
                description = description,
                actionBatch = fallbackBatch
            )
        }

        val batchDescription = actionBatch.description.ifEmpty { description }

        return ExecutorResult(
            thought = thought,
            actionStr = actionStr,
            description = batchDescription,
            actionBatch = actionBatch
        )
    }

    /**
     * 解析 MAI-UI 格式响应
     * 格式: <thinking>...</thinking><tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
     */
    private fun parseMAIUIResponse(response: String): ExecutorResult {
        val thought = Action.Companion.extractThinking(response)
        val action = Action.Companion.fromMAIUIFormat(response)

        // 从 action 生成描述
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

        val actionStr = action?.toJson() ?: ""

        // 将单个 action 包装为 actionBatch
        val actionBatch = if (action != null) {
            ActionBatch(
                actions = listOf(action),
                description = description,
                thought = thought
            )
        } else {
            // 解析失败，创建包含 invalid action 的 batch
            ActionBatch(
                actions = listOf(Action(type = "invalid")),
                description = description,
                thought = thought
            )
        }

        return ExecutorResult(thought, actionStr, description, actionBatch)
    }
}

data class ExecutorResult(
    val thought: String,
    val actionStr: String,
    val description: String,
    val actionBatch: ActionBatch
)
