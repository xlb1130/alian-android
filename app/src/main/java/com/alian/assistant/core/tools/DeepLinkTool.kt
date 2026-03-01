package com.alian.assistant.core.tools

import com.alian.assistant.infrastructure.device.DeviceController
import kotlin.collections.iterator

/**
 * DeepLink 工具
 *
 * 通过 Intent 打开应用的特定页面或功能
 * 这是实现 delegation 类型 Skill 的核心工具
 */
class DeepLinkTool(private val deviceController: DeviceController) : Tool {

    override val name = "deep_link"
    override val displayName = "深度链接"
    override val description = "通过 DeepLink/Intent 打开应用的特定页面或功能"

    override val params = listOf(
        ToolParam(
            name = "uri",
            type = "string",
            description = "DeepLink URI（如：weixin://、alipays://、amap://）",
            required = true
        ),
        ToolParam(
            name = "action",
            type = "string",
            description = "Intent Action（默认 VIEW）",
            required = false,
            defaultValue = "android.intent.action.VIEW"
        )
    )

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val uri = params["uri"] as? String
            ?: return ToolResult.Error("缺少 uri 参数")

        return try {
            deviceController.openDeepLink(uri)
            ToolResult.Success(
                data = mapOf("uri" to uri),
                message = "已打开: $uri"
            )
        } catch (e: Exception) {
            ToolResult.Error("打开 DeepLink 失败: ${e.message}")
        }
    }

    companion object {
        /**
         * 常用 DeepLink 模板
         */
        val TEMPLATES = mapOf(
            // ========== 外卖类 ==========
            "meituan_food" to "imeituan://www.meituan.com/waimai/home",
            "meituan_search" to "imeituan://www.meituan.com/search?q={query}",
            "eleme_home" to "eleme://search",
            "xiaomei_chat" to "xiaomei://chat?message={query}",  // 小美 AI 对话

            // ========== 出行类 ==========
            "amap_route" to "amap://route/plan?sourceApplication=alian&dlat={lat}&dlon={lon}&dname={destination}&dev=0&t=0",
            "amap_navi" to "amap://navi?sourceApplication=alian&lat={lat}&lon={lon}&dev=0",
            "amap_search" to "amap://search?keyword={query}&sourceApplication=alian",
            "didi_call" to "diditaxi://",
            "baidu_map_route" to "baidumap://map/direction?destination={destination}&mode=driving&src=alian",

            // ========== 社交类 ==========
            "weixin_scan" to "weixin://scanqrcode",
            "weixin_pay" to "weixin://pay",
            "alipay_scan" to "alipays://platformapi/startapp?appId=10000007",
            "alipay_pay" to "alipays://platformapi/startapp?appId=20000056",

            // ========== 支付类 ==========
            "alipay_transfer" to "alipays://platformapi/startapp?appId=20000200&actionType=toAccount&account={account}",

            // ========== 音乐类 ==========
            "netease_play" to "orpheus://song/{id}",
            "netease_search" to "orpheus://search?keyword={query}",
            "qqmusic_search" to "qqmusic://qq.com/ui/search?key={query}",

            // ========== 视频类 ==========
            "douyin_search" to "snssdk1128://search?keyword={query}",
            "bilibili_search" to "bilibili://search?keyword={query}",
            "bilibili_video" to "bilibili://video/{bvid}",

            // ========== AI 类（delegation 目标）==========
            "doubao_chat" to "doubao://chat?message={query}",
            "tongyi_chat" to "tongyi://chat?message={query}",

            // ========== 通用 ==========
            "web" to "{url}",
            "tel" to "tel:{phone}",
            "sms" to "sms:{phone}?body={message}",
            "email" to "mailto:{email}?subject={subject}&body={body}"
        )

        /**
         * 根据模板生成 DeepLink
         */
        fun fromTemplate(templateName: String, params: Map<String, String>): String? {
            val template = TEMPLATES[templateName] ?: return null
            var result = template
            for ((key, value) in params) {
                result = result.replace("{$key}", value)
            }
            return result
        }
    }
}
