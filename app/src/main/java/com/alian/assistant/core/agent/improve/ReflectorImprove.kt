package com.alian.assistant.core.agent.improve

import android.graphics.Bitmap
import android.util.Log
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.ai.llm.VLMClient

/**
 * Reflector 改进版本 - 精简 Prompt + 工程化验证
 * 
 * 优化点：
 * 1. 精简 Prompt（减少 50% token）
 * 2. 工程化验证：先进行低成本验证，失败后再调用大模型
 * 3. 支持多种动作类型的工程化验证
 * 4. 统一的 verify 方法，封装所有验证逻辑
 */
class ReflectorImprove(
    private val controller: IDeviceController? = null
) {
    companion object {
        private const val TAG = "ReflectorImprove"
        private const val CONFIDENCE_THRESHOLD = 0.8f
        private const val MAX_CONSECUTIVE_REFLECTOR_SKIPS = 3
    }

    /**
     * 验证结果
     */
    data class VerificationResult(
        val outcome: String,           // A=成功, B=错误页面, C=无变化, D=假设成功
        val errorDescription: String,  // 错误描述
        val verified: Boolean,         // 是否进行了验证（true=已验证，false=假设成功）
        val method: String             // 验证方法（engineered/vlm/assumed）
    )

    /**
     * 工程化验证结果
     */
    data class EngineeredVerifyResult(
        val verified: Boolean,           // 是否已验证
        val outcome: String?,            // 验证结果 (A/B/C)，如果 verified=false 则为 null
        val errorDescription: String?    // 错误描述
    )

    /**
     * 统一验证方法
     * 
     * 封装所有验证逻辑：
     * 1. 判断是否需要验证
     * 2. 如果需要验证，先进行工程化验证
     * 3. 如果工程化验证失败，调用大模型验证
     * 4. 如果不需要验证，返回假设成功的结果
     * 
     * @param action 要验证的动作
     * @param executorResult Executor 结果
     * @param infoPool 信息池
     * @param vlmClient VLM 客户端
     * @param beforeScreenshot 动作前截图
     * @param afterScreenshot 动作后截图
     * @param systemPrompt 系统 Prompt
     * @return 验证结果
     */
    suspend fun verify(
        action: Action,
        executorResult: ExecutorResult,
        infoPool: InfoPoolImprove,
        vlmClient: VLMClient?,
        beforeScreenshot: Bitmap,
        afterScreenshot: Bitmap,
        systemPrompt: String
    ): VerificationResult {
        // 1. 判断是否需要验证
        val shouldVerify = shouldVerify(action, executorResult, infoPool)
        
        if (!shouldVerify) {
            Log.d(TAG, "跳过验证，假设成功")
            return VerificationResult(
                outcome = "D",
                errorDescription = "None (assumed)",
                verified = false,
                method = "assumed"
            )
        }

        Log.d(TAG, "开始验证...")

        // 2. 先进行工程化验证（低成本）
        val engineeredResult = engineeredVerify(action, infoPool)
        
        if (engineeredResult != null && engineeredResult.verified) {
            // 工程化验证成功，直接使用结果
            Log.d(TAG, "✓ 工程化验证成功: ${engineeredResult.outcome} - ${engineeredResult.errorDescription}")
            return VerificationResult(
                outcome = engineeredResult.outcome!!,
                errorDescription = engineeredResult.errorDescription ?: "Engineered verification passed",
                verified = true,
                method = "engineered"
            )
        }

        // 3. 工程化验证失败或无法验证，调用大模型进行视觉验证
        if (vlmClient == null) {
            Log.w(TAG, "VLMClient 为 null，无法进行大模型验证")
            return VerificationResult(
                outcome = "C",
                errorDescription = "VLMClient not available",
                verified = false,
                method = "failed"
            )
        }

        Log.d(TAG, "工程化验证失败或无法验证，调用大模型验证...")
        val reflectPrompt = getPrompt(infoPool)
        Log.d(TAG, "Reflector 提示词: $reflectPrompt")
        Log.d(TAG, "Reflector 估算 token: ${reflectPrompt.length / 3}")
        
        val reflectResponse = vlmClient.predict(reflectPrompt, listOf(beforeScreenshot, afterScreenshot), systemPrompt)
        Log.d(TAG, "Reflector 响应: $reflectResponse")
        
        val reflectResult = if (reflectResponse.isSuccess) {
            parseResponse(reflectResponse.getOrThrow())
        } else {
            ReflectorResult("C", "Failed to call reflector")
        }

        Log.d(TAG, "大模型验证结果: ${reflectResult.outcome} - ${reflectResult.errorDescription.take(50)}")

        return VerificationResult(
            outcome = reflectResult.outcome,
            errorDescription = reflectResult.errorDescription,
            verified = true,
            method = "vlm"
        )
    }

    /**
     * 判断是否需要验证
     */
    private fun shouldVerify(
        action: Action,
        executorResult: ExecutorResult,
        infoPool: InfoPoolImprove
    ): Boolean {
        // 1. 强制验证：如果设置了强制验证标志
        if (infoPool.forceReflector) {
            Log.d(TAG, "强制验证")
            infoPool.forceReflector = false
            return true
        }

        // 2. 置信度阈值：低于阈值必须验证
        if (executorResult.confidence < CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "置信度低 (${executorResult.confidence})，需要验证")
            return true
        }

        // 3. 动作类型：某些动作类型必须验证
        val mustVerifyActions = listOf("swipe", "type", "open_app", "take_over")
        if (action.type in mustVerifyActions) {
            Log.d(TAG, "动作类型 ${action.type} 需要验证")
            return true
        }

        // 4. 历史失败：同类动作历史失败率高
        val similarActions = infoPool.actionHistory
            .filter { it.type == action.type }
            .takeLast(5)

        if (similarActions.size >= 3) {
            val similarOutcomes = infoPool.actionOutcomes.takeLast(similarActions.size)
            val failureRate = similarOutcomes.count { it in listOf("B", "C") } / similarActions.size.toFloat()

            if (failureRate > 0.3f) {
                Log.d(TAG, "同类动作历史失败率高 ($failureRate)，需要验证")
                return true
            }
        }

        // 5. 敏感操作：敏感操作必须验证
        if (action.needConfirm || action.message != null) {
            Log.d(TAG, "敏感操作需要验证")
            return true
        }

        // 6. 批量动作：批量动作需要验证
        if (executorResult.actionBatch.actions.size > 1) {
            Log.d(TAG, "批量动作需要验证")
            return true
        }

        // 7. 连续成功假设：连续 N 次跳过后强制验证一次
        if (infoPool.consecutiveReflectorSkips >= MAX_CONSECUTIVE_REFLECTOR_SKIPS) {
            Log.d(TAG, "连续 ${infoPool.consecutiveReflectorSkips} 次跳过，强制验证")
            return true
        }

        // 其他情况跳过验证
        return false
    }

    /**
     * 工程化验证（低成本验证）
     * 
     * 先进行工程化验证，如果成功则直接返回结果，不需要调用大模型
     * 如果失败或无法验证，返回 null，需要调用大模型进行视觉验证
     * 
     * @param action 要验证的动作
     * @param infoPool 信息池
     * @return 验证结果，如果无法验证则返回 null
     */
    private fun engineeredVerify(
        action: Action,
        infoPool: InfoPoolImprove
    ): EngineeredVerifyResult? {
        when (action.type) {
            "open_app" -> return verifyOpenApp(action, infoPool)
            // 未来可以添加其他动作类型的工程化验证
            // "click" -> return verifyClick(action, infoPool)
            // "type" -> return verifyType(action, infoPool)
            else -> return null  // 不支持工程化验证，需要调用大模型
        }
    }

    /**
     * 验证 open_app 动作
     * 
     * 通过检查当前前台应用包名是否匹配目标应用包名来验证
     */
    private fun verifyOpenApp(
        action: Action,
        infoPool: InfoPoolImprove
    ): EngineeredVerifyResult {
        if (controller == null) {
            Log.w(TAG, "Controller 为 null，无法进行 open_app 工程化验证")
            return EngineeredVerifyResult(false, null, null)
        }

        val appName = action.text
        if (appName.isNullOrEmpty()) {
            Log.w(TAG, "open_app 动作缺少应用名称")
            return EngineeredVerifyResult(false, null, null)
        }

        // 获取当前前台应用包名
        val currentPackage = controller.getCurrentPackage()
        if (currentPackage == null) {
            Log.w(TAG, "无法获取当前应用包名，跳过工程化验证")
            return EngineeredVerifyResult(false, null, null)
        }

        Log.d(TAG, "当前前台应用包名: $currentPackage")

        // 获取目标包名：从已安装应用列表中查找匹配的应用
        val installedAppsList = infoPool.installedApps.split(", ")
        Log.d(TAG, "installedApps 内容: ${infoPool.installedApps.take(200)}")
        Log.d(TAG, "installedAppsList 大小: ${installedAppsList.size}")
        Log.d(TAG, "查找目标应用: '$appName' (lowercase: '${appName.lowercase()}')")
        
        val targetAppEntry = installedAppsList.find { entry ->
            val entryLower = entry.lowercase()
            val appNameLower = appName.lowercase()
            val containsApp = entryLower.contains(appNameLower)
            val containsEntry = appNameLower.contains(entryLower)
            val match = containsApp || containsEntry
            if (entryLower.contains("meituan") || entryLower.contains("美团")) {
                Log.d(TAG, "  检查条目: '$entry' -> containsApp=$containsApp, containsEntry=$containsEntry, match=$match")
            }
            match
        }

        // 提取包名（格式通常是 "应用名 (包名)" 或直接是包名）
        val targetPackage = targetAppEntry?.let { entry ->
            val match = Regex("\\(([^)]+)\\)").find(entry)
            match?.groupValues?.get(1) ?: entry
        } ?: appName

        Log.d(TAG, "open_app 工程化验证: 目标应用=$appName, 目标包名=$targetPackage, 匹配条目=$targetAppEntry")

        // 验证是否匹配
        // 1. 直接比较包名
        // 2. 检查当前包名是否匹配目标应用名称（如果无法获取包名）
        val isMatch = currentPackage == targetPackage || 
                     currentPackage.contains(targetPackage) || 
                     targetPackage.contains(currentPackage) ||
                     targetAppEntry?.lowercase()?.contains(currentPackage.lowercase()) == true

        return if (isMatch) {
            Log.d(TAG, "✓ open_app 工程化验证成功: $currentPackage 匹配目标应用")
            EngineeredVerifyResult(
                verified = true,
                outcome = "A",
                errorDescription = "成功打开应用: $appName ($currentPackage)"
            )
        } else {
            Log.w(TAG, "✗ open_app 工程化验证失败: $currentPackage 不匹配目标应用 $targetPackage")
            EngineeredVerifyResult(
                verified = true,
                outcome = "B",
                errorDescription = "打开应用失败: 目标应用 $targetPackage，当前应用 $currentPackage"
            )
        }
    }

    /**
     * 生成反思 Prompt（改进版 - 增强验证指导）
     */
    fun getPrompt(infoPool: InfoPoolImprove): String = buildString {
        append("### Verification Task ###\n")
        append("Compare BEFORE and AFTER screenshots to verify action result.\n\n")
        
        append("### Context ###\n")
        append("Task: ${infoPool.instruction}\n")
        append("Completed: ${infoPool.completedPlan}\n\n")
        
        append("### Last Action ###\n")
        append("Action: ${formatAction(infoPool.lastAction)}\n")
        append("Expected: ${infoPool.lastSummary}\n\n")
        
        append("### Verification Rules ###\n")
        append("A = Success: Screen changed as expected\n")
        append("B = Wrong page: Navigated to unexpected screen (popup, error, different app)\n")
        append("C = No change: Screen looks identical (action may have failed)\n\n")
        
        append("### Special Cases ###\n")
        append("- type action: Check if text appeared in the INPUT BOX area (the actual field), NOT in keyboard suggestions/history. Text in keyboard suggestions DOES NOT count as input.\n")
        append("- swipe action: C is acceptable if already at boundary\n")
        append("- click action: Look for visual feedback (highlight, new content)\n\n")
        
        append("### Output ###\n")
        append("### Outcome ###\n[A/B/C]\n\n")
        append("### Error ###\n[None or brief description of what went wrong]\n")
    }

    /**
     * 格式化动作显示
     */
    private fun formatAction(action: Action?): String {
        if (action == null) return "Unknown"
        return buildString {
            append(action.type)
            when (action.type) {
                "click" -> append(" [${action.x}, ${action.y}]")
                "type" -> append(" [${action.text?.take(20)}]")
                "swipe" -> append(" [${action.x}, ${action.y} → ${action.x2}, ${action.y2}]")
                "open_app" -> append(" [${action.text}]")
                "system_button" -> append(" [${action.button}]")
                else -> {}
            }
        }
    }

    /**
     * 解析反思响应
     */
    fun parseResponse(response: String): ReflectorResult {
        val outcomeSection = response
            .substringAfter("### Outcome", "")
            .substringBefore("### Error")
            .replace("###", "")
            .trim()

        val outcome = when {
            outcomeSection.contains("A") -> "A"
            outcomeSection.contains("B") -> "B"
            outcomeSection.contains("C") -> "C"
            else -> "C"
        }

        val errorDescription = response
            .substringAfter("### Error", "")
            .replace("###", "")
            .trim()

        return ReflectorResult(outcome, errorDescription)
    }
}

data class ReflectorResult(
    val outcome: String,  // A, B, C
    val errorDescription: String
)