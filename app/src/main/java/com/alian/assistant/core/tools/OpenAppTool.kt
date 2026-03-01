package com.alian.assistant.core.tools

import com.alian.assistant.infrastructure.device.AppScanner
import com.alian.assistant.infrastructure.device.DeviceController


/**
 * 打开应用工具
 *
 * 支持：
 * - 通过包名打开
 * - 通过应用名打开（自动搜索包名）
 */
class OpenAppTool(
    private val deviceController: DeviceController,
    private val appScanner: AppScanner
) : Tool {

    override val name = "open_app"
    override val displayName = "打开应用"
    override val description = "打开指定的应用程序"

    override val params = listOf(
        ToolParam(
            name = "app",
            type = "string",
            description = "应用名称或包名（如：微信、com.tencent.mm）",
            required = true
        )
    )

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val app = params["app"] as? String
            ?: return ToolResult.Error("缺少 app 参数")

        // 判断是包名还是应用名
        val packageName = if (app.contains(".")) {
            // 已经是包名格式
            app
        } else {
            // 需要搜索包名
            val results = appScanner.searchApps(app, topK = 1)
            results.firstOrNull()?.app?.packageName
                ?: return ToolResult.Error("未找到应用: $app")
        }

        return try {
            deviceController.openApp(packageName)
            ToolResult.Success(
                data = mapOf("package_name" to packageName),
                message = "已打开应用: $app"
            )
        } catch (e: Exception) {
            ToolResult.Error("打开应用失败: ${e.message}")
        }
    }
}
