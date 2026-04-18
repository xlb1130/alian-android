@file:Suppress("DEPRECATION")

package com.alian.assistant.core.agent.improve

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService

data class UiaNodeLite(
    val text: String?,
    val desc: String?,
    val resourceId: String?,
    val role: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val bounds: Rect,
    val depth: Int
)

data class UiaTreeSnapshot(
    val timestamp: Long,
    val nodeCount: Int,
    val nodes: List<UiaNodeLite>
) {
    fun toPromptSummary(maxLines: Int = 40): String {
        if (nodes.isEmpty()) return ""
        return buildString {
            append("UI Tree Summary (top actionable nodes):\n")
            nodes
                .filter { it.visible && it.enabled && (it.clickable || it.role == "input" || !it.text.isNullOrBlank()) }
                .take(maxLines)
                .forEachIndexed { index, node ->
                    append(
                        "${index + 1}. role=${node.role}, clickable=${node.clickable}, " +
                            "text=${node.text?.take(24) ?: "-"}, desc=${node.desc?.take(24) ?: "-"}, " +
                            "id=${node.resourceId?.substringAfterLast("/") ?: "-"}, " +
                            "bounds=[${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]\n"
                    )
                }
        }.trim()
    }
}

class UiaTreeSnapshotProvider(
    private val maxNodes: Int = 320,
    private val minCaptureIntervalMs: Long = 500L
) {
    private var lastSnapshot: UiaTreeSnapshot? = null
    private var lastCapturedAtMs: Long = 0L

    fun capture(): UiaTreeSnapshot? {
        val now = System.currentTimeMillis()
        val cached = lastSnapshot
        if (cached != null && now - lastCapturedAtMs < minCaptureIntervalMs) {
            return cached
        }

        val service = AlianAccessibilityService.getInstance() ?: return null
        val root = service.rootNode ?: return null
        val nodes = mutableListOf<UiaNodeLite>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to 0)

        while (queue.isNotEmpty() && nodes.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val text = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                val desc = node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                nodes.add(
                    UiaNodeLite(
                        text = text,
                        desc = desc,
                        resourceId = node.viewIdResourceName,
                        role = inferRole(node),
                        clickable = node.isClickable,
                        enabled = node.isEnabled,
                        visible = node.isVisibleToUser,
                        bounds = bounds,
                        depth = depth
                    )
                )
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        queue.add(child to (depth + 1))
                    }
                }
            } finally {
                node.recycle()
            }
        }

        while (queue.isNotEmpty()) {
            queue.removeFirst().first.recycle()
        }

        val snapshot = UiaTreeSnapshot(
            timestamp = System.currentTimeMillis(),
            nodeCount = nodes.size,
            nodes = nodes
        )
        lastSnapshot = snapshot
        lastCapturedAtMs = now
        return snapshot
    }

    private fun inferRole(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString()?.lowercase().orEmpty()
        return when {
            node.isEditable || className.contains("edittext") -> "input"
            className.contains("button") -> "button"
            className.contains("checkbox") -> "checkbox"
            className.contains("switch") -> "switch"
            className.contains("image") -> "image"
            className.contains("textview") -> "text"
            className.contains("recyclerview") || className.contains("listview") -> "list"
            else -> "unknown"
        }
    }
}

