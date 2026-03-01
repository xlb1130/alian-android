package com.alian.assistant.core.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.alian.assistant.data.UserSkillsManager
import com.alian.assistant.infrastructure.device.AppScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.collections.iterator

/**
 * Skill 注册表
 *
 * 管理所有 Skills 的注册、查找和匹配
 * 核心功能：
 * - 从 skills.json 加载内置意图定义
 * - 加载用户自定义 Skills
 * - 查询本地已安装 App，筛选可用应用
 * - 根据优先级选择最佳执行方案
 */
class SkillRegistry private constructor(
    private val context: Context,
    private val appScanner: AppScanner
) {

    private val skills = mutableMapOf<String, Skill>()
    private val categoryIndex = mutableMapOf<String, MutableList<Skill>>()

    // 用户创建的 Skill ID 集合（用于区分内置和自定义）
    private val userCreatedSkillIds = mutableSetOf<String>()

    // 缓存已安装 App 的包名集合（启动时刷新）
    private var installedPackages: Set<String> = emptySet()

    /**
     * 初始化：刷新已安装应用列表
     */
    fun refreshInstalledApps() {
        val apps = appScanner.getApps()
        installedPackages = apps.map { it.packageName }.toSet()
        println("[SkillRegistry] 已缓存 ${installedPackages.size} 个已安装应用")

        // 调试：检查美团相关的应用
        val meituanApps = installedPackages.filter { it.contains("meituan") || it.contains("dianping") }
        println("[SkillRegistry] 美团相关应用: $meituanApps")

        // 检查小美的 DeepLink 是否可用（间接检测安装状态）
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("beam://www.meituan.com/home")
            }
            val resolveInfo = pm.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                val pkgName = resolveInfo.activityInfo.packageName
                println("[SkillRegistry] 小美 DeepLink 可用，包名: $pkgName")
                if (!installedPackages.contains(pkgName)) {
                    installedPackages = installedPackages + pkgName
                    println("[SkillRegistry] 添加 $pkgName 到已安装列表")
                }
            } else {
                println("[SkillRegistry] 小美 DeepLink 不可用")
            }
        } catch (e: Exception) {
            println("[SkillRegistry] 检查小美失败: ${e.message}")
        }
    }

    /**
     * 检查包名是否已安装
     */
    fun isAppInstalled(packageName: String): Boolean {
        return installedPackages.contains(packageName)
    }

    /**
     * 从 assets/skills.json 加载 Skills
     */
    fun loadFromAssets(filename: String = "skills.json"): Int {
        try {
            val jsonString = context.assets.open(filename).bufferedReader().use { it.readText() }
            return loadFromJson(jsonString)
        } catch (e: IOException) {
            println("[SkillRegistry] 无法加载 $filename: ${e.message}")
            return 0
        }
    }

    /**
     * 从 JSON 字符串加载 Skills
     */
    fun loadFromJson(jsonString: String): Int {
        var loadedCount = 0
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val config = parseSkillConfig(obj)
                register(Skill(config))
                loadedCount++
            }
            println("[SkillRegistry] 已加载 $loadedCount 个 Skills")
        } catch (e: Exception) {
            println("[SkillRegistry] JSON 解析错误: ${e.message}")
            e.printStackTrace()
        }
        return loadedCount
    }

    /**
     * 解析单个 Skill 配置（新结构）
     */
    private fun parseSkillConfig(obj: JSONObject): SkillConfig {
        // 解析参数
        val params = mutableListOf<SkillParam>()
        val paramsArray = obj.optJSONArray("params")
        if (paramsArray != null) {
            for (i in 0 until paramsArray.length()) {
                val paramObj = paramsArray.getJSONObject(i)
                val examples = mutableListOf<String>()
                val examplesArray = paramObj.optJSONArray("examples")
                if (examplesArray != null) {
                    for (j in 0 until examplesArray.length()) {
                        examples.add(examplesArray.getString(j))
                    }
                }
                params.add(SkillParam(
                    name = paramObj.getString("name"),
                    type = paramObj.optString("type", "string"),
                    description = paramObj.optString("description", ""),
                    required = paramObj.optBoolean("required", false),
                    defaultValue = paramObj.opt("default"),
                    examples = examples
                ))
            }
        }

        // 解析关键词
        val keywords = mutableListOf<String>()
        val keywordsArray = obj.optJSONArray("keywords")
        if (keywordsArray != null) {
            for (i in 0 until keywordsArray.length()) {
                keywords.add(keywordsArray.getString(i))
            }
        }

        // 解析关联应用列表（新结构）
        val relatedApps = mutableListOf<RelatedApp>()
        val appsArray = obj.optJSONArray("related_apps")
        if (appsArray != null) {
            for (i in 0 until appsArray.length()) {
                val appObj = appsArray.getJSONObject(i)

                // 解析执行类型
                val typeStr = appObj.optString("type", "gui_automation")
                val type = when (typeStr.lowercase()) {
                    "delegation" -> ExecutionType.DELEGATION
                    else -> ExecutionType.GUI_AUTOMATION
                }

                // 解析操作步骤
                val steps = mutableListOf<String>()
                val stepsArray = appObj.optJSONArray("steps")
                if (stepsArray != null) {
                    for (j in 0 until stepsArray.length()) {
                        steps.add(stepsArray.getString(j))
                    }
                }

                relatedApps.add(RelatedApp(
                    packageName = appObj.getString("package"),
                    name = appObj.getString("name"),
                    type = type,
                    deepLink = appObj.optString("deep_link", null)?.takeIf { it.isNotEmpty() },
                    steps = if (steps.isEmpty()) null else steps,
                    priority = appObj.optInt("priority", 0),
                    description = appObj.optString("description", null)?.takeIf { it.isNotEmpty() }
                ))
            }
        }

        return SkillConfig(
            id = obj.getString("id"),
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            category = obj.optString("category", "通用"),
            keywords = keywords,
            params = params,
            relatedApps = relatedApps,
            promptHint = obj.optString("prompt_hint", null)?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * 注册 Skill（内置）
     */
    fun register(skill: Skill) {
        skills[skill.config.id] = skill

        // 更新分类索引
        val category = skill.config.category
        categoryIndex.getOrPut(category) { mutableListOf() }.add(skill)

        println("[SkillRegistry] 注册 Skill: ${skill.config.id} (${skill.config.relatedApps.size} 关联应用)")
    }

    /**
     * 注册用户自定义 Skill
     */
    fun registerUserSkill(skill: Skill) {
        skills[skill.config.id] = skill
        userCreatedSkillIds.add(skill.config.id)

        // 更新分类索引
        val category = skill.config.category
        categoryIndex.getOrPut(category) { mutableListOf() }.add(skill)

        println("[SkillRegistry] 注册用户 Skill: ${skill.config.id} (${skill.config.relatedApps.size} 关联应用)")
    }

    /**
     * 注销 Skill（仅允许用户自定义 Skill）
     * @return 是否成功注销
     */
    fun unregister(skillId: String): Boolean {
        if (!isUserCreated(skillId)) {
            Log.w("SkillRegistry", "无法删除内置 Skill: $skillId")
            return false
        }

        val skill = skills.remove(skillId) ?: return false
        userCreatedSkillIds.remove(skillId)

        // 更新分类索引
        val category = skill.config.category
        categoryIndex[category]?.remove(skill)
        if (categoryIndex[category]?.isEmpty() == true) {
            categoryIndex.remove(category)
        }

        println("[SkillRegistry] 注销用户 Skill: $skillId")
        return true
    }

    /**
     * 判断是否为用户创建的 Skill
     */
    fun isUserCreated(skillId: String): Boolean {
        return userCreatedSkillIds.contains(skillId)
    }

    /**
     * 加载用户自定义 Skills
     */
    fun loadUserSkills(): Int {
        if (!UserSkillsManager.isInitialized()) {
            println("[SkillRegistry] UserSkillsManager 未初始化，跳过加载用户 Skills")
            return 0
        }

        val userSkills = UserSkillsManager.getInstance().loadUserSkills()
        var loadedCount = 0

        for (config in userSkills) {
            registerUserSkill(Skill(config))
            loadedCount++
        }

        println("[SkillRegistry] 已加载 $loadedCount 个用户自定义 Skills")
        return loadedCount
    }

    /**
     * 获取 Skill
     */
    fun get(id: String): Skill? = skills[id]

    /**
     * 获取所有 Skills
     */
    fun getAll(): List<Skill> = skills.values.toList()

    /**
     * 按分类获取 Skills
     */
    fun getByCategory(category: String): List<Skill> {
        return categoryIndex[category] ?: emptyList()
    }

    /**
     * 获取所有分类
     */
    fun getAllCategories(): List<String> = categoryIndex.keys.toList()

    /**
     * 匹配用户意图（基于关键词）
     */
    fun match(query: String, topK: Int = 3, minScore: Float = 0.3f): List<SkillMatch> {
        val matches = mutableListOf<SkillMatch>()

        for (skill in skills.values) {
            val score = skill.matchScore(query)
            if (score >= minScore) {
                val params = skill.extractParams(query)
                matches.add(SkillMatch(skill, score, params))
            }
        }

        return matches
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * 获取最佳匹配
     */
    fun matchBest(query: String, minScore: Float = 0.3f): SkillMatch? {
        return match(query, topK = 1, minScore = minScore).firstOrNull()
    }

    /**
     * 匹配意图并返回可用应用（核心方法）
     *
     * 1. 匹配用户意图到 Skill
     * 2. 筛选出已安装的关联应用
     * 3. 按优先级排序
     */
    fun matchAvailableApps(
        query: String,
        minScore: Float = 0.3f
    ): List<AvailableAppMatch> {
        val skillMatches = match(query, topK = 5, minScore = minScore)
        val results = mutableListOf<AvailableAppMatch>()

        for (skillMatch in skillMatches) {
            val skill = skillMatch.skill
            val params = skillMatch.params

            // 筛选已安装的应用，按优先级排序
            val availableApps = skill.config.relatedApps
                .filter { isAppInstalled(it.packageName) }
                .sortedByDescending { it.priority }

            for (app in availableApps) {
                results.add(AvailableAppMatch(
                    skill = skill,
                    app = app,
                    params = params,
                    score = skillMatch.score
                ))
            }
        }

        // 按 (匹配分数 * 0.5 + 应用优先级 * 0.01) 综合排序
        return results.sortedByDescending { it.score * 0.5f + it.app.priority * 0.01f }
    }

    /**
     * 获取意图的最佳可用应用
     */
    fun getBestAvailableApp(query: String, minScore: Float = 0.3f): AvailableAppMatch? {
        return matchAvailableApps(query, minScore).firstOrNull()
    }

    /**
     * 生成 Skills 描述（给 LLM）
     */
    fun getSkillsDescription(): String {
        return buildString {
            append("可用技能列表：\n\n")
            for ((category, categorySkills) in categoryIndex) {
                append("【$category】\n")
                for (skill in categorySkills) {
                    val config = skill.config
                    append("- ${config.name}: ${config.description}\n")
                    if (config.keywords.isNotEmpty()) {
                        append("  关键词: ${config.keywords.joinToString(", ")}\n")
                    }
                    // 显示已安装的应用
                    val installedApps = config.relatedApps.filter { isAppInstalled(it.packageName) }
                    if (installedApps.isNotEmpty()) {
                        val appNames = installedApps.map {
                            val typeIcon = if (it.type == ExecutionType.DELEGATION) "🚀" else "🤖"
                            "$typeIcon${it.name}"
                        }
                        append("  可用应用: ${appNames.joinToString(", ")}\n")
                    }
                }
                append("\n")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: SkillRegistry? = null

        fun init(context: Context, appScanner: AppScanner): SkillRegistry {
            return instance ?: synchronized(this) {
                instance ?: SkillRegistry(context.applicationContext, appScanner).also {
                    it.refreshInstalledApps()
                    instance = it
                }
            }
        }

        fun getInstance(): SkillRegistry {
            return instance ?: throw IllegalStateException("SkillRegistry 未初始化，请先调用 init()")
        }

        fun isInitialized(): Boolean = instance != null
    }
}
