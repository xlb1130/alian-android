package com.alian.assistant.infrastructure.device.controller.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 无障碍元素定位器
 *
 * 功能：
 * 1. 封装无障碍元素查找逻辑
 * 2. 支持多种查找策略（文本、描述、资源ID、坐标区域）
 * 3. 返回元素中心坐标或直接执行操作
 */
class AccessibilityElementLocator {
    companion object {
        private const val TAG = "AccessibilityElementLocator"
        private const val DEFAULT_SEARCH_RADIUS = 100  // 默认搜索半径（像素）
    }

    /**
     * 元素定位结果
     */
    data class ElementLocateResult(
        val success: Boolean,
        val node: AccessibilityNodeInfo? = null,
        val centerX: Int = 0,
        val centerY: Int = 0,
        val method: String = ""  // 定位方式：text/resourceId/contentDesc/bounds
    )

    /**
     * 元素状态验证结果
     */
    data class ElementValidationResult(
        val isValid: Boolean,
        val isClickable: Boolean,
        val isVisible: Boolean,
        val isEnabled: Boolean,
        val isFocusable: Boolean,
        val bounds: Rect,
        val errorMessage: String = ""
    )

    /**
     * 获取无障碍服务实例
     */
    private fun getService(): AlianAccessibilityService? {
        return AlianAccessibilityService.getInstance()
    }

    private fun a11y(message: String): String = "[A11Y] $message"

    /**
     * 检查无障碍服务是否可用
     */
    fun isServiceAvailable(): Boolean {
        return AlianAccessibilityService.isConnected()
    }

    /**
     * 通过文本定位元素
     *
     * @param text 文本内容
     * @param exactMatch 是否精确匹配（默认 false，包含即可）
     * @return 定位结果
     */
    fun locateByText(text: String, exactMatch: Boolean = false): ElementLocateResult {
        val service = getService() ?: return ElementLocateResult(success = false)

        val nodes = service.findByText(text, exactMatch)
        if (nodes.isEmpty()) {
            Log.d(TAG, a11y("通过文本定位失败: '$text'"))
            return ElementLocateResult(success = false)
        }

        // 从所有候选中打分选择最优节点（避免重复文本命中错误元素）
        val node = selectBestCandidate(
            candidates = nodes,
            targetText = text,
            exactMatch = exactMatch
        ) ?: nodes.first()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        // 验证元素是否真的匹配目标文本
        val nodeText = node.text?.toString() ?: ""
        val isMatch = if (exactMatch) {
            nodeText == text
        } else {
            nodeText.contains(text, ignoreCase = true)
        }

        Log.d(TAG, a11y("✓ 通过文本定位成功: '$text' -> ($centerX, $centerY)"))
        return ElementLocateResult(
            success = true,
            node = node,
            centerX = centerX,
            centerY = centerY,
            method = "text"
        )
    }

    /**
     * 通过内容描述定位元素
     *
     * @param description 内容描述
     * @return 定位结果
     */
    fun locateByDescription(description: String): ElementLocateResult {
        val service = getService() ?: return ElementLocateResult(success = false)

        val root = service.rootNode ?: return ElementLocateResult(success = false)
        val candidates = collectNodes(root).filter { node ->
            val nodeDesc = node.contentDescription?.toString() ?: ""
            nodeDesc.contains(description, ignoreCase = true)
        }
        if (candidates.isEmpty()) {
            Log.d(TAG, a11y("通过描述定位失败: '$description'"))
            return ElementLocateResult(success = false)
        }

        val node = selectBestCandidate(
            candidates = candidates,
            targetDesc = description
        ) ?: candidates.first()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        Log.d(TAG, a11y("✓ 通过描述定位成功: '$description' -> ($centerX, $centerY)"))
        return ElementLocateResult(
            success = true,
            node = node,
            centerX = centerX,
            centerY = centerY,
            method = "contentDesc"
        )
    }

    /**
     * 通过资源ID定位元素
     *
     * @param resourceId 资源ID（可以是部分匹配）
     * @return 定位结果
     */
    fun locateByResourceId(resourceId: String): ElementLocateResult {
        val service = getService() ?: return ElementLocateResult(success = false)

        val root = service.rootNode ?: return ElementLocateResult(success = false)
        val candidates = collectNodes(root).filter { node ->
            val nodeId = node.viewIdResourceName ?: ""
            nodeId.contains(resourceId, ignoreCase = true)
        }
        if (candidates.isEmpty()) {
            Log.d(TAG, a11y("通过资源ID定位失败: '$resourceId'"))
            return ElementLocateResult(success = false)
        }

        val node = selectBestCandidate(
            candidates = candidates,
            targetResourceId = resourceId
        ) ?: candidates.first()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        Log.d(TAG, a11y("✓ 通过资源ID定位成功: '$resourceId' -> ($centerX, $centerY)"))
        return ElementLocateResult(
            success = true,
            node = node,
            centerX = centerX,
            centerY = centerY,
            method = "resourceId"
        )
    }

    /**
     * 在坐标附近查找最近的可点击元素
     *
     * @param x 目标X坐标
     * @param y 目标Y坐标
     * @param radius 搜索半径（像素）
     * @return 定位结果
     */
    fun locateNearestClickable(x: Int, y: Int, radius: Int = DEFAULT_SEARCH_RADIUS): ElementLocateResult {
        val service = getService() ?: return ElementLocateResult(success = false)

        // 定义搜索区域
        val searchRect = Rect(
            x - radius,
            y - radius,
            x + radius,
            y + radius
        )

        // 在区域内查找所有元素
        val nodes = service.findByBounds(searchRect)
        if (nodes.isEmpty()) {
            Log.d(TAG, a11y("坐标附近未找到元素: ($x, $y)"))
            return ElementLocateResult(success = false)
        }

        // 筛选可点击元素，并找到距离最近的
        var nearestNode: AccessibilityNodeInfo? = null
        var minDistance = Float.MAX_VALUE

        for (node in nodes) {
            if (node.isClickable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()

                // 计算欧几里得距离
                val distance = sqrt(
                    ((centerX - x) * (centerX - x) + (centerY - y) * (centerY - y)).toFloat()
                )

                if (distance < minDistance) {
                    minDistance = distance
                    nearestNode = node
                }
            }
        }

        if (nearestNode == null) {
            Log.d(TAG, a11y("坐标附近未找到可点击元素: ($x, $y)"))
            return ElementLocateResult(success = false)
        }

        val bounds = Rect()
        nearestNode.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        Log.d(TAG, a11y("✓ 通过坐标定位成功: ($x, $y) -> 最近的元素 ($centerX, $centerY), 距离: ${minDistance.toInt()}"))
        return ElementLocateResult(
            success = true,
            node = nearestNode,
            centerX = centerX,
            centerY = centerY,
            method = "bounds"
        )
    }

    /**
     * 验证元素状态
     *
     * @param node 要验证的元素
     * @return 验证结果
     */
    fun validateElement(node: AccessibilityNodeInfo): ElementValidationResult {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val isVisible = node.isVisibleToUser
        val isEnabled = node.isEnabled
        val isClickable = node.isClickable
        val isFocusable = node.isFocusable

        val validationErrors = mutableListOf<String>()

        if (!isVisible) validationErrors.add("元素不可见")
        if (!isEnabled) validationErrors.add("元素被禁用")
        if (!isClickable) validationErrors.add("元素不可点击")
        if (bounds.isEmpty) validationErrors.add("元素边界无效")

        val isValid = validationErrors.isEmpty()
        val errorMessage = if (validationErrors.isNotEmpty()) {
            validationErrors.joinToString(", ")
        } else ""

        if (!isValid) {
            Log.w(TAG, a11y("元素状态验证失败: ${node.text ?: node.contentDescription}"))
            Log.w(TAG, a11y("  - $errorMessage"))
            Log.w(TAG, a11y("  - 边界: $bounds"))
            Log.w(TAG, a11y("  - 可点击: $isClickable, 可见: $isVisible, 启用: $isEnabled, 可聚焦: $isFocusable"))
        } else {
            Log.d(TAG, a11y("✓ 元素状态验证通过: ${node.text ?: node.contentDescription}"))
        }

        return ElementValidationResult(
            isValid = isValid,
            isClickable = isClickable,
            isVisible = isVisible,
            isEnabled = isEnabled,
            isFocusable = isFocusable,
            bounds = bounds,
            errorMessage = errorMessage
        )
    }

    /**
     * 验证元素是否匹配目标特征
     *
     * @param node 要验证的元素
     * @param targetText 目标文本（可选）
     * @param targetDesc 目标描述（可选）
     * @param targetResourceId 目标资源ID（可选）
     * @return 是否匹配
     */
    fun verifyElementMatch(
        node: AccessibilityNodeInfo,
        targetText: String? = null,
        targetDesc: String? = null,
        targetResourceId: String? = null
    ): Boolean {
        var matchCount = 0
        var totalChecks = 0

        targetText?.let { text ->
            totalChecks++
            val nodeText = node.text?.toString() ?: ""
            if (nodeText.contains(text, ignoreCase = true)) {
                matchCount++
                Log.d(TAG, a11y("✓ 文本匹配: '$nodeText' 包含 '$text'"))
            } else {
                Log.w(TAG, a11y("✗ 文本不匹配: '$nodeText' 不包含 '$text'"))
            }
        }

        targetDesc?.let { desc ->
            totalChecks++
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (nodeDesc.contains(desc, ignoreCase = true)) {
                matchCount++
                Log.d(TAG, a11y("✓ 描述匹配: '$nodeDesc' 包含 '$desc'"))
            } else {
                Log.w(TAG, a11y("✗ 描述不匹配: '$nodeDesc' 不包含 '$desc'"))
            }
        }

        targetResourceId?.let { resourceId ->
            totalChecks++
            val nodeId = node.viewIdResourceName ?: ""
            if (nodeId.contains(resourceId, ignoreCase = true)) {
                matchCount++
                Log.d(TAG, a11y("✓ 资源ID匹配: '$nodeId' 包含 '$resourceId'"))
            } else {
                Log.w(TAG, a11y("✗ 资源ID不匹配: '$nodeId' 不包含 '$resourceId'"))
            }
        }

        // 如果没有任何检查条件，返回 true
        if (totalChecks == 0) return true

        // 至少匹配一项才算成功
        val isMatch = matchCount > 0
        Log.d(TAG, a11y("元素匹配结果: $matchCount/$totalChecks, 是否匹配: $isMatch"))
        return isMatch
    }

    /**
     * 直接点击元素（不用坐标）
     *
     * @param node 要点击的元素
     * @return 是否成功
     */
    fun clickElement(node: AccessibilityNodeInfo): Boolean {
        val service = getService() ?: return false

        val clickTarget = resolveClickableNode(node)
        if (clickTarget == null) {
            Log.w(TAG, a11y("未找到可点击节点（含父级），跳过点击"))
            return false
        }
        if (clickTarget !== node) {
            Log.d(TAG, a11y("目标节点不可点击，已上溯到可点击父节点"))
        }

        // 先验证元素状态
        val validation = validateElement(clickTarget)
        if (!validation.isValid) {
            Log.w(TAG, a11y("元素状态无效，跳过点击: ${validation.errorMessage}"))
            return false
        }

        val result = service.clickNode(clickTarget)
        Log.d(TAG, a11y("点击元素: ${clickTarget.text ?: clickTarget.contentDescription}, 结果: $result"))
        return result
    }

    /**
     * 直接在元素上输入文本
     *
     * @param node 要输入的元素（必须是可编辑的）
     * @param text 要输入的文本
     * @return 是否成功
     */
    fun typeInElement(node: AccessibilityNodeInfo, text: String): Boolean {
        val service = getService() ?: return false
        val TAG = "AccessibilityElementLocator"

        // 直接调用 setText，其内部已包含：
        // 1. 聚焦元素
        // 2. ACTION_SET_TEXT（或备选的选中文本+粘贴方案）
        // 3. 验证结果
        // 避免多次调用导致重复输入
        val result = service.setText(node, text)
        Log.d(TAG, a11y("typeInElement: 设置文本 '$text', 结果: $result"))

        return result
    }

    /**
     * 查找当前焦点的可编辑元素
     *
     * @return 可编辑元素，如果不存在则返回 null
     */
    fun findFocusedEditable(): AccessibilityNodeInfo? {
        val service = getService() ?: return null

        val root = service.rootNode ?: return null

        // 遍历元素树查找焦点元素
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isFocused && node.isEditable) {
                Log.d(TAG, "找到焦点可编辑元素: ${node.text}")
                return node
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "未找到焦点可编辑元素")
        return null
    }

    /**
     * 在坐标附近查找可编辑元素
     *
     * @param x X坐标
     * @param y Y坐标
     * @param radius 搜索半径
     * @return 可编辑元素，如果不存在则返回 null
     */
    fun findNearestEditable(x: Int, y: Int, radius: Int = DEFAULT_SEARCH_RADIUS): AccessibilityNodeInfo? {
        val service = getService() ?: return null

        // 定义搜索区域
        val searchRect = Rect(
            x - radius,
            y - radius,
            x + radius,
            y + radius
        )

        // 在区域内查找所有元素
        val nodes = service.findByBounds(searchRect)
        if (nodes.isEmpty()) {
            return null
        }

        // 筛选可编辑元素，并找到距离最近的
        var nearestNode: AccessibilityNodeInfo? = null
        var minDistance = Float.MAX_VALUE

        for (node in nodes) {
            if (node.isEditable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()

                // 计算欧几里得距离
                val distance = sqrt(
                    ((centerX - x) * (centerX - x) + (centerY - y) * (centerY - y)).toFloat()
                )

                if (distance < minDistance) {
                    minDistance = distance
                    nearestNode = node
                }
            }
        }

        if (nearestNode != null) {
            Log.d(TAG, "找到附近可编辑元素: ${nearestNode.text}, 距离: ${minDistance.toInt()}")
        }

        return nearestNode
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            results.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return results
    }

    private fun selectBestCandidate(
        candidates: List<AccessibilityNodeInfo>,
        targetText: String? = null,
        targetDesc: String? = null,
        targetResourceId: String? = null,
        exactMatch: Boolean = false
    ): AccessibilityNodeInfo? {
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { node ->
            var score = 0
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val nodeText = node.text?.toString().orEmpty()
            val nodeDesc = node.contentDescription?.toString().orEmpty()
            val nodeId = node.viewIdResourceName.orEmpty()

            targetText?.let { target ->
                score += when {
                    exactMatch && nodeText == target -> 300
                    !exactMatch && nodeText.contains(target, ignoreCase = true) -> 220
                    !exactMatch && nodeDesc.contains(target, ignoreCase = true) -> 140
                    else -> 0
                }
            }
            targetDesc?.let { target ->
                score += if (nodeDesc.contains(target, ignoreCase = true)) 240 else 0
            }
            targetResourceId?.let { target ->
                score += if (nodeId.contains(target, ignoreCase = true)) 260 else 0
            }

            if (node.isClickable) score += 120
            if (resolveClickableNode(node) != null) score += 80
            if (node.isVisibleToUser) score += 70
            if (node.isEnabled) score += 50
            if (!bounds.isEmpty) score += 40

            // 偏好更大的点击热区，降低点到细小文本节点的概率
            val area = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)
            score += (area / 8000).coerceAtMost(120)

            // 微调：更偏好在屏幕中部（通常更可能是主内容）
            val centerOffset = abs(bounds.centerX() - 540) + abs(bounds.centerY() - 1200)
            score -= (centerOffset / 120).coerceAtMost(40)

            score
        }
    }

    private fun resolveClickableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }
}