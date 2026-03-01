package com.alian.assistant.core.agent.components

import com.alian.assistant.core.agent.memory.InfoPool

/**
 * Notetaker Agent - 记录重要信息
 */
class Notetaker {

    /**
     * 生成笔记 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String = buildString {
        append("You are a helpful AI assistant for operating mobile phones. ")
        append("Your goal is to take notes of important content relevant to the user's request.\n\n")

        append("### User Request ###\n")
        append("${infoPool.instruction}\n\n")

        append("### Progress Status ###\n")
        append("${infoPool.progressStatus}\n\n")

        append("### Existing Important Notes ###\n")
        if (infoPool.importantNotes.isNotEmpty()) {
            append("${infoPool.importantNotes}\n\n")
        } else {
            append("No important notes recorded.\n\n")
        }

        append("---\n")
        append("Examine the current screen to identify any important content that needs to be recorded.\n\n")

        append("IMPORTANT:\n")
        append("- Do not take notes on low-level actions\n")
        append("- Only keep track of significant textual or visual information relevant to the request\n")
        append("- Do not repeat user request or progress status\n")
        append("- Do not make up content that you are not sure about\n\n")

        append("Provide your output in the following format:\n\n")
        append("### Important Notes ###\n")
        append("The updated important notes, combining old and new ones. ")
        append("If nothing new to record, copy the existing important notes.\n")
    }

    /**
     * 解析笔记响应
     */
    fun parseResponse(response: String): String {
        return response
            .substringAfter("### Important Notes", "")
            .replace("###", "")
            .trim()
    }
}
