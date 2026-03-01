package com.alian.assistant.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.alian.assistant.core.skills.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自定义 Skills 管理器
 *
 * 负责用户自定义 Skills 的持久化存储和管理
 * 使用 SharedPreferences 存储 JSON 格式的 Skills 配置
 */
class UserSkillsManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 内存缓存
    private var cachedSkills: MutableList<SkillConfig>? = null

    companion object {
        private const val PREFS_NAME = "user_skills"
        private const val KEY_SKILLS = "skills_json"
        private const val TAG = "UserSkillsManager"

        @Volatile
        private var instance: UserSkillsManager? = null

        fun init(context: Context): UserSkillsManager {
            return instance ?: synchronized(this) {
                instance ?: UserSkillsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getInstance(): UserSkillsManager {
            return instance ?: throw IllegalStateException("UserSkillsManager 未初始化，请先调用 init()")
        }

        fun isInitialized(): Boolean = instance != null
    }

    /**
     * 加载所有用户自定义 Skills
     */
    fun loadUserSkills(): List<SkillConfig> {
        cachedSkills?.let { return it }

        val skills = mutableListOf<SkillConfig>()
        try {
            val jsonString = prefs.getString(KEY_SKILLS, null)
            if (!jsonString.isNullOrEmpty()) {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val config = parseSkillConfig(obj)
                    skills.add(config)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载用户 Skills 失败: ${e.message}")
        }

        cachedSkills = skills
        Log.d(TAG, "已加载 ${skills.size} 个用户自定义 Skills")
        return skills
    }

    /**
     * 保存用户 Skill（新增或更新）
     */
    fun saveUserSkill(config: SkillConfig) {
        val skills = cachedSkills?.toMutableList() ?: loadUserSkills().toMutableList()

        // 查找是否已存在（更新）或新增
        val existingIndex = skills.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            skills[existingIndex] = config
            Log.d(TAG, "更新用户 Skill: ${config.id}")
        } else {
            skills.add(config)
            Log.d(TAG, "新增用户 Skill: ${config.id}")
        }

        cachedSkills = skills
        saveToPrefs(skills)

        // 同步注册到 SkillRegistry，确保内存中有最新数据
        if (SkillRegistry.isInitialized()) {
            SkillRegistry.getInstance().registerUserSkill(Skill(config))
            Log.d(TAG, "已将 Skill 注册到 SkillRegistry: ${config.id}")
        }
    }

    /**
     * 删除用户 Skill
     */
    fun deleteUserSkill(id: String): Boolean {
        val skills = cachedSkills?.toMutableList() ?: loadUserSkills().toMutableList()
        val removed = skills.removeIf { it.id == id }

        if (removed) {
            cachedSkills = skills
            saveToPrefs(skills)
            Log.d(TAG, "删除用户 Skill: $id")
        }

        return removed
    }

    /**
     * 获取单个用户 Skill
     */
    fun getUserSkill(id: String): SkillConfig? {
        val skills = cachedSkills ?: loadUserSkills()
        return skills.find { it.id == id }
    }

    /**
     * 检查是否为用户创建的 Skill
     */
    fun isUserCreated(id: String): Boolean {
        val skills = cachedSkills ?: loadUserSkills()
        return skills.any { it.id == id }
    }

    /**
     * 生成唯一的 Skill ID
     */
    fun generateUniqueId(baseName: String): String {
        val skills = cachedSkills ?: loadUserSkills()
        val baseId = "user_" + baseName
            .lowercase()
            .replace(Regex("[^a-z0-9\u4e00-\u9fa5]"), "_")
            .take(20)

        var id = baseId
        var counter = 1
        while (skills.any { it.id == id }) {
            id = "${baseId}_$counter"
            counter++
        }

        return id
    }

    /**
     * 导出用户 Skills 为 JSON 字符串
     */
    fun exportToJson(): String {
        val skills = cachedSkills ?: loadUserSkills()
        val jsonArray = JSONArray()
        for (config in skills) {
            jsonArray.put(skillConfigToJson(config))
        }
        return jsonArray.toString(2)
    }

    /**
     * 清空所有用户 Skills
     */
    fun clearAll() {
        cachedSkills = mutableListOf()
        prefs.edit().remove(KEY_SKILLS).apply()
        Log.d(TAG, "已清空所有用户 Skills")
    }

    // ========== 私有方法 ==========

    private fun saveToPrefs(skills: List<SkillConfig>) {
        try {
            val jsonArray = JSONArray()
            for (config in skills) {
                jsonArray.put(skillConfigToJson(config))
            }
            prefs.edit().putString(KEY_SKILLS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存用户 Skills 失败: ${e.message}")
        }
    }

    private fun skillConfigToJson(config: SkillConfig): JSONObject {
        val obj = JSONObject()
        obj.put("id", config.id)
        obj.put("name", config.name)
        obj.put("description", config.description)
        obj.put("category", config.category)
        obj.put("keywords", JSONArray(config.keywords))
        obj.put("params", JSONArray(config.params.map { param ->
            JSONObject().apply {
                put("name", param.name)
                put("type", param.type)
                put("description", param.description)
                put("required", param.required)
                param.defaultValue?.let { put("default", it) }
                put("examples", JSONArray(param.examples))
            }
        }))
        obj.put("related_apps", JSONArray(config.relatedApps.map { app ->
            JSONObject().apply {
                put("package", app.packageName)
                put("name", app.name)
                put("type", if (app.type == ExecutionType.DELEGATION) "delegation" else "gui_automation")
                app.deepLink?.let { put("deep_link", it) }
                app.steps?.let { put("steps", JSONArray(it)) }
                put("priority", app.priority)
                app.description?.let { put("description", it) }
            }
        }))
        config.promptHint?.let { obj.put("prompt_hint", it) }
        return obj
    }

    private fun parseSkillConfig(obj: JSONObject): SkillConfig {
        // 解析关键词
        val keywords = mutableListOf<String>()
        val keywordsArray = obj.optJSONArray("keywords")
        if (keywordsArray != null) {
            for (i in 0 until keywordsArray.length()) {
                keywords.add(keywordsArray.getString(i))
            }
        }

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

        // 解析关联应用
        val relatedApps = mutableListOf<RelatedApp>()
        val appsArray = obj.optJSONArray("related_apps")
        if (appsArray != null) {
            for (i in 0 until appsArray.length()) {
                val appObj = appsArray.getJSONObject(i)
                val typeStr = appObj.optString("type", "gui_automation")
                val type = when (typeStr.lowercase()) {
                    "delegation" -> ExecutionType.DELEGATION
                    else -> ExecutionType.GUI_AUTOMATION
                }

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
            category = obj.optString("category", "自定义"),
            keywords = keywords,
            params = params,
            relatedApps = relatedApps,
            promptHint = obj.optString("prompt_hint", null)?.takeIf { it.isNotEmpty() }
        )
    }
}
