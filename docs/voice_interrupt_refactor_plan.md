# VLM/语音通话语音打断改造方案清单

## 1. 背景

当前语音通话、视频通话、VLM Only 视频通话都宣称支持语音打断，但现状更接近“停掉当前 TTS 播放”，并没有真正完成“终止旧轮次并切到新轮次”。

用户体感上会出现以下几类问题：

1. AI 声音停了一下，但旧回复逻辑还在继续，随后又恢复或落回历史。
2. 用户已经说了新话，但系统仍判定“上一轮还在处理中”，新输入被丢弃。
3. 流式场景里，打断后没有真正停止旧 SSE/HTTP 请求，只是停止了本地播报。
4. 视频通话和 VLM 视频通话比语音通话更容易表现为“看起来支持打断，实际上接不上新一轮”。

本清单目标是把“语音打断”从“停播报”升级为“完整的轮次中断机制”。

## 2. 本次改造范围

### 2.1 业务范围

1. 语音通话 `VoiceCall`
2. 视频通话 `VideoCall`
3. VLM Only 视频通话 `VLMOnlyVideoCall`

### 2.2 技术范围

1. AEC/VAD 人声打断检测链路
2. ASR 录音与识别会话管理
3. LLM/VLM 流式与非流式请求生命周期
4. TTS 流式与非流式播放生命周期
5. ViewModel 状态机与对话轮次管理
6. 历史消息落盘与中断态处理

## 3. 当前问题清单

### 3.1 打断语义不完整

现状里“语音打断”大多只完成了：

1. 检测到用户说话
2. 停止本地 TTS 播放
3. 尝试重新开始录音

但没有统一完成：

1. 取消旧轮次任务
2. 取消旧 HTTP/SSE 请求
3. 让旧回调失效
4. 释放 `isProcessing`
5. 阻止旧响应继续写历史和改 UI

### 3.2 三条通话链路行为不一致

1. `VoiceCall` 的中断处理相对完整，但仍未统一取消旧请求。
2. `VideoCall` 和 `VLMOnlyVideoCall` 在播放中断回调里没有完整释放处理中状态。
3. 三个 ViewModel 对“打断后是否追加历史、何时重启录音、何时允许下一轮输入”没有统一规则。

### 3.3 流式请求不可真正取消

1. 旧轮次被打断后，本地播放虽然停止，但上游流式请求仍可能继续读取。
2. 旧流结束后仍可能进入 `onFinished`，把过期内容写入历史。
3. 旧任务晚到的回调会和新轮次竞争状态。

### 3.4 普通视频通话的“流式”不是真流式

`VideoCall` 当前依赖的文本流式实现先把整个响应读完，再按行拆分，导致：

1. 不能尽早播报
2. 不能中途优雅取消
3. 打断时只能停 TTS，不能真正停模型生成

### 3.5 中断后的上下文污染

1. 被打断的 AI 回复仍可能作为正式历史写入。
2. 下一轮 `filterOutAIResponse()` 可能把用户新话误伤。
3. 历史上下文混入半截回复，会影响下一轮 LLM/VLM 推理。

### 3.6 对 AEC 能力依赖较重，但缺少降级语义

1. 实时打断实质上依赖 AEC 音频链路。
2. 当不走 AEC 管线时，播放中的 ASR 结果会被忽略。
3. 当前缺少“检测失败时怎么退化”的明确策略。

## 4. 改造目标

本次改造需要达成以下目标：

1. 打断后，旧轮次必须被彻底终止，而不只是停止播报。
2. 三种通话模式的打断行为必须统一。
3. 新语音输入必须能在打断后稳定进入下一轮，不再被 `isProcessing` 卡住。
4. 流式请求必须支持真正取消。
5. 被打断的旧回复不能再污染对话历史。
6. 保持现有 AEC/VAD 检测能力，同时避免先把时间花在阈值微调上。

## 5. 总体设计

### 5.1 统一“轮次”概念

每一轮用户交互统一抽象为一个 `Turn`，至少包含：

1. `turnId`
2. `state`
3. `requestJob`
4. `streamCall`
5. `playbackSession`
6. `isInterrupted`

建议统一轮次状态：

1. `Listening`
2. `Recognized`
3. `Requesting`
4. `Speaking`
5. `Interrupted`
6. `Completed`
7. `Cancelled`

### 5.2 统一打断入口

所有通话模式共用同一套打断语义：

`interruptCurrentTurn(reason)`

该入口必须保证按顺序完成：

1. 标记当前轮次为 `Interrupted`
2. 停止当前 TTS 播放
3. 取消当前流式/非流式模型请求
4. 取消与该轮次绑定的协程任务
5. 释放 `isProcessing`
6. 清理当前播放文案与临时缓冲
7. 启动下一轮录音监听

### 5.3 过期回调统一丢弃

所有异步回调都必须带 `turnId` 校验：

1. 旧轮次 `onFinished`
2. 旧轮次 `onError`
3. 旧轮次 `ASR final`
4. 旧轮次 `SSE chunk`
5. 旧轮次 `tool call result`

如果回调所属 `turnId` 不等于当前激活轮次，必须直接丢弃。

## 6. 分阶段改造清单

## Phase 0 基线与观测补齐

- [ ] 为三种通话模式统一补充轮次日志：创建轮次、开始请求、开始播报、打断触发、旧轮次取消、回调丢弃。
- [ ] 为打断流程增加结构化埋点：`candidate_detected / interrupt_confirmed / turn_cancelled / stale_callback_dropped / restart_recording`。
- [ ] 明确区分“停播报成功”和“轮次已取消成功”两类日志。
- [ ] 为流式请求增加请求 ID，便于排查“旧流未停”。

交付标准：

1. 可以从日志中完整还原一次打断全过程。
2. 可以区分问题发生在检测层、状态层、还是请求取消层。

## Phase 1 统一打断状态机

- [ ] 在 `VoiceCallViewModel`、`VideoCallViewModel`、`VLMOnlyVideoCallViewModel` 中引入统一轮次控制字段。
- [ ] 抽象统一的 `interruptCurrentTurn()` 流程，禁止各 ViewModel 只在回调里零散改状态。
- [ ] 播放中断回调触发时，必须同步释放 `isProcessing`。
- [ ] 播放中断回调触发时，必须清空 `_currentPlayingMessage`。
- [ ] 统一“打断后马上开始录音”的时机和防抖策略。
- [ ] 禁止打断回调直接复用旧轮次状态。

交付标准：

1. 三种通话模式在打断后都能立刻重新进入可录音状态。
2. 不再出现“已经停播，但 `isProcessing` 还锁着”的情况。

## Phase 2 请求与任务真正可取消

- [ ] 为语音通话 MCP 客户端增加活动中的 `OkHttp Call` 管理。
- [ ] 为视频通话客户端增加活动中的 `OkHttp Call` 管理。
- [ ] 为 VLM Only 视频通话客户端增加活动中的 `OkHttp Call` 管理。
- [ ] 在 `interruptCurrentTurn()` 中调用统一的 `cancelActiveRequest()`。
- [ ] 确保协程取消会同步终止 SSE 读取，而不是继续读到 `[DONE]`。
- [ ] 为非流式请求也提供取消入口，避免“思考中无法打断”。

交付标准：

1. 用户打断后，旧请求不再继续产出 chunk。
2. 旧请求不会在几秒后继续回调 `onFinished`。

## Phase 3 TTS 与流式播放会话收敛

- [ ] `AecVoiceCallAudioManager` 的流式播放在打断后不能只停 TTS，还要终止与旧轮次绑定的文本收集协程。
- [ ] `playTextStream()` 需要支持上游取消而不是等待旧 `collectJob` 自然结束。
- [ ] 为 TTS 会话增加显式 `playbackSessionId`，避免旧 TTS 回调落到新轮次。
- [ ] 中断态下禁止旧 TTS 再写入参考音频或再次触发结束回调。
- [ ] 统一流式和非流式播放的中断返回语义。

交付标准：

1. 打断后本地播报立刻停止。
2. 旧播报不会在短暂静默后继续恢复。

## Phase 4 普通视频通话流式链路重做

- [ ] 将 `VideoCall` 当前伪流式实现改成真正基于 `byteStream()` 的增量读取。
- [ ] 确保普通视频通话与语音通话、VLM 视频通话使用一致的可取消流式接口。
- [ ] 统一 chunk 边界策略，避免句子级切分过晚影响打断响应时间。
- [ ] 明确“模型请求已取消”和“TTS 已取消”的先后顺序。

交付标准：

1. `VideoCall` 在流式模式下可以中途真实取消。
2. 打断延迟不再受“先读完整个 responseBody”限制。

## Phase 5 历史消息与上下文污染治理

- [ ] 被打断的 AI 回复默认不写入正式历史。
- [ ] 如需保留，单独标记为 `interrupted_partial`，不能作为下一轮完整 assistant message。
- [ ] `filterOutAIResponse()` 只能基于“最后一条完整 assistant 回复”工作。
- [ ] 统一定义“何时一条 assistant 回复算真正完成”。
- [ ] 工具调用中断时，禁止将半截工具结果直接拼进历史。

交付标准：

1. 打断后的下一轮上下文干净。
2. 不再因为半截历史导致用户新话被误过滤。

## Phase 6 AEC 与降级策略收敛

- [ ] 明确“实时打断依赖 AEC”的产品语义。
- [ ] 当 AEC 不可用时，给出明确降级行为：仅在播报结束后继续识别，而不是误导为“支持实时打断”。
- [ ] 保留现有 AEC 检测阈值，不把阈值调参当作本轮主任务。
- [ ] 在完整轮次取消机制落地后，再评估是否需要继续调阈值和保护期。

交付标准：

1. 实时打断能力与配置语义一致。
2. 不再把状态机问题误判成纯 VAD/AEC 问题。

## 7. 文件级实施清单

### 7.1 ViewModel 层

- [ ] `app/src/main/java/com/alian/assistant/presentation/viewmodel/VoiceCallViewModel.kt`
- [ ] `app/src/main/java/com/alian/assistant/presentation/viewmodel/VideoCallViewModel.kt`
- [ ] `app/src/main/java/com/alian/assistant/presentation/viewmodel/VLMOnlyVideoCallViewModel.kt`

重点改造：

1. 引入统一轮次 ID
2. 抽象 `interruptCurrentTurn()`
3. 统一 `isProcessing` 生命周期
4. 统一 stale callback 丢弃逻辑

### 7.2 音频层

- [ ] `app/src/main/java/com/alian/assistant/infrastructure/audio/AecVoiceCallAudioManager.kt`
- [ ] `app/src/main/java/com/alian/assistant/infrastructure/audio/VoiceCallAudioManager.kt`
- [ ] `app/src/main/java/com/alian/assistant/infrastructure/audio/AecAudioProcessor.kt`
- [ ] `app/src/main/java/com/alian/assistant/infrastructure/audio/IAudioManager.kt`

重点改造：

1. 明确“停止播放”和“终止轮次”职责边界
2. 让流式播放支持上游取消
3. 统一回调语义

### 7.3 LLM/VLM 客户端层

- [ ] `app/src/main/java/com/alian/assistant/presentation/ui/screens/voicecall/MCPVoiceCallClient.kt`
- [ ] `app/src/main/java/com/alian/assistant/presentation/ui/screens/videocall/VideoCallClient.kt`
- [ ] `app/src/main/java/com/alian/assistant/presentation/ui/screens/videocall/MCPVideoCallClient.kt`
- [ ] `app/src/main/java/com/alian/assistant/presentation/ui/screens/videocall/VLMOnlyVideoCallClient.kt`
- [ ] `app/src/main/java/com/alian/assistant/presentation/ui/screens/videocall/MCPVLMOnlyVideoCallClient.kt`
- [ ] `app/src/main/java/com/alian/assistant/infrastructure/ai/llm/LLMClient.kt`

重点改造：

1. 活动请求可取消
2. 真正的流式读取
3. 回调绑定轮次 ID

### 7.4 TTS 层

- [ ] `app/src/main/java/com/alian/assistant/infrastructure/ai/tts/HybridTtsClient.kt`
- [ ] 在线/离线 TTS Engine 相关实现

重点改造：

1. 统一播放取消接口
2. 播放会话 ID
3. 打断后停止后续音频写入

## 8. 验收用例清单

- [ ] 语音通话非流式：AI 正在播报时，用户插话，旧播报立即停止，新话进入下一轮。
- [ ] 语音通话流式：AI 边说边播，用户插话后旧流被取消，不再落回旧回复。
- [ ] 视频通话流式：打断后 `isProcessing` 立即释放，新话不被丢弃。
- [ ] VLM 视频通话流式：打断后旧回答不写入正式历史。
- [ ] 连续两次快速打断：不会出现多轮并发重启录音。
- [ ] 工具调用中打断：旧工具结果不会污染当前轮次 UI 和历史。
- [ ] 非 AEC 模式：行为符合预期降级，不宣称实时打断成功。
- [ ] 切后台/音频焦点变化后恢复：不会把旧轮次回调误投递给新轮次。

## 9. 风险与注意事项

1. 不要先从调阈值入手，否则容易掩盖真正的状态机问题。
2. 不要只在一个 ViewModel 上修，三条链路必须一起统一语义。
3. 不要允许“旧回调改新状态”，这是后续所有诡异问题的根源。
4. 不要把被打断的回复直接写正式历史。
5. 不要把“停止 TTS”误当成“完成打断”。

## 10. 实施优先级建议

建议按以下顺序推进：

1. 先做 Phase 1，统一状态机和打断入口。
2. 再做 Phase 2，补齐旧请求取消能力。
3. 然后做 Phase 3，收敛 TTS 与流式播放会话。
4. 接着做 Phase 4，重做普通视频通话伪流式。
5. 最后做 Phase 5 和 Phase 6，治理上下文污染与降级策略。

如果只允许先做一轮最小可用修复，优先级应为：

1. `VideoCallViewModel` / `VLMOnlyVideoCallViewModel` 打断时立即释放 `isProcessing`
2. 统一取消 `currentJob`
3. 为流式请求补 `cancelActiveRequest()`
4. 为所有异步回调增加 `turnId` 丢弃机制

