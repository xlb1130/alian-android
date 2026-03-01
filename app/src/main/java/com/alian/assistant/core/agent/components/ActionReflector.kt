package com.alian.assistant.core.agent.components

import com.alian.assistant.core.agent.memory.InfoPool

/**
 * ActionReflector Agent - 反思动作是否成功
 */
class ActionReflector {

    /**
     * 生成反思 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String = buildString {
        append("You are an agent verifying whether the last action produced the expected behavior.\n\n")

        append("### User Request ###\n")
        append("${infoPool.instruction}\n\n")

        append("### Progress Status ###\n")
        if (infoPool.completedPlan.isNotEmpty()) {
            append("${infoPool.completedPlan}\n\n")
        } else {
            append("No progress yet.\n\n")
        }

        append("---\n")
        append("The two attached images are phone screenshots taken BEFORE and AFTER your last action.\n\n")

        append("### Latest Action ###\n")
        append("Action: ${infoPool.lastAction}\n")
        append("Expectation: ${infoPool.lastSummary}\n\n")

        append("---\n")
        append("Carefully examine whether the last action produced the expected behavior.\n")
        append("Key principle: If you cannot be 100% certain that the action failed, default to marking it as successful.\n\n")

        append("Note: For swiping to scroll, if the content before and after is exactly the same, ")
        append("the swipe is considered Failed (C) - the page may have reached the bottom.\n\n")

        append("Provide your output in the following format:\n\n")

        append("### Outcome ###\n")
        append("Choose from:\n")
        append("A: Successful. The result meets the expectation.\n")
        append("B: Failed. The action resulted in a wrong page. Need to return to previous state.\n")
        append("C: Failed. The action produced no changes.\n\n")

        append("### Error Description ###\n")
        append("If failed, describe the error. If successful, put \"None\".\n")
    }

    /**
     * 解析反思响应
     */
    fun parseResponse(response: String): ReflectorResult {
        val outcomeSection = response
            .substringAfter("### Outcome", "")
            .substringBefore("### Error Description")
            .replace("###", "")
            .trim()

        val outcome = when {
            outcomeSection.contains("A") -> "A"
            outcomeSection.contains("B") -> "B"
            outcomeSection.contains("C") -> "C"
            else -> "C"
        }

        val errorDescription = response
            .substringAfter("### Error Description", "")
            .replace("###", "")
            .trim()

        return ReflectorResult(outcome, errorDescription)
    }
}

data class ReflectorResult(
    val outcome: String,  // A, B, C
    val errorDescription: String
)
