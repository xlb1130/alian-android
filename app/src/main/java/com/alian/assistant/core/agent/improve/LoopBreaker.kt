package com.alian.assistant.core.agent.improve

import com.alian.assistant.core.agent.memory.Action

/**
 * 循环熔断器：
 * 识别重复动作和无进展循环，避免任务陷入无效探索。
 */
class LoopBreaker(
    private val maxSameActionStreak: Int = 3,
    private val maxNoProgressStreak: Int = 4
) {
    data class Decision(
        val shouldBreak: Boolean,
        val reason: String
    )

    private var noProgressStreak: Int = 0

    fun reset() {
        noProgressStreak = 0
    }

    fun evaluate(
        currentAction: Action,
        verification: ReflectorImprove.VerificationResult,
        actionHistory: List<Action>,
        currentStep: Int,
        maxSteps: Int
    ): Decision {
        if (currentStep >= maxSteps) {
            return Decision(true, "loop_max_steps_$currentStep")
        }

        if (verification.outcome == "C") {
            noProgressStreak++
        } else {
            noProgressStreak = 0
        }
        if (noProgressStreak >= maxNoProgressStreak) {
            return Decision(true, "loop_no_progress_$noProgressStreak")
        }

        val compactHistory = (actionHistory + currentAction).takeLast(maxSameActionStreak)
        if (compactHistory.size >= maxSameActionStreak &&
            compactHistory.distinctBy { semanticKey(it) }.size == 1
        ) {
            return Decision(true, "loop_same_action_${compactHistory.size}")
        }

        return Decision(false, "ok")
    }

    private fun semanticKey(action: Action): String {
        return listOf(
            action.type,
            action.targetText ?: "",
            action.targetDesc ?: "",
            action.targetResourceId ?: "",
            action.button ?: "",
            action.x?.toString() ?: "",
            action.y?.toString() ?: ""
        ).joinToString("|")
    }
}

