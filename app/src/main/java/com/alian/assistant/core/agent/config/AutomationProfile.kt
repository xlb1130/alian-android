package com.alian.assistant.core.agent.config

import com.alian.assistant.core.agent.config.policy.ExecutionPolicy
import com.alian.assistant.core.agent.config.policy.FeatureFlags
import com.alian.assistant.core.agent.config.policy.ModelCallPolicy
import com.alian.assistant.core.agent.config.policy.RiskControlPolicy
import com.alian.assistant.core.agent.config.policy.SnapshotPolicy
import com.alian.assistant.core.agent.config.policy.VirtualDisplayPolicy

/**
 * 自动化配置聚合根。
 *
 * 设计约束：
 * 1. Agent 运行期只读该聚合，避免配置来源分散。
 * 2. 各策略对象保持职责单一，后续能力扩展只需新增策略。
 *
 * @property execution 执行节奏与步数策略。
 * @property modelCall 模型调用与重试策略。
 * @property snapshot 截图缓存与节流策略。
 * @property riskControl 风险控制策略。
 * @property virtualDisplay 虚拟屏执行策略。
 * @property featureFlags 能力开关集合。
 */
data class AutomationProfile(
    val execution: ExecutionPolicy = ExecutionPolicy(),
    val modelCall: ModelCallPolicy = ModelCallPolicy(),
    val snapshot: SnapshotPolicy = SnapshotPolicy(),
    val riskControl: RiskControlPolicy = RiskControlPolicy(),
    val virtualDisplay: VirtualDisplayPolicy = VirtualDisplayPolicy(),
    val featureFlags: FeatureFlags = FeatureFlags(),
)
