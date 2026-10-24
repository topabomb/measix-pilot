# AI 协议层参考

本文档描述当前代码支持的线协议、统一消息模型、不透明状态回放和 endpoint/model 适配边界。在线模型能力不由本文保证；Adapter 的能力声明与请求编码以当前实现为准。

## 协议拓扑

项目有三类 Provider 配置和四种线协议：

```text
ProviderSetting
├─ OpenAI -> OpenAIProvider
│  ├─ ChatCompletionsAPI  (useResponseApi = false)
│  └─ ResponseAPI          (useResponseApi = true)
├─ Claude -> ClaudeProvider
└─ Google -> GoogleProvider
```

| 线协议 | 请求入口 | 对话结构 | 工具调用/结果 | 流式终态 |
|--------|----------|----------|---------------|----------|
| OpenAI Chat Completions | `/chat/completions` | `messages[]` | assistant `tool_calls` / `role=tool` | choice `finish_reason`，`[DONE]` 只结束 transport |
| OpenAI Responses | `/responses` | 扁平 `input[]` items | `function_call` / `function_call_output` | `response.completed`、`incomplete`、`failed` |
| Anthropic Messages | `/messages` | `messages[]` + content blocks | `tool_use` / user `tool_result` | `message_stop` |
| Google Gemini generateContent | `:streamGenerateContent` | `contents[]` + parts | `functionCall` / `functionResponse` | candidate `finishReason`，EOF 本身不表示成功 |

Provider 类型决定原生协议族，`useResponseApi` 只选择 OpenAI 子协议，host 只选择已证实的 endpoint 差异，modelId 只决定模型能力和参数约束。未知 OpenAI-compatible host 不会因为模型名称被重新解释为某个供应商。

## 共用请求边界

设置页的非流式、流式和工具调用探测由 `ProviderSettingsApplicationService` 使用 `ReasoningLevel.AUTO`，
避免用通用参数默认 OFF 关闭强制推理模型。探测保留模型自定义 headers/body 和原临时请求身份，不改写聊天配置。

Chat Completions SSE 允许跳过顶层 JSON null 和可选空引用注解；非对象数组/标量帧仍报告格式错误，
非空 error 仍终止请求。空帧不构成成功终态，usage-only 帧及真正的 finish_reason 继续沿原解析和用量链处理。

模型目录匹配时，紧邻 `b/k` 的数量不参与版本 token 匹配，例如 8B、A3B、32K。
Qwen3-VL 的已登记尺寸与 Instruct/Thinking 使用完整型号模式，支持渠道前缀和量化后缀；
两者都有图片输入及工具能力，仅 Thinking 自动登记推理能力，既有用户显式配置不批量改写。
型号与版本区分依据 [Qwen 官方仓库](https://github.com/QwenLM/Qwen3-VL)；模型登记不保证任意网关支持同一参数。

### 传输与认证

四条文本协议的流式请求装配（包括文件编码）在 `Dispatchers.IO` 执行，`flowOn` 不改变下游收集上下文；非流式请求也在 IO 装配和读取。装配结束、创建 EventSource 之前复验协程仍 active，避免编码期间已取消的请求继续启动 HTTP。SSE 与 `readResponse` 继续拥有取消和关闭，无第二请求循环。

OpenAI 的 `chatCompletionsPath` 与 `responsesPath` 分别配置两种协议的相对入口，默认 `/chat/completions` 与 `/responses`。Responses 路径保留 baseUrl 前缀，拒绝替换 origin、查询、fragment 和明文/编码穿越。`FrozenProviderWireShape.OpenAI` 在 START 捕获两者，后续凭据更新不能修改请求路径；旧配置缺失 `responsesPath` 时使用默认值。

四种文本协议通过 `configureSessionHeader`，只在实际请求 host 为 `opencode.ai` 时将 `openCodeSessionId ?: providerSessionId` 写为 `x-opencode-session`，自动会话身份覆盖自定义同名头。标题、建议、摘要、附件识别辅助入口及探测使用专用的 `openCodeSessionId`；OpenRouter 请求正文仍只读取 `providerSessionId`。Google Vertex 使用实际 Google URL，残留 baseUrl 不影响判定。自动头带请求标记；宿主的 `ProviderSessionHeaderInterceptor` 在每次网络发送时移除跨 host 重定向携带的自动头，不修改用户手动配置的普通 header。该身份复用既有会话或辅助请求身份，不新增持久状态。

`TextGenerationParams`、`ImageGenerationParams` 与 `ImageEditParams` 的 `credentials` 是不参与序列化的 `RequestCredentials`。`UserSettings` 继续使用原 Provider 的 KeyRoulette；`Fixed` 使用单次请求的原始凭据，不按空白或逗号拆分，不访问轮换缓存。未提供固定 credential 时由私有 headers 提供认证；若同时出现自动认证同名 header 则在发请求前拒绝。四种文本协议及个人 OpenAI 图片生成/编辑共用该认证选择；企业图片不制造持久化 ProviderSetting，而由 `ManagedImageGenerationProvider` 按冻结 protocol 构造唯一 typed wire。

Provider `customHeaders` 先于请求级（Model／Assistant）headers 应用，名称大小写不敏感，后值唯一覆盖前值。
Adapter 自动认证头仍由凭据 owner 设置；固定凭据遇到同名头先拒绝，受管请求额外校验全部 Provider／请求级保留头。
START 捕获 Provider headers，后续凭据刷新不读取新编辑值；模型列表、embedding、余额和图像走同一配置合并规则。
余额请求指纹纳入有效 headers，避免编辑后复用另一请求身份的缓存。

### 企业路由与私有请求

`Routed` 表示平台 Relay 的完整 HTTP/HTTPS endpoint 与单请求 Bearer。`ModelExecutionService` 从冻结的 Platform execution、资源 ID 和 runtimePath 装配 URL，Google 流式额外保留 `alt=sse`，四种现有文本编码器和企业图片 typed client 直接使用此 URL，不再次拼接供应商后缀，也不读取用户 KeyRoulette。generation 与 interaction headers 由原捕获上下文提供，刷新只替换认证，不改变路径、模型或代际。

`OPENAI_IMAGES_GENERATIONS` 发送顶层 `model/prompt/n/size`；`DASHSCOPE_MULTIMODAL_GENERATION` 发送 `model/input.messages/parameters`，把冻结 canonical `宽x高` 只在 wire builder 转为 `宽*高`，并固定 `watermark=false`。两者的 URL 结果都只允许安全 HTTPS 公网目标，每跳重验并固定 DNS，不携带 Relay Bearer/Cookie，按图片上限有界读取且校验 MIME 与签名。

平台辅助文本生成复用流式编码器、`StepOutputAccumulator` 和 `RequestUsageReducer`，返回聚合结果，不建立第二持久会话。

Routed 请求带 `PrivateRequest`，禁止重定向、自动认证和透明重放，body 上限 10 MiB；非成功响应保留状态与脱敏后的原始 detail。只有 status=428 且 body 严格满足 `managed_snapshot_required`、forwarded=false、正 target generation 和合法 requestId 时，才触发原 interaction 停止及资源清理，并提示用户显式同步配置；不自动同步或重发业务请求。

Fixed 请求带 `PrivateRequest` 网络标记，宿主日志入口不记录其 HTTP 内容，`PrivateRequestBoundaryInterceptor` 在实际重定向边界拒绝跨 origin 转发。Responses 的 `instructions` 与 model/input/tools 一样归协议装配 owner，custom body 不能覆盖已组装系统提示。

### 日志与响应关闭

`ClaudeProvider` 不记录请求 body、逐条 messages、SSE data 或错误响应 body；OpenAI Chat/Responses 与 Google 流式回调也不把异常 message/原始响应记录到 Log，Google 不记录引用元数据。企业 Routed 和私有 Fixed 请求因此不会通过 Provider 自身的日志绕过 `PrivateRequest` 的网络日志边界。错误仍按原解析结果和 cause 传给调用方，日志仅记录异常类型及 HTTP 状态。

非流式文本、图片生成/编辑及结果下载、模型目录、余额和 embedding 的完整响应读取统一使用 `Call.readResponse`，由同一调用持有 HTTP Call 到响应体消费并关闭为止。取消等待响应头或阻塞读取成功/错误响应体都会取消原 Call；退出不依赖网络读取超时才结束。流式请求继续由各协议的 EventSource 生命周期负责取消。

### customBody 所有权

请求体的结构性字段只有 Provider builder 一个 owner。`customBody` 只提供高级扩展参数，不拥有结构性协议字段。
各 builder 声明自身保留字段集合（`RequestBodyOwnership`），`mergeCustomBody()` 必须接收 ownership 契约。

| 请求线 | builder-owned 保留字段 |
|---|---|
| OpenAI Chat Completions | `model`, `messages`, `tools`, `stream`, `stream_options`, `session_id` |
| OpenAI Responses | `model`, `instructions`, `input`, `tools`, `stream`, `store`, `include`, `previous_response_id`, `session_id`, `prompt_cache_key` |
| Anthropic Messages | `model`, `messages`, `system`, `tools`, `stream` |
| Gemini Generate Content | `contents`, `systemInstruction`, `tools`；另保留会改变响应形状的 `generationConfig.candidateCount`、`generationConfig.responseModalities`、`toolConfig.functionCallingConfig.streamFunctionCallArguments` |

发生冲突时在 HTTP 前抛出 typed 本地异常（`CustomBodyReservedKeyException`），stable reason 为 `custom_body_reserved_key`。
custom body 可以递归合并 builder object 的非保留 leaf，但不能用 scalar/array 替换 builder 已建立的 object shape；
这种 shape replacement 同样作为本地 ownership 冲突报告。
允许 custom body 继续配置的典型字段：`temperature`、`top_p`、token 上限、`reasoning_effort`、`thinking`、
`response_format`、`tool_choice`、`parallel_tool_calls`、路由/缓存/实验开关等 Provider 扩展字段。
Assistant 级 custom body 可随默认模型和 Provider override 切换协议，因此 UI 不维护跨协议保留键并集；
实际请求 builder 按当前协议 ownership 做唯一权威校验，避免把另一个协议的合法扩展误报为错误。

### 工具 JSON Schema 边界

`Tool.parameters` 与目录中的 `McpCatalogTool.inputSchema` 都使用完整 `JsonObject` 作为规范表示。MCP 目录由 `McpCatalogWire` 捕获原始响应，`McpCatalogTool` 保全完整定义，工具工厂直接投影 `inputSchema`；通用层不使用封闭数据类枚举关键字，也不按 host 删除定义。

OpenAI Chat Completions、Responses 和 Anthropic Messages 直接发送该文档。Gemini 使用官方的 `parametersJsonSchema` 字段，并移除该字段不接受的 `$schema` 方言声明；其余定义和引用保持原样。Provider 差异只能在对应协议 Adapter 处理，不能回写或降级通用缓存。

OpenAI-compatible 端点要求 function tool 的 `parameters` 至少是 object schema。`normalizeToolParameters` 在 Chat Completions 与 Responses 共用的 wire conversion 边界把 `null` 规范化为 `{ "type": "object", "properties": {} }` 等价 object schema；非空原始 schema 逐字段原样保留，尤其是 `$defs`、`$ref`、`oneOf`、`additionalProperties` 和 provider 扩展。默认化只发生在 OpenAI wire 边界，不影响 Claude/Google 等 provider 的既有 schema 规则。

### 流式调用身份与完成证据

`UIMessageChoice.toolCallSlots` 是只存在于 Provider `MessageChunk` 的 typed transport 关联：Chat 使用 `tool_calls[].index`，Claude 使用 content block index，Responses 使用 response-local 输出槽（`item_id` / `output_index` 是槽的别名），Gemini 完整 functionCall 按响应到达顺序分配槽。完整非流式 message 使用工具数组顺序。`StepOutputAccumulator` 在当前 Step 内将槽首次映射为新的 `localCallId`，之后仅按槽拼接；重复或空的 `providerCallId` 不会把不同调用混为一个。transport 槽不写入 durable Tool JSON。

流结束必须有协议完成证据。Chat 必须有真实 `finish_reason`；Claude 必须有 `message_stop` 和 `stop_reason`；Google 必须有候选的 `finishReason`；Responses 必须有 typed terminal event 或支持的 `[DONE]`。EOF 不能当作成功，已接收内容保留并以 INCOMPLETE 结束。token 上限类终态同样不执行未完成响应里的工具。真实 finish reason 进入 StepModelResult，不能用合成的 `unknown` 或 null 覆盖。

## 统一消息模型

`UIMessage` 保存协议无关的持久消息、显式 Step、媒体与 typed Tool；Provider metadata 保存必要的原协议回放状态。
字段以类型声明为准，request-only 投影不成为持久事实。

终态 Assistant 的 `replaySafeProjection()` 按显式 Step 保留连续的完整前缀。Step 必须为 `Continue` 或 `Final`、
具有 `modelResult`，且整批 Tool 都有匹配的 `stepId`、合法调用 envelope 和 typed replay Result；任一条件不满足即结束完整前缀。
后续不完整尾部仅保留安全 Text/Image 作为辅助上下文，移除 Tool、Reasoning 和不透明回放 metadata；存在可上传内容时追加未完成 marker。

`providerReplayProjection` 以 `completePartCount` 和 `hasIncompleteTail` 标记该请求投影，不写回 Conversation、Room、备份或 UI durable 状态。
`RequestAssembler` 移除 Step marker 时同步换算 `completePartCount`，严格端点只消费完整前缀。附件与媒体的资源投影见
[多模态上下文与资源持久化](multimodal-context-and-turn-durability.md)。

Provider 不直接消费 `UIMessage`。app 层 `RequestAssembler` 把最终 durable 投影一次性转换为 `ModelRequestMessage`
（`role` / `parts`（已丢弃 `UIMessagePart.Step`）/ `modelId` / `providerMetadata` / `providerReplayProjection`），
它是 `Provider.generateText` / `streamText` 与全部线协议 adapter 的唯一请求输入；durable-only 字段
（`id` / `createdAt` / `usage` / `terminalStatus` 等）不进入 Provider。装配链见 [`request-context.md`](request-context.md)。

### Token usage 规范模型

线协议 Adapter 只把单次 Provider 请求的 wire usage 归一化为 `ProviderUsageSnapshot`；它不读取历史消息、不跨请求
累计，也不决定 UI 展示。四线字段映射、request/turn 算法、完整性、持久化和消费者口径统一见
[`token-usage-accounting.md`](token-usage-accounting.md)。

`UIMessagePart.Tool` 保存调用身份与参数、typed interaction 和 replay Result；execution 副作用事实由 app 的 `tool_execution` 持久化。Provider 序列化时再展开为各自的 tool call/result 结构，数据库不会插入独立的持久化 `MessageRole.TOOL`。

Disclosure Snapshot 由共有 planner 在接纳位置投影：START 的初始内容可附在因果 USER 的前置 Text part，
后续 Step 的外部状态位于完整工具结果批次之后、下一次模型生成之前。自身成功写入仅回放原 input/output，
不追加同义 USER 通知。OpenAI、Claude 和 Gemini 沿各自合法 role/part 编码，不将 Snapshot 提升为 System / Developer；
相邻 USER 合并仍须保持完整工具结果、状态文本与原媒体 part 的顺序。共享接纳见 [`request-context.md`](request-context.md)。形状与注入时机见
[`prompts-and-tools.md`](prompts-and-tools.md)。

生成图片在解析源头就必须是可渲染 URL。Chat Completions 保留完整 `data:` URI，Gemini 使用真实 mime 组装 `data:<mime>;base64,<payload>`。合并层只把无前缀的 base64 碎片追加到当前图片，不会给已经完整的 URL 再补 `image/png` 前缀，也不会把两张完整图片拼成一条。

四个 adapter 共用 `groupPartsByToolBoundary()`：连续工具只有 `Tool.stepId` 相同才属于同一批次，
不同 Step 即使没有中间文本也依次回放 call/result，再回放下一批 call/result。Step marker 在请求装配时移除，
工具携带的 Step 身份仍保留这一边界；Gemini 不另用 `providerStepId` metadata 推断批次。历史 Step 的划分只归迁移器。

### Metadata 分层

| 类型 | 层级 | 保留内容 |
|------|------|----------|
| `ClaudeReasoningMetadata` | Part | thinking `signature`、`redacted_thinking.data` |
| `OpenAIReasoningMetadata` | Part | reasoning item ID、`encrypted_content`、来源 profile |
| `GoogleThoughtMetadata` | Part | `thoughtSignature`、function call ID、thought/草稿图、模型与 endpoint source |
| `OpenRouterReasoningMetadata` | Part | 仅 `openrouter.ai` 回传的有序 `reasoning_details`；旧会话无此字段时走可见 `reasoning_content` |
| `OpenAIResponseMetadata` | Message | 无状态完整历史回放所需的有序 `response.output` 批次、wire format、来源 profile |
| `AttachmentProjectionTextMetadata` | Part | 标识输入投影器生成的 request-only 附件事实文本；不持久化，不直接进入线协议 |
| `DiffMetadata` | Part | 仅供 UI 展示的 unified diff，不发送给 Provider |

不透明状态不能从可见 Text/Reasoning 无损重建，也不能跨来源随意复用。metadata 字段保持可空并向后兼容旧会话；更新工具 metadata 时必须 merge，不能覆盖 Provider 状态。

## OpenAI endpoint 身份

`OpenAIEndpointProfile.kt` 是 host 到内部 endpoint 身份的唯一来源。已识别 host 用于选择经验证的参数或 wire 差异；其他 host 为 `COMPATIBLE`。

endpoint host 只用于选择已知的协议差异（reasoning 格式、function output 形状等），
**不用于否决模型的图片输入能力**。`Model.inputModalities` 是模型图片能力的唯一配置事实；
`RequestMediaCapabilities` 是协议适配器对 USER / ASSISTANT / Tool.output 三个容器的静态映射。
自定义 OpenAI-compatible endpoint 按用户选择的 OpenAI-compatible 协议处理；
代理是否完整实现该协议，由实际请求结果验证。

```text
host
  -> resolveOpenAIEndpointVendor()
  -> Chat Completions 参数与兼容分支
  -> resolveResponseEndpointProfile()
  -> Responses wire/source/能力组合
```

这些枚举是内部协议状态，不是用户设置。不得新增持久化的“方言”开关来修复单个 host，也不得用 modelId 猜测未知网关背后的 endpoint。

独立文生图按 `imageEndpoint` 保留配置的 baseURL 前缀，普通 OpenAI-compatible host 走 `/images/generations` 与 `/images/edits`。
精确 `openrouter.ai` 使用 `/images` JSON API，编辑把授权图片作为 data URL 放入 `input_references[].image_url.url`；
不会在失败后切换另一个收费端点重试。`media_type` 保留响应真实图片 MIME，旧响应仍使用 `output_format`／PNG 默认。
`auto` 或空 size 省略，普通兼容 host 的显式尺寸原样传递；仅已确认不接受 size 的 `api.x.ai` 省略此字段，不按模型名或域名子串推断。
HTTP 失败通过 `formatProviderHttpError()` 变成带 `statusCode` / `errorCode` / `errorType` 的 `HttpException`。
OpenAI Images 使用 `error.code`（如 `moderation_blocked`、`content_policy_violation`、`rate_limit_exceeded`、`credit_balance_exhausted`、`invalid_api_key`）；
xAI Imagine 使用相同信封或顶层 `code`/`message`，并可能在 200 响应里用 `respect_moderation=false` 标记审核未通过。
个人与受管 Images 请求在消费解析结果前统一要求至少一个非空图片数据或 URL；HTTP 200 的空结果以
`image_generation_empty_result` 明确失败，全量审核拒绝仍保留 `moderation_blocked`，不改走其他端点。
这一步只检查响应内容是否存在，实际图片解码及持久化仍由原资源链负责。
`validateOpenAIImageRequest` 对精确 GPT Image 2／2.5 Sunburst／Flare 及已核实快照检查 Images 参数：
生成 JSON 在 custom body 合并后校验，编辑表单从同一合并结果编码，每个字段只发送一次；受管 OpenAI Images
沿同一校验入口，不开放受管 custom body。检查尺寸整除与比例、质量档位、背景与格式组合、压缩值及数量；
未知型号和独立 OpenRouter 图片协议不套用这些约束，不改写旧模型设置。动态像素／边长上限仍由服务端判定并保留错误。
Responses 工具没有开放上述 Images 参数，继续使用该工具自身缺省值；不能以 Images 参数校验推断 Responses 支持。
依据：[Images 生成参数](https://developers.openai.com/api/reference/resources/images/methods/generate)、
[Images 编辑参数](https://developers.openai.com/api/reference/resources/images/methods/edit)。
分类与工具回传见 [`turn-step-execution.md`](turn-step-execution.md) 与 [`prompts-and-tools.md`](prompts-and-tools.md)。

## Chat Completions

`ChatCompletionsAPI` 负责该 wire 的角色/part 编解码、流式槽与 endpoint 参数适配。

### System role 与 token limit

官方 OpenAI reasoning 模型需要 developer role 时由 `useDeveloperRoleForSystemMessages` 决定；兼容服务默认保持 system role。官方 OpenAI 与已识别 MiMo 端点使用 `max_completion_tokens`，其他兼容端点使用 `max_tokens`。

### Reasoning effort

项目级 `ReasoningLevel` 是统一 UI 枚举，不代表所有模型接受相同字符串。官方 OpenAI 变体由 `mapOfficialOpenAIReasoningEffort()` 映射；AUTO 省略参数，让 endpoint 使用默认值。

DeepSeek 直连的 Chat Completions 还发送 `thinking.type`：OFF 为 `disabled`，其他级别为 `enabled`。当前 Adapter 的 effort 映射如下，供应商协议入口见文末。

| 项目级别 | Chat `reasoning_effort` |
|----------|-------------------------|
| OFF / AUTO | 省略 |
| LOW | `low` |
| MEDIUM / HIGH / XHIGH | `high` |
| MAX | `max` |

DeepSeek 的 XHIGH 映射为 `high`，只有独立 MAX 才发送 `max`。OpenAI 的 `mapOfficialOpenAIReasoningEffort()` 则把 MAX 映射为注册模型对应的 `high` 或 `xhigh`；两种 wire 映射不能混用。

兼容网关可能把 `choices`、`delta`、`message` 或 `tool_calls` 显式写成 JSON `null`。Chat Completions 流式解析必须用 `jsonArrayOrNull` / `jsonObjectOrNull`，不能对 `JsonNull` 强制取数组或对象，否则会中断整轮生成。`tool_calls` 数组里的空槽、`function: null`，以及 Mistral thinking 的非对象首元素，同样按空值跳过，不能强制 `jsonObject`。

OpenRouter 直连 host（`openrouter.ai`）若返回结构化 `reasoning_details`，会保存在 Reasoning part 的 source-isolated metadata 中，并只在同一 host 的后续请求回传该数组。流式分片按到达顺序累积：相同 `id` 或相同 `index` 的条目合并 `text`/`summary`，新条目追加。其他 Chat Completions host 只回放可见 `reasoning_content`。

### Chat reasoning 回放策略

Chat 的 `role=tool` 结果只写 `tool_call_id` 和内容，不写 `name`；assistant 的 `tool_calls[].function.name` 继续保留。

DashScope 直连仅对 `usesDashScopeQwen38Effort` 精确登记的型号使用 `reasoning_effort`：AUTO 省略，OFF 为 `none`，LOW/MEDIUM 为 `low`/`medium`，HIGH/XHIGH/MAX 为 `xhigh`。自动字段仍由模型 `REASONING` 能力控制。其他 DashScope 型号保留 `enable_thinking` / `thinking_budget`（AUTO 只写前者）。custom body 仍最后合并；上述精确分支最终同时含 `reasoning_effort` 与 `thinking_budget` 时报告 `dashscope_reasoning_parameter_conflict`，不静默删改用户字段。其他 host、企业平台 origin 和未知网关不因 Qwen 模型名继承该协议；兼容端点 OFF 仍使用既有 `low` 映射。

Chat Completions 的 `ChatReasoningReplayPolicy` 由 `resolveChatReasoningReplayPolicy()` 统一解析。
策略有三个正交维度：`VisibleReasoningReplay` 控制可见 `reasoning_content`，`OpaqueReasoningReplay` 控制
source-isolated 不透明状态，`TerminalAssistantReplay` 控制非成功 Assistant 使用兼容 partial text 还是只使用完整
step 前缀。`includeHistoryReasoning` 只控制可选的可见历史思考，不控制 Provider mandatory state。

有效策略冻结如下：

| Endpoint/model | requestHasTools | includeHistoryReasoning | visible reasoning | opaque state | terminal Assistant |
|---|---:|---:|---|---|---|
| 官方 OpenAI Chat | 任意 | 任意 | `NONE` | `NONE` | `COMPATIBLE_PARTIAL` |
| DeepSeek 官方端点 | 是 | 任意 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPLETE_STEP_PREFIX` |
| DeepSeek V4 on compatible gateway | 是 | 任意 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPLETE_STEP_PREFIX` |
| DeepSeek/DeepSeek V4 | 否 | 开 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| DeepSeek/DeepSeek V4 | 否 | 关 | `NONE` | `NONE` | `COMPATIBLE_PARTIAL` |
| MiMo 官方端点 | 是 | 任意 | `TOOL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| MiMo 官方端点 | 否 | 开 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| OpenRouter | 任意 | 开 | `ALL_ASSISTANT_ENVELOPES` | `OPENROUTER_SOURCE_MATCHED` | `COMPATIBLE_PARTIAL` |
| OpenRouter | 任意 | 关 | `NONE` | `OPENROUTER_SOURCE_MATCHED` | `COMPATIBLE_PARTIAL` |
| 其他 compatible Chat | 任意 | 开 | `ALL_ASSISTANT_ENVELOPES` | `NONE` | `COMPATIBLE_PARTIAL` |
| 其他 compatible Chat | 任意 | 关 | `NONE` | `NONE` | `COMPATIBLE_PARTIAL` |

`requestHasTools` 在 request builder 顶部只计算一次（`params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()`），
同时控制是否写入顶层 `tools` 和 DeepSeek/MiMo mandatory replay policy。`tool_choice = none` 不改变 `requestHasTools`。

`ALL_ASSISTANT_ENVELOPES` 的范围是：经过上下文裁剪、输入 Transformer 和最后的 `replaySafeProjection()` 后，最终进入本次
Chat `messages` 数组的每个完整 assistant envelope，只要保存了非空 Reasoning，就写出 `reasoning_content`，
不论该 envelope 是否包含 `tool_calls`。B.AI 和其他兼容网关上的 DeepSeek V4 只由 `ModelRegistry.DEEPSEEK_V4`
明确登记的模型契约识别，不在 serializer 中新增 `contains("deepseek-v4")`，也不新增 B.AI vendor。

只有 `TerminalAssistantReplay.COMPLETE_STEP_PREFIX` 识别 terminal request projection：只序列化
`parts.take(completePartCount)` 的完整前缀，
不发送 partial assistant tail。`completePartCount == 0` 时该 terminal Assistant 不进入 DeepSeek Chat 历史。

OpenRouter `reasoning_details` 继续 source-isolated：存在 details 时不降级发送 visible `reasoning_content`，
其他 host 不能消费该 metadata。Provider `providerCallId` 只用于线协议，不作为本地工具执行定位键。

工具参数分片只合并到当前未完成步骤中定位到的那一个 Tool。后续请求即使复用相同 `providerCallId`，也不能改写前序已完成调用的参数、结果或 metadata。

### GPT-6 型号约束

`ModelRegistry` 精确登记 `gpt-6-astra`、`gpt-6.1-sol`、`gpt-6-sol`、`gpt-6-luna` 的图片输入、文本输出、工具及推理能力，
不沿用 GPT-5 的降档规则或推断未知后缀。Chat Completions 与 Responses 保留 LOW/MEDIUM/HIGH/XHIGH/MAX；AUTO 省略 effort。
Astra 与 6.1 Sol 拒绝 OFF/none；Sol 与 Luna 的 OFF 编码为 none。自定义 minimal、auto、ultra 等非法 wire 值明确失败。

`validateGpt6Request` 在 custom body 合并后校验最终请求：非 none 禁止 temperature/top_p/top_logprobs，Chat 另禁 logprobs，
Responses 不允许请求 output_text logprobs。显式非法采样参数报具体字段，不静默丢弃。
Chat 的新工具声明及历史工具消息只允许 Sol/Luna + none；其他组合要求用户选择 Responses，不自动切换协议或改写配置。
内置工具同样要求 Responses。关闭 REASONING 勾选不能绕过模型约束；官方和普通兼容网关保持各自端点身份。
Responses 仍沿既有无状态原始批次回放和工具结果链，不新增异步工具、WebSocket steering 或 reasoning configuration_update。
依据：[GPT-6 迁移与限制](https://developers.openai.com/api/docs/guides/latest-model)。

### GLM-5.3 Chat Completions

`ModelRegistry.GLM_5_3` 精确匹配 `glm-5.3`，默认登记文本输入/输出、工具与推理，不扩展到 Flash 或未知后缀。
`ChatCompletionsAPI` 对该型号接受 AUTO/LOW/HIGH/MAX，拒绝 OFF/MEDIUM/XHIGH，不静默降档；AUTO 省略 effort，
使用模型默认 max。官方 BIGMODEL 端点发送 `thinking.type=enabled`，显式档位写 `reasoning_effort`。
未知兼容端点仍走原兼容编码，不因型号登记推断其供应商或增加 thinking 字段；精确型号的档位校验仍生效。
合并 custom body 后校验 `reasoning_effort` 和显式 `thinking.type`，禁止非法 effort 或关闭思考。
该约束不依赖 REASONING 勾选，也不回写用户或企业保存的能力配置；其他 GLM 型号保留既有行为。
此处为 Chat Completions 适配，不据此宣称 GLM 的 Responses/Anthropic 入口已完成专项适配。
依据：[GLM-5.3 官方参数](https://docs.bigmodel.cn/cn/guide/models/text/glm-5.3)。

### MiMo 端点

`OpenAIEndpointVendor.MIMO` 识别 MiMo 官方端点 `api.xiaomimimo.com` 与 `token-plan-cn.xiaomimimo.com`。模型声明 `REASONING` capability 时，Chat Completions 按本地 reasoning 开关发送 `thinking.type=enabled|disabled`；只有 wire 上实际启用 thinking 时才不发送 `temperature`/`top_p`，非 reasoning 模型保留 sampling，token 上限使用 `max_completion_tokens`。Responses 使用 `ResponseEndpointProfile.MIMO` 和 `OpenAIResponseSourceProfile.MIMO`：OFF 映射为 `reasoning.effort=none`，AUTO 省略，LOW/MEDIUM/HIGH 原样，XHIGH/MAX 降为 `high`；sampling 是否省略复用同一 capability + level 判定。MiMo Responses 不请求 unsupported reasoning summary/encrypted content，也不复用其他 endpoint 的 opaque item。

### OpenRouter session_id

`TextGenerationParams.providerSessionId` 是 request-scoped 的 nullable typed 字段，不进入 Settings、Conversation、Room 或 backup。用户会话使用自身 conversation UUID，子助手使用 child conversation UUID；同一 turn 的多 step 与 `CONTINUE_USER_INTERACTION` 复用同一个值，fork 因新 conversation UUID 自然隔离。只有 `OpenAIEndpointVendor.OPENROUTER` 的 Chat Completions 和 Responses builder 把非空、长度不超过 256 的值写为顶层 `session_id`。标题、建议、附件检查和 Provider 连接测试等辅助请求不传该字段；这不改变标题/建议任务本身的原会话归属。

## Responses API

`ResponseAPI` 始终使用本地无状态完整历史回放，不使用 `previous_response_id`。默认端点显式发送 `store=false`；MiMo 不支持该字段，因此省略 `store`，但仍发送完整历史并保持同一无状态语义。这样 Provider 切换、持久化与离线恢复不依赖服务端会话。

仅实际 host 为 `api.openai.com` 的已验证 OPENAI profile 自动发送 `prompt_cache_key`：以固定协议前缀和
`providerSessionId` 计算 SHA-256 十六进制值，同一会话稳定、不同会话隔离，不发送原始身份。空身份与辅助请求省略；
其他兼容端点不自动发送，也不根据模型名推断支持。该字段由请求构造负责，custom body 冲突明确拒绝。
不新增缓存保留期或设置项。缓存键不保证命中；GPT-5.6 及之后官方将其用于可选的独立缓存归属，
此前模型还用于路由优化，见 [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)。

### 兼容网关返回的函数结果

部分兼容网关在 `response.output` 返回 `function_call_output`；这不表示官方 Responses 会执行客户端函数。
`ResponseAPI` 从本次实际请求体提取函数名，`responseFunctionResults` 仅将结果关联到当前响应中唯一的 call_id，
要求对应函数在已发送工具集中。未知、歧义、重复、缺失或冲突结果以明确 reason 失败，不按工具名跨响应匹配。
字符串（包括空字符串）、对象和数组保留原值，其他类型拒绝；只读显示将结构化值转为 JSON 文本，不创建文件资源。

流式结果先缓存在 `ResponseStreamState`，成功终态对账后才按原工具槽发布结果增量；失败、EOF 或取消不提前发布。
同一结果项的重复事件必须相同，终态不能改写或丢失已见结果。`ResponseFunctionResultMetadata` 保存原值、call_id、
实际响应端点（去除凭据、query 和 fragment）及来源 profile；`ToolResultStatus` 表示有可回放结果，不创建本地执行记录。
原 Step 索引据此排除已返回结果的调用，同名本地工具不会再次审批或执行。raw output 已回放该结果时不再追加一份。

### 响应显示顺序

Responses 内置 `image_generation` 的 model 取自 `Model.responsesImageModel`。用户模型的既有内置工具编辑页可选
`gpt-image-2`、`gpt-image-2.5-sunburst`、`gpt-image-2.5-flare`；显式保存的其他值原样保留。旧模型缺字段时使用
原来固定的 `gpt-image-2`，不扫描重写设置；关闭工具不清除所选值，也不向请求发送该工具。
该选择随原 Model 捕获，返回图片仍经 Base64ImageToLocalFileTransformer、ArtifactStore 与原 Turn 租约提交聊天附件，
不建立 GeneratedMedia 记录。独立 Images API 页面与 generate_image 继续使用各自已选模型和 GeneratedMediaStore。

`ResponseDisplayState` 只管理当前响应的显示槽，不保存第二份协议回放。item_id 与 output_index 在同一响应内
绑定且不可改换；元数据事件也接纳晚到别名。正文按内容索引与 text/refusal 类型区分，跨工具或其他显示片段后，
即使同一 message item 再次输出正文，也开始新显示段。推理、服务端事件和图片沿自身稳定槽更新。
完整非流式响应按解析后的原 Part 顺序赋槽。槽只经 `UIMessageChoice.contentBlockSlots` 进入合并器，不持久化。

`web_search_call`、`file_search_call`、`code_interpreter_call` 保存为只读 `ServerTool`，不据此启用新工具或创建本地执行。
服务端状态事件更新原片段，成功响应终态必须保留已见项身份且其状态已终止；只有这时标记完整。
搜索查询、来源和错误状态使用 ai 的类型化显示投影，URL 引用从原 message annotations 进入已有可点击引用列表。
图片结果替换原槽的占位图，不再前插第二张；聊天附件归属仍沿原 Artifact 链。
成功终态也发布图片结果，因此只在最终 output 返回图片时仍会进入附件处理；已发布占位/完成帧按同槽更新，不重复新增。
终态必须保留已见图片身份和已完成结果；空白/缺失结果、未结束状态、终态丢项或改图均明确失败。
可见段按事件次序保存，raw output 仍按服务端原数组和批次回放，不用显示列表重建不透明状态。
依据：[Web search output](https://developers.openai.com/api/docs/guides/tools-web-search)。

### Endpoint profile

| Profile | Wire format | Reasoning 表示 | 加密状态 | Function output |
|---------|-------------|----------------|----------|-----------------|
| `OPENAI` | OpenAI | summary items | `encrypted_content` | 可多模态 |
| `OPENAI_COMPATIBLE` | OpenAI | 按 OpenAI 形状 | 按 OpenAI 形状 | 假定遵循标准 function_call_output 契约（可多模态） |
| `VOLC_ARK` | OpenAI | endpoint 默认 summary | 请求 `encrypted_content` | 字符串 |
| `DEEPSEEK` | DeepSeek | `content[].reasoning_text` | 不使用 | 字符串 |
| `MIMO` | MiMo | reasoning text item | 不使用 | 字符串 |

相同 wire format 不表示不透明状态可跨来源复用。`sourceProfile` 进一步区分 OpenAI、通用兼容网关和方舟。

### 历史回放

```text
有 OpenAIResponseMetadata
  && wireFormat 匹配
  && sourceProfile 兼容
    -> 按 outputItemGroups 原样回放完整 output 批次
否则
    -> 从 UIMessage.parts 重建 input items
       -> Part 级 reasoning ID/encrypted content 仍单独检查来源
```

`outputItemGroups` 保留每次 response 的批次边界。回放时先追加某一 response 的完整 output，再追加该批函数调用的本地 `function_call_output`；不能把多次工具续轮压平成一个无边界列表。

后续 Step 失败时，`completedProviderReplayMetadata` 从完整前缀各 Step 的 `modelResult.providerMetadata` 恢复批次，
不复用整消息可能含失败尾部的聚合 metadata。只有前缀全部 Step 有一致 wire/profile 的证据才使用完整 raw 回放；
旧记录缺证据时保留原可见内容回放。`providerReplayProjection.completePartCount` 经 RequestAssembler 去掉 Step 后调整，
Responses 在完整 raw 前缀后另加安全尾部正文/图片及中断标记，不能因 raw 回放提前返回而丢掉可读尾部。
服务端片段记录的 endpoint 只说明来源；Responses 原始批次兼容仍按 wireFormat/sourceProfile 判断，不承诺同 profile 的不同 URL 隔离。

`AttachmentProjectionTransformer` 生成的附件事实遵循来源容器：USER 图片仍在 user input，工具图片仍在 `function_call_output`，ASSISTANT 顶层图片及其事实仍使用 assistant role。Responses 命中原始 `outputItemGroups` 回放时不能修改不透明 output，也不能从可见 UI parts 重建整个消息；`ResponseAPI.addAssistantItems()` 因此在完整原始批次和本地函数结果之后，只选择带 `AttachmentProjectionTextMetadata` 的 request-only 文本追加为 assistant message。普通可见回答不会重复，前置 system/developer 内容也不变化。

附件事实的外部位置统一写为 `path=/upload/<file>`，不发送 UUID handle 或重复 name；无可用文件路径时省略 path。
路径是否存在不代替图片能力判定。按需识图读取 ArtifactStore 校验后的内存快照，经共用 FileEncoder 规范化为 data URI，四种协议仍使用其标准
USER 图片编码；这不需要 Workspace、额外磁盘文件或专用 Provider 编码旁路。

`ResponseStreamState` 在每次请求内统一解析输出身份；function arguments 的 `item_id` 与 `output_index` 指向同一 transient 槽，后续事件可补充别名。只有 `call_id` 唯一对应现有槽时才允许按它关联；缺失、未知、歧义或互相冲突的身份显式报错，不回退到最近工具。`call_id` 只用于 wire 回放，`StepOutputAccumulator` 按 transport 槽分配 durable `localCallId`，不得用 `call_id` 合并并行调用，也不把 synthetic item ID 写入原始 JSON。

added、argument delta、arguments.done 和 output_item.done 共用参数状态。done 完整参数与已发出参数必须一致；只收到 added 而没有参数 delta 时，done 发出一次完整参数。重复 done 幂等。成功的 `response.completed` 或 `[DONE]` 必须具备完整参数证据，并把最终完整参数写入原始 function item；缺少完整 output 时只允许回放已由 done 闭合的状态，不能回放 added 时的空 arguments。完整 terminal output 必须恰好覆盖已发出的工具；重复 `call_id` 的相对顺序必须与本地工具顺序相同，否则显式失败，防止按 occurrence 回放时交换工具结果。

`response.failed` / `response.incomplete` 不执行成功回放校验，保留 Provider 原始 reason/detail、usage 和此前已发出的部分内容。连接在未收到协议终态时关闭视为异常，不能把半个 response 当作成功。

非流式 HTTP 200 的 `failed` / `incomplete` 响应同样不是成功结果：Adapter 先解码可用 output 和 usage，
再抛出 `ProviderResponseException`。它只在数据属性中携带 `MessageChunk`，异常文本不包含该 payload，
原 `HttpException` 保留为分类 cause；生成循环接收已返回的内容与 usage 后继续提交失败终态，不能执行其中的工具。

### DeepSeek Responses effort

| 项目级别 | `reasoning.effort` |
|----------|--------------------|
| OFF | `none` |
| AUTO | 省略 |
| LOW | `low` |
| MEDIUM / HIGH / XHIGH | `high` |
| MAX | `max` |

DeepSeek profile 不请求 OpenAI summary 或 encrypted content，并使用 `reasoning_text` 流事件与 item 内容。

OpenAI / 兼容 / Ark 路径重建 reasoning item 时：若 part 带有同源 `encrypted_content`，只回传 `id` 和加密状态，不附带可见 `summary` 明文。DeepSeek 的 `reasoning_text` 路径不受影响。

## Anthropic Messages

`ClaudeProvider` 把 System 放在顶层 `system`，把 assistant 内容表示为 `text`、`thinking`、`redacted_thinking` 和 `tool_use` blocks；工具结果放入 user `tool_result` blocks。

### 模型兼容的 thinking 配置

Claude thinking 不是一个可对所有历史模型统一发送的结构：

| 模型组 | AUTO | 显式强度 | OFF |
|--------|------|------------|-----|
| Claude Opus 5.5 | adaptive | adaptive + effort | IO 前拒绝 |
| Claude Sonnet 5.5 | adaptive | adaptive + effort | between_tools，工具之间仍可能思考 |
| `ModelRegistry.CLAUDE_ADAPTIVE_THINKING` | `thinking.type=adaptive` | adaptive + `output_config.effort` | `disabled` |
| 较早的 reasoning 模型 | 省略 thinking | `enabled + budget_tokens` | `disabled` |

`buildClaudeThinkingFields` 使用注册模型组选择形状：adaptive 模式下 MAX 为 `max`；XHIGH 仅对
`CLAUDE_XHIGH_EFFORT` 发送 `xhigh`，其他 adaptive 型号映射为 `max`。启用 thinking 时设置 `display=summarized`。
手动 budget 至少为协议下限且必须小于 `max_tokens`，过小的显式上限在构建时拒绝。
`CLAUDE_MANUAL_THINKING_WITH_EFFORT` 额外发送 effort，XHIGH/MAX 映射为 `high`；其余手动模型只使用 budget。
较早型号未声明 REASONING 时不发送 thinking；5.5 始终执行型号规则。具体型号由 `ModelRegistry` 及兼容测试维护。

当 thinking 实际启用时不发送 temperature；legacy AUTO 因为省略 thinking，可以正常发送 temperature。
5.5 在 CustomBody 合并之后验证最终 body：temperature=1、top_p∈[0.99,1] 接受并省略，其他显式值及任意 top_k 拒绝；
tool_choice 仅允许 auto／none。effort 必须是受支持字符串，Sonnet between_tools 只允许 high 及以下。
Opus／Sonnet 5.5 的 XHIGH 均发送 xhigh，保留 Claude 3 及旧型号行为。UI 使用实际解析出的模型限制选择，不重写已有无效配置。

### 不透明 thinking 状态

- `thinking.signature` 保存到 `ClaudeReasoningMetadata.signature`；
- `redacted_thinking.data` 保存到 `redactedData`；
- 工具续轮按原顺序、原内容回放；
- 较早型号切换到不同模型配置时剥离旧模型的 thinking/redacted blocks，保留可见答案和工具历史。

5.5 使用 `ClaudeReplayEvidence` 检查实际 endpoint、wire model、system／tools／messages 前缀、当前块和前驱链。
来源取最终响应地址，包括 Routed relay 和重定向；query 只保存摘要，不保存其中凭据。前缀忽略此前 thinking／redacted，
并只排除协议位置的 cache_control，工具 schema 中同名字段仍是内容。允许保留原 thinking 连续区间，不能跨中间缺口拼接。
缺失、未知版本或失配证据只过滤本次 wire，持久历史不变；同源同型号的本地配置 ID 变化不影响证据。

`ClaudeResponseMetadata` 累积本次实际排除块的摘要，经原 StepModelResult／checkpoint 保存，后续请求不能使这些块复活；
在已过滤前缀上生成的新块可形成新的有效链，无 thinking 的成功响应也携带该记录。没有旁路写入或独立状态表。
`ClaudeContentBlockMetadata` 保留原响应组及块位置，使同响应中交错的 thinking、text、tool_use 先完整输出为一个 assistant，
再附这一批 tool_result。`UIMessageChoice.contentBlockSlots` 只用于增量关联，签名按同槽追加；空正文的签名块和 redacted 块均保留。
这些可选协议字段通过原 JSON 和备份链兼容，旧数据不补造证据。依据为官方 [Preserved thinking](https://platform.claude.com/docs/en/build-with-claude/preserved-thinking)。

### 服务端搜索与暂停续传

Claude 的 `BuiltInTools.Search` 编码为 `web_search_20250305`，与客户端工具共同进入原 tools 数组；不启用动态过滤或
额外代码执行。客户端传输支持不保证任意模型或兼容端点接受该功能，拒绝仍保留服务端诊断。

`server_tool_use` 与 `web_search_tool_result` 保存为 `UIMessagePart.ServerTool`，保留原响应块顺序、原始内容和实际
响应 endpoint，不创建本地 Tool、审批或执行记录。流式 input_json_delta 按块类型路由，服务端块只在成功 message_stop
之后标记完整；搜索结果错误仍保留其 error_code。正文引用保存到 `ClaudeTextMetadata`，URL 注解是只读显示投影。
服务端块提供类型化 `ServerToolPresentation`，界面不解析回放 JSON，也不从结果存在推断活动执行状态。

`pause_turn` 由 Provider 在完整成功终态明确返回 `CONTINUE_ASSISTANT`，执行方式见 [Turn/Step 执行](turn-step-execution.md)。
没有 message_stop 的 EOF、未知 stop reason、失败或取消不续发。完整 Step 可回放服务端块，非成功尾部剔除不完整状态；
换 endpoint 时剥离不兼容服务端块及引用 wire 字段，普通正文保留。其他线协议不编码 Claude 服务端块。

`StepOutputAccumulator` 按 responseId/index 合并块，文本元数据后到时不覆盖已有正文，服务端完整块替换原片段；
引用跨响应合并去重。无槽位媒体前插同时平移已登记槽位的列表位置，不能使后续增量写到另一片段。
协议依据：[Web search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool)、
[Stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)。

### Prompt caching

开启 `promptCaching` 时，Provider 同时支持顶层 automatic `cache_control` 和显式 block 断点：最后一个 System block、最后一个 Tool，以及倒数第二条可缓存 user message。TTL 由 `ClaudePromptCacheTtl` 选择。

## Google Gemini

`GoogleProvider` 使用 `systemInstruction`、`contents[].parts`、`functionCall` 和 `functionResponse`。认证支持 AI Studio API key、Vertex API key 与 Service Account OAuth。
`buildContents` 在编完 `contents` 后合并相邻同 `role` 的项（`parts` 按序拼接）。这是 Gemini 要求 `user` / `model`
交替的线协议规范化，不是请求规划层的 USER 合并。TimeReminder、USER 角色注入，以及 `functionResponse` 后紧跟
下一条 USER，都在这一处完成相邻角色合并。contents 级合并只拼接已编码的 parts 数组，不把两个 Part 合成一个，因此带
`thoughtSignature` 的 Part 仍保持独立。

### Thinking 与签名

- Gemini 2.5 使用 `thinkingBudget`；Pro 型号不能保证完全关闭时不会强行发送 `0`。MAX 的 32000 预算在 Flash/Flash-Lite 上钳到 24576，Pro 钳到 32768。
- `ModelRegistry.GEMINI_3_SERIES` 使用 `thinkingLevel`，项目 XHIGH / MAX 收敛为 HIGH。
- `GEMINI_3_NO_MINIMAL_THINKING` 组的 OFF 映射为 `low`，其他已登记 Gemini 3 型号映射为 `minimal`。
  分组和相邻版本匹配由 `ModelRegistry` 维护，不在 serializer 复制型号名单。
- `GEMINI_4_ARGON` 只匹配 `gemini-4-argon`，登记图片输入、文本输出、工具与推理能力；不推断未知后缀或其他 Gemini 4 型号。
  Google 请求仅接受 AUTO/LOW/MEDIUM/HIGH，AUTO 省略 level，使用模型 medium 默认；OFF/XHIGH/MAX 明确拒绝，不沿用旧版静默降档。
  这一型号使用 `thinkingLevel`，不发送 `thinkingBudget`；普通 GenerateContent 的 `maxOutputTokens` 可省略或为 1..65536 的整数。
  自定义 body 合并后再次校验思考配置及输出上限，不能绕过约束。不新增 Interactions、background 或长输出自动续写。
  型号约束不改写用户或企业已存能力配置；能力勾选也不能关闭模型本身强制的思考行为。
  依据：[Argon 型号](https://ai.google.dev/gemini-api/docs/models/gemini-4-argon)、[默认值与迁移](https://ai.google.dev/gemini-api/docs/latest-model)。
- `GoogleThoughtMetadata` 在 Text、Reasoning、Image 和 FunctionCall 等 Part 上保留 `thoughtSignature`，并绑定
  产生它的 model ID 与 transport + 实际请求 endpoint host source profile；Vertex 使用
  实际固定的 `aiplatform.googleapis.com`。旧的无来源 opaque state
  不进入新的有来源请求。
- 带签名 Part 不能与相邻 Part 合并；空文本 Part 上的签名也必须保留。
- function call 的 API ID 保存并回填到对应 `functionResponse.id`。
- function calls 按 durable `Tool.stepId` 分批；Step 身份由 app Turn 协议分配。旧 transcript 通过统一迁移器
  升级，Adapter 不读取 `providerStepId` 或推测历史批次。
- thought image 的原始 `inlineData` 保存在 metadata 中，UI 可以显示占位而续轮仍能原样回放。

所有 SYSTEM 文本按消息和 Part 顺序用换行合入一个 text-only `systemInstruction`，包括图片输出模型。
函数声明与模型内置工具合并到同一个 `tools` 数组。Schema 清理只移除当前适配明确不使用的兼容字段，保留
Gemini 支持的 `enum`。grounding metadata 转为 `UIMessageAnnotation.UrlCitation`。

### 响应终态与流关闭

- `STOP` 是唯一正常完成终态；
- `MAX_TOKENS` 映射为 typed Provider incomplete；
- safety、recitation、malformed/unexpected tool、缺签名以及未知 finish reason 均 fail-closed 为 Provider failure；
- `promptFeedback.blockReason` 即使没有 candidates 也进入正式失败路径；
- SSE 未见 candidate 0 的 terminal `finishReason` 就关闭，映射为 incomplete，不能提交为成功；
- usage-only SSE 仍发布 usage snapshot，不因 candidates 为空而丢失；
- 非流式失败/incomplete 使用与 Responses 相同的 `ProviderResponseException` 交接已解码内容和 usage，
  不在 finish reason 校验时丢失已返回的 partial 响应；
- 请求体、原始 SSE body、opaque signature 和 base64 媒体不得写入日志。

`GoogleProvider.parseMessagePart()` 只接受 `text`（含空 text + signature、`thought=true` 的 Reasoning）、
完整 object `functionCall`（`name` 非空），以及 `mimeType` 以 `image/` 开头的 `inlineData`。其他 Part 字段
显式失败并只报告排序后的字段名，不写入原始 payload。当前客户端 function-call 增量仍只接受完整 `args` object；
会启用 partial args、多候选或新输出模态的 custom body path 被 ownership 拒绝，直到对应 decoder 明确实现。

### 支持边界

未知模型省略显式 thinking 控制。decoder 不支持 server-side tool、code execution、partial function args
和未知 Part union，收到这些内容时明确失败。公共 `RequestMediaCapabilities` 只声明原生图片；
音频、视频和文档由应用层附件投影处理，不由 Google adapter 私自编码媒体或猜测 MIME。

## ModelRegistry 的职责

`ModelRegistry` 按 modelId 推断输入/输出模态、TOOL/REASONING 能力及已知模型组的请求参数差异。
精确型号、家族和别名由注册表及相应测试维护；新增登记不自动改写用户已保存的模型配置。

注册表不决定 Provider 类型或线协议。模型组应服务于可测试的行为差异，在线型号清单不能成为
endpoint 选择或请求硬阻断条件。匹配测试必须覆盖相邻版本和别名，避免把 `gpt-5.10` 当作 `gpt-5.1`。
请求媒体能力从 `Model.inputModalities` 派生，不在投影或 endpoint host 另建例外清单；图标匹配只影响显示。

`ModelRegistry.HY4_PREVIEW` 只登记 `hy4-preview` 的文本输入/输出，不预填工具、推理档位或视觉能力，也不新增供应商。
用户或企业显式声明的能力仍由原配置解析，登记不改写已有模型设置；未知后缀不继承此精确身份。

## 维护与验证

协议修改从 `OpenAIEndpointProfile.kt`、对应 Adapter 和 `ModelRegistry` 的现有职责进入；
公共 role/part 转换由 `ProviderMessageUtils` 维护，消息和 metadata 位于 `ai/ui`。
测试应覆盖完整顺序、工具槽身份、来源隔离、不完整终态与取消，不能只验证可见正文。
Adapter fixture、设备与真实 endpoint 的证据分别记录，见[测试策略](testing-strategy.md)。

## 官方协议入口

- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)
- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses)
- [OpenAI Error codes](https://developers.openai.com/api/docs/guides/error-codes)
- [xAI Imagine Image Generation](https://docs.x.ai/developers/model-capabilities/images/generation)
- [xAI Debugging Errors](https://docs.x.ai/developers/debugging)
- [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages)
- [Anthropic thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [Gemini thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures)
- [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/)
- [Volcengine Ark Responses](https://www.volcengine.com/docs/82379/1956279)
