                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            package com.alian.assistant.core.agent.improve

/**
 * Manager 改进版本 - 支持条件性调用和精简 Prompt
 * 
 * 优化点：
 * 1. 精简 Prompt（减少 60% token）
 * 2. 支持条件性调用（只在关键时刻介入）
 * 3. 子目标完成检测
 * 4. 支持重规划（NEW_INTENT）
 */
class ManagerImprove {

    /**
     * 生成规划 Prompt
     * 根据模式选择不同的 Prompt
     */
    fun getPrompt(infoPool: InfoPoolImprove): String {
        return when (infoPool.managerMode) {
            ManagerMode.INITIAL -> getInitialPlanPrompt(infoPool)
            ManagerMode.RECOVERY -> getRecoveryPlanPrompt(infoPool)
            ManagerMode.REPLANNING -> getReplanningPrompt(infoPool)
            ManagerMode.NORMAL -> getUpdatePlanPrompt(infoPool)
        }
    }

    /**
     * 生成重规划 Prompt（用于 NEW_INTENT）
     * 
     * @param infoPool InfoPoolImprove 实例
     * @param newIntent 用户新意图
     * @param preservedHistory 保留的历史记录（语义摘要）
     * @return Prompt 字符串
     */
    fun getReplanPrompt(
        infoPool: InfoPoolImprove,
        newIntent: String,
        preservedHistory: List<String>
    ): String = buildString {
        append("REPLAN WITH NEW INTENT\n\n")
        
        append("### Original Task ###\n")
        append("${infoPool.instruction}\n\n")
        
        append("### New Intent ###\n")
        append("$newIntent\n\n")
        
        append("### Current State ###\n")
        append("Done: ${infoPool.completedPlan}\n")
        append("Plan: ${infoPool.plan}\n")
        append("Last Action: ${infoPool.lastAction}\n\n")
        
        append("### Preserved History (Semantic Summary) ###\n")
        if (preservedHistory.isNotEmpty()) {
            preservedHistory.forEach { summary ->
                append("- $summary\n")
            }
        } else {
            append("No history available.\n")
        }
        append("\n")
        
        append("### Instructions ###\n")
        append("- Adjust the plan to incorporate the new intent\n")
        append("- Preserve the context from preserved history\n")
        append("- If the new intent is completely different, create a new plan\n")
        append("- If the new intent is a minor adjustment, update the current plan\n")
        append("- Keep track of what has been completed vs what remains\n\n")
        
        append("### Thought ###\n[reasoning about how to adjust the plan]\n\n")
        append("### Plan ###\n[adjusted plan or new plan]\n\n")
        append("### Tell User ###\n[<15 chars]")
    }

    /**
     * 初始规划 Prompt（改进版 - 添加屏幕尺寸和更清晰的示例）
     */
    private fun getInitialPlanPrompt(infoPool: InfoPoolImprove): String = buildString {
        append("You are planning actions for an Android phone (${infoPool.screenWidth}x${infoPool.screenHeight}).\n\n")
        append("Task: ${infoPool.instruction}\n\n")
        
        if (infoPool.skillContext.isNotEmpty()) {
            append("Available Skills: ${infoPool.skillContext}\n\n")
        }
        
        append("Rules:\n")
        append("- FIRST STEP must be: \"Open [app_name]\" or \"Return to home screen\"\n")
        append("- Use open_app action, NOT app drawer\n")
        append("- When opening apps, use open_app tool instead of clicking desktop icons\n")
        append("- Add 'answer' step for questions requiring response\n")
        append("- Each subgoal should be atomic and verifiable\n")
        if (infoPool.additionalKnowledge.isNotEmpty()) {
            append("- ${infoPool.additionalKnowledge}\n")
        }
        append("\n")
        
        append("### Thought ###\n[Analyze the task and break it into steps]\n\n")
        append("### Plan ###\n1. Open [target app]\n2. [specific action]\n3. ...\n\n")
        append("### Tell User ###\n[Brief status in Chinese, ≤10 chars]\n")
    }

    /**
     * 更新计划 Prompt（极致精简版）
     */
    private fun getUpdatePlanPrompt(infoPool: InfoPoolImprove): String = buildString {
        append("Update: ${infoPool.instruction}\n\n")
        append("Done: ${infoPool.completedPlan}\n")
        append("Plan: ${infoPool.plan}\n")
        append("Last: ${infoPool.lastAction}\n\n")
        
        // 只在有失败时提示
        if (infoPool.errorFlagPlan) {
            append("STUCK! Try: wait/scroll/back\n\n")
        }
        
        // 只在有失败时显示
        val recentFailures = infoPool.actionOutcomes.takeLast(20).filter { it in listOf("B", "C") }
        if (recentFailures.isNotEmpty()) {
            append("Fails: ${recentFailures.size}. New approach needed.\n\n")
        }
        
        append("Stop: STOP_SENSITIVE (pay/passwd/faceID) | Finished (done+success)\n\n")
        append("### Thought ###\n[reasoning]\n\n")
        append("### Done ###\n[new completed subgoals]\n\n")
        append("### Plan ###\n[update or Finished]\n\n")
        append("### Tell User ###\n[<15 chars]\n")
    }

    /**
     * 错误恢复 Prompt（改进版 - 添加失败分析和恢复策略指导）
     */
    private fun getRecoveryPlanPrompt(infoPool: InfoPoolImprove): String = buildString {
        append("RECOVERY MODE - Task stuck\n\n")
        append("Task: ${infoPool.instruction}\n")
        append("Current Plan: ${infoPool.plan}\n\n")
        
        val k = infoPool.errToManagerThresh
        val recentActions = infoPool.actionHistory.takeLast(k)
        val recentOutcomes = infoPool.actionOutcomes.takeLast(k)
        val recentErrors = infoPool.errorDescriptions.takeLast(k)
        
        append("### Failed Actions ###\n")
        recentActions.forEachIndexed { i, act ->
            val outcome = recentOutcomes.getOrNull(i) ?: "?"
            val error = recentErrors.getOrNull(i) ?: ""
            append("- ${act.type}: Outcome=$outcome")
            if (error.isNotEmpty()) append(" | Error: $error")
            append("\n")
        }
        append("\n")
        
        append("### Recovery Strategies by Failure Type ###\n")
        append("- Outcome B (Wrong page): Use 'back' or 'home', then retry navigation\n")
        append("- Outcome C (No change): Try different coordinates, scroll to reveal, or wait for loading\n")
        append("- Repeated failures: Consider alternative approach or different app path\n\n")
        
        append("### Thought ###\n[Analyze WHY the actions failed and propose new strategy]\n\n")
        append("### Plan ###\n[Revised plan with different approach]\n\n")
        append("### Tell User ###\n[≤15 chars]\n")
    }

    /**
     * 重新规划 Prompt（极致精简版）
     */
    private fun getReplanningPrompt(infoPool: InfoPoolImprove): String = buildString {
        append("REPLAN: ${infoPool.instruction}\n\n")
        append("Done: ${infoPool.completedPlan}\n")
        append("Plan: ${infoPool.plan}\n")
        append("Last: ${infoPool.lastAction}\n\n")
        
        append("### Thought ###\n[adjust plan for current state]\n\n")
        append("### Plan ###\n[adjusted plan]\n\n")
        append("### Tell User ###\n[<15 chars]\n")
    }

    /**
     * 解析响应
     */
    fun parseResponse(response: String): PlanResult {
        val thought = response
            .substringAfter("### Thought", "")
            .substringBefore("### Historical Operations")
            .substringBefore("### Plan")
            .replace("###", "")
            .trim()

        val completedSubgoal = if (response.contains("### Historical Operations")) {
            response
                .substringAfter("### Historical Operations")
                .substringBefore("### Plan")
                .replace("###", "")
                .trim()
        } else {
            "No completed subgoal."
        }

        val plan = response
            .substringAfter("### Plan")
            .substringBefore("### Tell User")
            .replace("###", "")
            .trim()

        val tellUser = response
            .substringAfter("### Tell User")
            .replace("###", "")
            .trim()

        return PlanResult(thought, completedSubgoal, plan, tellUser)
    }
}

data class PlanResult(
    val thought: String,
    val completedSubgoal: String,
    val plan: String,
    val tellUser: String = ""
)