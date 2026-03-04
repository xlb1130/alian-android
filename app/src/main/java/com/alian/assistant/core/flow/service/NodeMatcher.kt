package com.alian.assistant.core.flow.service

import com.alian.assistant.core.flow.model.*

/**
 * 节点匹配服务
 * 匹配当前屏幕状态与模板中的关键节点
 */
class NodeMatcher {
    
    /**
     * 匹配当前节点
     * @param template 流程模板
     * @param stepIndex 当前步骤索引
     * @param currentPackage 当前应用包名
     * @param currentElements 当前屏幕元素列表
     * @param currentTexts 当前屏幕文本列表
     * @return 匹配结果
     */
    suspend fun matchCurrentNode(
        template: FlowTemplate,
        stepIndex: Int,
        currentPackage: String,
        currentElements: List<ElementSignature>,
        currentTexts: List<String> = emptyList()
    ): MatchResult {
        // 获取当前步骤
        val step = template.steps.getOrNull(stepIndex) 
            ?: return MatchResult(false, 0f)
        
        // 快速检查：包名匹配
        if (template.targetApp.packageName != currentPackage) {
            return MatchResult(false, 0f)
        }
        
        // 根据节点类型进行不同级别的验证
        return when (step.nodeType) {
            NodeType.ENTRY -> {
                // 入口节点：检查是否在应用首页
                checkEntryNode(template, currentElements, currentTexts)
            }
            NodeType.KEY_OPERATION -> {
                // 关键操作节点：需要截图验证，返回不确定
                MatchResult(false, 0.5f)
            }
            NodeType.BRANCH -> {
                // 分支节点：检查分支条件
                checkBranchNode(template, step, currentElements, currentTexts)
            }
            NodeType.INTERMEDIATE -> {
                // 中间节点：假设匹配，跳过验证
                MatchResult(true, 0.9f)
            }
            NodeType.RECOVERY -> {
                // 恢复节点：检查恢复条件
                checkRecoveryNode(template, currentElements, currentTexts)
            }
            NodeType.COMPLETION -> {
                // 完成节点：检查完成标志
                checkCompletionNode(template, currentElements, currentTexts)
            }
        }
    }
    
    /**
     * 检查入口节点
     */
    private fun checkEntryNode(
        template: FlowTemplate,
        elements: List<ElementSignature>,
        texts: List<String>
    ): MatchResult {
        val entryNode = template.keyNodes.find { it.name == "ENTRY" }
            ?: return MatchResult(true, 0.8f)
        
        return entryNode.screenSignature.match(texts, elements)
    }
    
    /**
     * 检查分支节点
     */
    private fun checkBranchNode(
        template: FlowTemplate,
        step: FlowStep,
        elements: List<ElementSignature>,
        texts: List<String>
    ): MatchResult {
        // 查找对应的关键节点
        val keyNode = template.keyNodes.find { it.stepIndex == step.order }
            ?: return MatchResult(true, 0.7f)
        
        return keyNode.screenSignature.match(texts, elements)
    }
    
    /**
     * 检查恢复节点
     */
    private fun checkRecoveryNode(
        template: FlowTemplate,
        elements: List<ElementSignature>,
        texts: List<String>
    ): MatchResult {
        // 检查是否有错误提示或恢复选项
        val recoveryKeywords = listOf("错误", "失败", "重试", "返回", "取消", "关闭")
        val hasRecoveryOption = texts.any { text ->
            recoveryKeywords.any { keyword ->
                text.contains(keyword, ignoreCase = true)
            }
        }
        
        return MatchResult(hasRecoveryOption, if (hasRecoveryOption) 0.8f else 0.4f)
    }
    
    /**
     * 检查完成节点
     */
    private fun checkCompletionNode(
        template: FlowTemplate,
        elements: List<ElementSignature>,
        texts: List<String>
    ): MatchResult {
        // 先检查预定义的完成节点
        val completionNode = template.keyNodes.find { it.name == "COMPLETION" }
        if (completionNode != null) {
            val result = completionNode.screenSignature.match(texts, elements)
            if (result.matched) {
                return result
            }
        }
        
        // 检查常见的完成标志
        val completionKeywords = listOf("完成", "成功", "订单", "确定", "已提交", "已发送", "恭喜")
        val hasCompletionText = texts.any { text ->
            completionKeywords.any { keyword ->
                text.contains(keyword, ignoreCase = true)
            }
        }
        
        return MatchResult(hasCompletionText, if (hasCompletionText) 0.85f else 0.5f)
    }
    
    /**
     * 快速匹配（仅检查包名和关键文本）
     */
    fun quickMatch(
        template: FlowTemplate,
        currentPackage: String?,
        currentTexts: List<String>
    ): MatchResult {
        // 包名检查
        if (template.targetApp.packageName != currentPackage) {
            return MatchResult(false, 0f)
        }
        
        // 入口节点快速检查
        val entryNode = template.keyNodes.find { it.name == "ENTRY" }
        if (entryNode != null) {
            return entryNode.screenSignature.quickMatch(currentPackage, currentTexts)
        }
        
        return MatchResult(true, 0.6f)
    }
    
    /**
     * 检查是否在目标应用内
     */
    fun isInTargetApp(template: FlowTemplate, currentPackage: String): Boolean {
        return template.targetApp.packageName == currentPackage
    }
    
    /**
     * 从无障碍节点提取元素签名
     */
    fun extractElementSignatures(accessibilityNodes: List<Map<String, Any?>>): List<ElementSignature> {
        return accessibilityNodes.mapNotNull { node ->
            val resourceId = node["resourceId"] as? String
            val text = node["text"] as? String
            val contentDesc = node["contentDescription"] as? String
            val className = node["className"] as? String
            
            if (resourceId != null || text != null || contentDesc != null) {
                ElementSignature(
                    resourceId = resourceId,
                    text = text,
                    contentDesc = contentDesc,
                    className = className
                )
            } else {
                null
            }
        }
    }
    
    /**
     * 从无障碍节点提取文本列表
     */
    fun extractTexts(accessibilityNodes: List<Map<String, Any?>>): List<String> {
        return accessibilityNodes.mapNotNull { node ->
            (node["text"] as? String)?.takeIf { it.isNotBlank() }
        }.distinct()
    }
}
