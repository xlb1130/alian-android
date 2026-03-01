package com.alian.assistant.core.tools

import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.device.DeviceController

/**
 * Shell 命令工具
 *
 * 通过 Shizuku 执行 shell 命令
 * 注意：这是一个底层工具，主要供其他工具或高级场景使用
 */
class ShellTool(
    private val deviceController: DeviceController,
    private val settingsManager: SettingsManager? = null
) : Tool {

    override val name = "shell"
    override val displayName = "Shell 命令"
    override val description = "执行 shell 命令（需要 Shizuku 权限）"

    override val params = listOf(
        ToolParam(
            name = "command",
            type = "string",
            description = "要执行的 shell 命令",
            required = true
        )
    )

    // 安全白名单：允许执行的命令前缀
    private val ALLOWED_PREFIXES = listOf(
        "input ",           // 输入操作
        "am ",              // Activity Manager
        "pm ",              // Package Manager
        "wm ",              // Window Manager
        "screencap ",       // 截图
        "monkey ",          // 启动应用
        "dumpsys ",         // 系统信息
        "getprop ",         // 系统属性
        "settings ",        // 系统设置
        "content ",         // Content Provider
        "cmd ",             // 通用命令
        "ls ",              // 文件列表
        "cat ",             // 读文件
        "echo "             // 输出
    )

    // 基础黑名单：始终禁止的危险命令
    private val BASE_BLOCKED_COMMANDS = listOf(
        "rm -rf",
        "rm -r /",
        "format",
        "mkfs",
        "dd if=",
        "reboot",
        "shutdown",
        "> /dev",
        "chmod 777 /"
    )

    // su -c 命令（需要特殊权限才能使用）
    private val SU_COMMAND = "su -c"

    /**
     * 获取当前生效的黑名单
     * 根据设置决定是否允许 su -c
     */
    private fun getBlockedCommands(): List<String> {
        val settings = settingsManager?.settings?.value
        val suEnabled = settings?.rootModeEnabled == true && settings.suCommandEnabled == true
        return if (suEnabled) {
            BASE_BLOCKED_COMMANDS // su -c 已启用，不在黑名单中
        } else {
            BASE_BLOCKED_COMMANDS + SU_COMMAND // su -c 禁用
        }
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val command = params["command"] as? String
            ?: return ToolResult.Error("缺少 command 参数")

        // 安全检查
        val securityCheck = checkSecurity(command)
        if (securityCheck != null) {
            return ToolResult.Error(securityCheck)
        }

        // 由于 DeviceController.exec 是 private，这里需要通过已有的公开方法
        // 或者扩展 DeviceController
        // 暂时只支持特定的安全命令
        return try {
            val result = executeCommand(command)
            ToolResult.Success(
                data = result,
                message = "命令执行完成"
            )
        } catch (e: Exception) {
            ToolResult.Error("执行失败: ${e.message}")
        }
    }

    /**
     * 安全检查
     */
    private fun checkSecurity(command: String): String? {
        val lowerCmd = command.lowercase().trim()
        val blockedCommands = getBlockedCommands()

        // 检查黑名单
        for (blocked in blockedCommands) {
            if (lowerCmd.contains(blocked.lowercase())) {
                // 特殊提示 su -c 命令
                if (blocked == SU_COMMAND) {
                    return "安全限制：su -c 命令需要在设置中开启「Root 模式」和「允许 su -c」"
                }
                return "安全限制：禁止执行此类命令"
            }
        }

        // 检查白名单（可选，如果需要更严格的控制可以启用）
        // val isAllowed = ALLOWED_PREFIXES.any { lowerCmd.startsWith(it.lowercase()) }
        // if (!isAllowed) {
        //     return "安全限制：命令不在允许列表中"
        // }

        return null
    }

    /**
     * 执行命令
     * 注意：这里需要扩展 DeviceController 或使用反射
     * 暂时使用简化实现
     */
    private fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()

            if (error.isNotBlank()) {
                "Output: $output\nError: $error"
            } else {
                output
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
