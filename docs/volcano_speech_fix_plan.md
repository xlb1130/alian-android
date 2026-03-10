# 火山引擎 ASR/TTS 改造与修复文档（最终版）

## 1. 目标

本次改造目标：

1. 在线 ASR/TTS 能按 `speechProvider` 在百炼与火山之间无缝切换。
2. 火山实现完全对齐官方 v3 文档（Header 鉴权 + 二进制协议）。
3. 修复配置层问题（`cluster` 误必填、缺少 resource id、凭证落盘不完整）。

## 2. 官方文档基线（本次以此为准）

- TTS（双向流）：`https://www.volcengine.com/docs/6561/1329505?lang=zh`
- ASR（大模型流式）：`https://www.volcengine.com/docs/6561/1354869?lang=zh`

关键结论：

1. 火山 v3 使用 WebSocket Header 鉴权，不再依赖 URL query `cluster`。
2. 必要 Header：
   - `X-Api-App-Key`（APP ID）
   - `X-Api-Access-Key`（Access Token）
   - `X-Api-Resource-Id`（资源 ID）
3. TTS/ASR 都是二进制协议，且整数字段为大端。

## 3. 原始问题清单

### 3.1 配置层

1. 火山配置模型只覆盖 `appId/apiKey/cluster`，缺少 `ASR Resource ID` 和 `TTS Resource ID`。
2. UI 把 `cluster` 当必填，与 v3 文档不一致。
3. 凭证持久化未覆盖 resource id 字段。

### 3.2 协议实现层

1. `VolcanoAsrEngine` 仍是旧版 URL 参数鉴权和旧协议实现。
2. `VolcanoTtsEngine` 仍是旧版 `api/v1/tts_binary_llm`，未实现 v3 事件流。
3. 火山 ASR 工厂签名未跟上 `externalPcmMode`，会导致工厂接口不一致。

### 3.3 运行时切换层

1. 在线识别与通话链路大量写死 DashScope/Qwen。
2. `HybridTtsClient` 在线分支只支持 CosyVoice，不支持火山。
3. AEC 通话链路在线 ASR 写死 `AecQwenSpeechClient`。

## 4. 改造设计

### 4.1 配置模型

- `SpeechProviderCredentials` 新增：
  - `asrResourceId`
  - `ttsResourceId`
- `cluster` 保留为兼容字段，但不再作为火山运行时必填。
- `SpeechProviderConfig` 增加 `requiresAsrResourceId / requiresTtsResourceId`。

### 4.2 火山 ASR（v3）

- 端点：
  - `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel`
  - `.../bigmodel_nostream`
  - `.../bigmodel_async`
- Header 鉴权：`App-Key + Access-Key + Resource-Id`。
- 首包发送 full client request。
- 音频包发送 audio-only request（正序号），结束发送负序号 final 包。
- 响应按二进制协议解析，支持 error frame/full server response。

### 4.3 火山 TTS（v3 双向流）

- 端点：`wss://openspeech.bytedance.com/api/v3/tts/bidirection`
- Header 鉴权：`App-Key + Access-Key + Resource-Id`。
- 会话流程：
  - `StartConnection` -> `ConnectionStarted`
  - `StartSession` -> `SessionStarted`
  - `TaskRequest`（文本）
  - `FinishSession` -> `SessionFinished`
  - `FinishConnection`
- 仅向上游回调 PCM 音频 payload，不再透传协议帧。

### 4.4 在线链路切换

- `StreamingVoiceRecognitionManager` 改为按 `speechProvider` 动态创建 `BailianAsrEngine/VolcanoAsrEngine`。
- `VoiceCallAudioManager` 在线 ASR 改为统一 ASR 引擎。
- `AecVoiceCallAudioManager` 在线 ASR 改为统一 ASR 引擎 + `ExternalPcmConsumer` 外部喂流。
- `HybridTtsClient` 在线分支改为按 `speechProvider` 动态创建 `BailianTtsEngine/VolcanoTtsEngine`。

## 5. 已完成修改（代码落地）

1. 配置与持久化：
   - `app/src/main/java/com/alian/assistant/data/model/SpeechProvider.kt`
   - `app/src/main/java/com/alian/assistant/data/SettingsManager.kt`
   - `app/src/main/java/com/alian/assistant/presentation/ui/screens/settings/SpeechProviderSettingsContent.kt`
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/provider/SpeechProviderManager.kt`

2. 协议改造：
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/asr/volcano/VolcanoAsrEngine.kt`
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/tts/volcano/VolcanoTtsEngine.kt`

3. 运行时在线切换：
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/asr/StreamingVoiceRecognitionManager.kt`
   - `app/src/main/java/com/alian/assistant/infrastructure/audio/VoiceCallAudioManager.kt`
   - `app/src/main/java/com/alian/assistant/infrastructure/audio/AecVoiceCallAudioManager.kt`
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/tts/HybridTtsClient.kt`
   - `app/src/main/java/com/alian/assistant/presentation/viewmodel/AlianViewModel.kt`
   - `app/src/main/java/com/alian/assistant/presentation/ui/overlay/OverlayService.kt`

4. 类型扩展：
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/asr/AsrTypes.kt`
   - `app/src/main/java/com/alian/assistant/infrastructure/ai/tts/TtsTypes.kt`

## 6. 验证结果

已执行：

1. `./gradlew :app:compileDebugKotlin` 通过。
2. `./gradlew :app:testDebugUnitTest` 通过。

## 7. 兼容性说明

1. `cluster` 字段仍保留在配置模型中用于兼容历史数据，但火山 v3 运行时不再依赖它。
2. 若未填写火山 `App ID / Access Token / Resource ID`，在线火山 ASR/TTS 会明确报“配置不完整”。
3. 百炼链路保持可用，并支持通过统一引擎路径继续运行。
