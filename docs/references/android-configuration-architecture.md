# Android 配置架构与资源边界

本文定义 Android 配置的 owner、提交与迁移协议、引用关系及个人/企业域边界。Assistant 逐字段运行语义见 [助手配置](assistant-configuration.md)，总体依赖与启动恢复见 [应用架构](application-architecture.md)。

## 1. 配置组成

```text
UserSettingsDocument（用户定义 + 公用/按域偏好）+ Applied Enterprise State
  → ConfigurationResolver（按原域/主体解析）
      → ResolvedConfiguration → application / query ports → 执行与 UI
```

配置分属 DataStore、轻量 UI preferences、Room、资源文件和可重建缓存，不能整体替换为远端 Settings JSON。企业平台接入复用现有 Session、配置发布与按域解析，平台令牌不进入用户配置。企业配置使用独立身份与定义，不通过全局 Settings overlay 覆盖用户配置。

用户模型的 `responsesImageModel` 由原 Provider 模型编辑命令保存并随 Model 捕获；缺失字段兼容默认 `gpt-image-2`，
与字段加入前的请求行为一致。选择不会改写其他模型或创建新的按域配置。企业发布模型与独立图片资源仍只读，
本地不向平台合同添加此字段，也不把用户模型的选择套到企业定义上；企业会话使用用户自有模型时沿原共享定义解析。

## 2. 配置 owner 与读写架构

### 2.1 Owner 表

| Owner | 当前载体 | 负责的事实 | 是否进入普通本地备份 |
|---|---|---|---|
| Built-in | `DefaultProviders.kt`、默认 Assistant/TTS/Prompt/Theme 常量 | 安装包内默认资源和反序列化默认值 | 不单独备份；读取时补齐 |
| 用户配置与偏好 | Preferences DataStore `settings` 的 `user_settings` JSON | UserSettingsDocument 内分开保存用户定义、公用显示偏好、按主体的选择、内部状态 | 以个人 Settings 投影导出 `settings.json` |
| Local UI preference | SharedPreferences `MeasixPilot.preferences` | 语言、明暗、启动/布局/搜索排序等轻量偏好 | 否 |
| Local durable resource | Room + `filesDir` | Workspace、Skill、会话级覆盖、资源文件 | 仅备份协议明确包含的域 |
| Local runtime cache | `cacheDir/lru_key_roulette.json` 等 | 可重建的 key 轮换/发现缓存；不是配置真源 | 否 |
| 企业状态 | `noBackupFilesDir/enterprise`；EnterpriseAppliedStore / EnterpriseSessionController | Session、完整配置、binding、独立 Feed 与当前空间；按 Deployment/User | 否 |

最近聊天 ID 位于 `ScopedUserPreferences.lastConversationId`，按个人域或完整 Deployment/User 保存。
`SettingsStore.rememberConversation(scope, id)` 沿原 writer 更新该引用；`id = null` 明确清除指定域的最近聊天，不改变其资源选择或其他域。
`ConversationHistoryPreferenceMigration` 在旧 Settings 键迁移之后，把已发行的 SharedPreferences `lastConversationId`
一次性归入个人域；已有域内值优先，DataStore 提交成功后才删除旧键，失败可重试。运行时不再读取全局旧键。
普通个人配置更新保留各域最近聊天与企业使用偏好；新聊天 Draft 不写入最近聊天，删除后恢复缺失会话由页面明确处理。

`lru_key_roulette.json` 当前以原始 API key 作为 map key 保存轮换时间。它虽然不是配置真源也不进入手工备份，仍是
未加密 secret 副本；企业 credential 不能复用这条缓存协议。

### 2.2 单一读写路径

模型上下文容量沿现有配置责任保存。用户模型的 `Model.contextLength` 是可空正整数，
从原提供商模型编辑器修改，共享该模型的空间同时受影响。企业模型的本地覆盖由
`ScopedUserPreferences.modelContextLengthOverrides` 保存，按完整企业主体与受管引用隔离，
不改企业发布定义或 Core 合同。企业模型设置页只编辑本域覆盖，失效引用显示原因且允许清除旧覆盖。
`ConfigurationApplicationService.setModelContextLengthOverride` 持有原 RealmSelection 授权，
Settings writer 在最新文档内比较 expectedTokens；过期编辑、会话失效和存储失败不能覆盖已提交值。
Resolver 提供有效容量及来源，START 捕获后同 Turn 继续使用。缺字段和删除覆盖均表示未知，
个人备份不含企业覆盖，个人恢复保留企业偏好，退出与主体删除沿原 scope 生命周期清理。
容量只用于上下文占比展示，不改变服务端限制、ContextBudget 或历史裁剪；历史分母规则见
[Token 用量](token-usage-accounting.md)。

`SettingsStore` 是用户配置与偏好的唯一写入 owner。企业配置归独立的 EnterpriseAppliedStore / EnterpriseSessionController。

```text
updateLocal(latest personalSettings transform)
→ normalizeForPersistence / canonicalizeForDataStore
→ UserSettingsDocument.withPersonalSettings
→ one dataStore.edit（user_settings）
→ durable success
→ userSettings（materializeForRead，只读个人投影）
```

- `userSettings` 用于共享用户定义编辑、公用外观与个人配置读取；不代表企业可用资源或执行授权。
- `SettingVM.settings` 直接读取 owner 的 StateFlow，首帧不再另造默认 Settings；未恢复时由 RouteActivity 的统一恢复门禁展示 Loading／失败与重试，恢复成功才开放设置导航。
- 新增提供商先选协议类型，再复用配置编辑器持有内存草稿；`ProviderSettingsApplicationService.createProvider/importProvider` 等待 Settings 提交后才发布成功和列表定位。导入重新分配提供商与模型用户引用，不覆盖已有定义或企业偏好；取消草稿不写入，失败保留输入。分享配置解析禁止异常附带原始 JSON 输入，避免自定义凭据泄漏，保留异常位置、类型与诊断堆栈。
- 域内目录、使用选择和执行通过 configuration application/query ports 消费 `ConfigurationResolver` 的 `ResolvedConfiguration`，不另落盘镜像。
- `ConfigurationQueryService` 将原 selection 的读取失败发布为带诊断的不可用状态，保留外层空间订阅；查询等待期间授权到期后，切换空间仍可重新建立目录读取。取消继续传播，失败不发布旧目录或空目录冒充成功。
- `SettingsStore.observeConfiguration` 的去重同时比较完整投影内容与目录 `catalog.keys` 的迭代顺序：Map 值相同但顺序变化仍发布。目录顺序只从原配置 List 经 Resolver 投影，不增加 order 字段或 UI 排序副本；无关配置变化仍去重。
- 所有配置修改持有同一 Settings writer lock。首次写入直接读 DataStore，不等待异步 UI 投影；观察器取得写锁后重新读取最新文档，不能用迟到值回退投影。
- 启动恢复显式等待 `SettingsStore.initializeForRecovery()` 的真实读取、migration 和解码；异常直接进入原应用恢复门禁。同一实例只保留一个发布 `userSettings` 的长期观察 job，重试先在 writer 锁外等待旧观察结束，再在原写锁内读取最新值并发布；合法的配置冷 Flow 仍可独立订阅。失败不重置已有投影、不清空文件或自动写默认配置。
- DataStore 拒绝提交时不发布；已取得写入所有权的提交在取消后仍等待回执并发布，再传播取消。按域偏好提交还保持原 Session 授权边界。
- 企业规则在 application/Settings 命令与执行边界复验，UI disabled 只是展示；企业定义不会覆盖或删除同名用户定义。
- `restoreLocal()` 和 `snapshotLocal()` 只操作个人 Settings 投影；恢复经 `ArtifactStore.restoreSettingsReferences` 校验配置文件引用。
- `pendingAssistantDeletions` 在 Settings 投影中为 `@Transient`，在 UserSettingsDocument.internalState 中持久化，恢复普通 Settings 时不得清空。

新增文件引用必须通过 `ArtifactStore.updateSettingsReferences`：Settings 写锁先于 Artifact 生命周期锁，校验后保持文件锁直到 DataStore 回执与创建所有权交接完成。普通配置提交拒绝绕过该入口新增引用。完整引用覆盖用户定义和所有主体保留的使用偏好，不从当前显示投影推断；具体文件保留、删除与恢复协议见 [多模态与持久化](multimodal-context-and-turn-durability.md)。

JSON 解码、个人配置物化和按域 Resolver 投影在 SettingsStore 的后台计算 dispatcher 执行；
发布仍在原 writer／recovery 协议中完成，不由后台 Flow 提前宣布配置提交。

### 2.3 用户配置结构与默认语义

`UserSettingsDocument.configuration` 是用户可编辑配置的持久事实；`Settings` 还包含运行消费所需的投影。
用户文档模型位于 `UserSettingsDocument.kt`，个人 Settings 投影位于 `SettingsStore.kt`；迁移与读取规范化分别由 `UserSettingsMigration`、`SettingsNormalization` 维护。

`UserSettingsDocument` 当前 schema 为 2。`UserSettingsMigration` 在 typed decode 前检查原始版本，
将 schema 1 的共享助手定义和各企业域 assistantUsage 中旧 `contextMessageLimit` 1–39 转为原生效值 40；
缺失 override 保持继承，其余字段、主体与资源身份不变。已有文档不得从旧分散键重建；未知版本拒绝迁移，原数据不变。
个人备份的 Settings 投影另用显式 `settingsFormatVersion=2`，导出即使省略默认值也写出标记。
`migrateLegacySettingsJson` 对缺标记／版本 1 的助手值执行同一转换，新格式保留 1–39，未来版本明确拒绝。
备份仍仅含个人设置，企业 usage 只在本地文档迁移，不进入个人包；恢复沿原 Settings owner 保留企业偏好。
不在这里复制完整属性清单，但必须区分四种默认语义：Kotlin 构造默认、旧 DataStore key 缺失默认、
`materializeForRead()` 后的有效默认、企业字段未提供。不能用其中一种推断另一种。
`Settings()` 的 `modeInjections` 构造默认包含 `DEFAULT_MODE_INJECTIONS`；`UserSettingsMigration` 在旧键缺失时
使用同一默认，但明确保存的空列表保持空。读取规范化不补造已删除的 Learning Mode。正常持久化写入 `user_settings` 的类型化结构；
JSON codec 使用 `ignoreUnknownKeys=true` 和 `encodeDefaults=true`。

| 配置类别 | 语义与边界 |
| --- | --- |
| 显示与主题 | `DisplaySetting`、主题及自定义主题是用户偏好；系统栏和布局由 [UI 策略](ui-architecture.md)解析 |
| 模型与派生任务 | Chat、标题、摘要、建议、生图、附件识别等选择分别解析，不能用当前聊天模型替代失效的显式引用 |
| 资源目录 | Provider、Assistant、提示规则、MCP、语音和搜索定义保留各自 ID；目录存在不表示当前域获准使用 |
| 本域使用选择 | `ResourceSelections` 与助手使用偏好按主体保存，不能反写企业受管定义或全局用户选择 |
| 备份与内部状态 | 用户备份配置与提醒独立于同步、清理和恢复状态；内部状态不成为 UI 可任意回写的聚合 |

`null`、空集合、未设置和显式失效引用必须按各字段协议区分；不得根据显示文本反推配置语义。
助手模型的三态、工具和提示字段见 [助手配置](assistant-configuration.md)。

个人文件服务器定义保存在 `UserConfiguration.webDavConnections`，当前引用保存在个人
`ScopedUserPreferences.currentWebDavConnectionId`；`Settings` 备份投影保全两者。
`ConfigurationApplicationService.changeWebDavConnection` 通过原 Settings writer 增量修改这两个字段，
首项保存时选中，后续添加不抢占，删除当前项清空选择；失效引用不回退第一项。
编辑使用原定义比较，迟到草稿不能复活已删除连接或覆盖其他编辑。企业域不消费这些个人选择。
个人连接地址必须是完整 HTTP(S) URL，不推断协议或企业托管地址；名称留空时使用已校验地址的主机名，
HTTP(S) 方案名不区分大小写。用户名和密码均为空时不发送认证头，任一非空时保留原值用于 Basic 认证。
`WebDavConnection.validated` 统一规范化地址并以类型化字段错误报告地址或用户名问题；
编辑页在测试或保存前复用此校验，Settings 提交仍复验。测试只读取服务器根信息，不保存；保存不要求网络测试成功。
配置读取失败保留草稿，设置页的显式重试重新订阅原查询，不要求切域，也不自动循环重试。
Settings 在创建或更改地址、用户名、密码时重新分配 `accessRevision`；重命名保留该身份。
执行时核对捕获的身份，配置值快速往返或删除重建不能恢复旧访问；密码轮换不清除同一远端目标的待核实写入。

### 2.4 用户配置文档与迁移

`UserSettingsDocument` 的 `schemaVersion`、`configuration`、`preferences`、`internalState` 为必需字段，缺失或版本不支持时拒绝读取；其 schema 与 Room、应用和备份版本独立。

- `UserConfiguration` 保存现有 Provider/Model、TTS/ASR、MCP、Search、Assistant、注入/QuickMessage/标签、辅助提示词、备份连接及 UserProfile。用户昵称/头像不重复放入显示偏好。
- `UserPreferences.common` 保存 DataStore 原有的外观、DisplayPreferences、播放速度和提醒；`scopes` 以完整 ConfigurationScope 保存 ResourceSelections、企业域的 AssistantUsagePreferences 和 GatewayPreference。Gateway 偏好随 Deployment、User 与资源 ID 隔离；旧顶层 gateways 不具备主体归属，迁移不猜测归属。SharedPreferences 表中的偏好仍由原 owner 管理。
- 模型组折叠保存在 `ScopedUserPreferences.collapsedModelGroupIds`，使用稳定 Provider ID（企业目录组为 `enterprise`）并按完整主体隔离。`ConfigurationApplicationService.setModelGroupCollapsed` 增量修改最新文档，提交失败不发布；查询通过 `ConfigurationQueryService` 返回诊断。搜索临时展开命中组，不保存临时状态。个人 `Settings` 备份只投影个人折叠偏好，恢复不覆盖企业主体。
- `DisplayPreferences.conversationSortOrder` 是共用显示偏好，缺失时为 `UPDATED`。个人备份和 Settings 投影保全此字段；它不进入企业配置或修改业务最近会话的定义。
- `common.configuration.ConfigurationReference` 保留原用户 UUID 或企业 deploymentId/原始字符串 ID，不生成替代 UUID。个人引用的 JSON 仍是原 UUID 字符串；企业引用序列化为 `managed~deploymentId~资源ID`。ConfigurationScope 企业身份只包括 deploymentId/userId，URL、域名、端口、服务器位置、企业名称和用户名等可变属性都不参与身份。Settings 投影只提供个人配置；企业聊天、子助手、辅助模型、MCP、语音与配置界面通过原域的 `ResolvedConfiguration` 读取资源和选择。
- `EnterprisePrincipalPreferencesMigration` 在 DataStore 打开前定点转换 `scopes.scope.authority`、资源选择、Gateway 与助手使用偏好的引用字段；预设消息复用 `LegacyEnterpriseTranscriptMigration`，只转换 `modelId` 和子助手目标 metadata。模板、正文、正则文本、Header、Body、Skills 与未知 JSON 字段不作为身份解释。同一平台主体因多个旧 URL 来源产生的 Settings scope 按序列化顺序以后出现的标量为准、逐资源合并偏好，MCP Catalog 保留最高 managed generation/revision 的可重建缓存。Room 的同类持久字段由 `Migration_12_13` 在数据库事务内改写；转换时严格验证非 Personal scope 与 managed reference，任一异常使整笔迁移回滚。正常解析器只接受新格式，不保留旧格式 fallback。
- 已退休 local 示例或导入来源的数据使用 `RetiredLocalEnterpriseIdentity`：将原 `sourceNamespace` 与 deploymentId 的长度帧 UTF-8 字节编码为 `retired-local.` 保留 deploymentId，原身份可完整解码。scope、引用及 MCP 使用同一转换，userId、资源 ID、关系、正文与 payload 不变，不与同名平台主体合并。当前 serializer 校验保留编码的规范性；`EnterpriseConfigurationCodec.validateIdentity` 拒绝该命名空间取得平台 Session。退休记录仍归原持久化 owner，不开放 local 执行或新的可编辑域；个人恢复及个人备份合并保留这些非 Personal 数据。旧 manifest 不受支持时由 Enterprise 发布 Failed 并保留原文件，个人恢复不等待其成功。
- Settings、MCP Catalog、Conversation、Memory、Artifact 与媒体缓存/查询均以稳定的 `deploymentId + userId` scope 归属；修改企业地址不会复制或清空缓存。`Migration_12_13` 同时把高频域内查询索引改为 scope 前缀，保留 Child 外键、全库恢复和生命周期查询所需的非 scope 索引，不增加第二套缓存或地址到身份的映射。
- `UserSettingsMigration` 在旧 OCR/Search/MCP 迁移之后，将旧键转换并在同一次 DataStore 迁移提交中移除；MCP Catalog 的 pending staging 保留给 Catalog owner。旧资源或 tombstone 解码失败会中止迁移，原输入不变。正常读写只访问新文档，没有旧键 fallback。
- 用户定义及其配置绑定只接受 User 引用；按企业保存的选择允许 User 或同 authority 的 Enterprise 引用，拒绝外域引用。会话、Message、Turn、文件与 Workspace 的自身 ID 继续使用 UUID。
- 旧 developer_mode 键由迁移清除，旧备份中的字段由 JSON codec 忽略；它不再控制运行行为。
- 写个人配置会保留其他主体的偏好；ResolvedConfiguration 仍为只读内存投影，不另落盘。

## 3. 企业身份、配置同步与执行

`PlatformEnterpriseService` 编排 Discovery、Enrollment、Bootstrap、Snapshot 和凭据网络请求；`EnterpriseSessionController` 串行发布身份、连接与 Applied 版本；`EnterpriseAppliedStore` 以 `noBackupFilesDir/enterprise` 的单 manifest 指向不可变配置和执行描述。用户定义与偏好仍归 Settings。`PlatformSnapshotMapper` 映射平台公开资源，当前 `gateways` 固定为空；保留 Gateway 类型和偏好不表示平台已提供这类资源。

### 接入与身份恢复

扫码、相册识码和粘贴共用 `EnrollmentMaterialParser`。资料使用 `formatVersion=1`、`kind=PLATFORM_ENROLLMENT`，要求 `platformUrl/code/expiresAt`；拒绝未知或重复键、null、未知版本/kind 与非法时间。UTF-8 输入最多 2048 字节，code 为 1–128 个 Unicode code point；到期时间按当前 RFC3339 UTC 子集解码为 `Instant`。

`platformUrl` 是规范化 HTTP/HTTPS origin，允许域名、IP、IPv6 和显式端口，拒绝 userinfo、query、fragment、非根 path 与非法端口。解析只产生 `EnterpriseJoinConfirmation`；用户确认后才发起网络请求，取消或被替换的确认不执行接入。

兑换成功先持久保存 pending enrollment，再由 Bootstrap 核对 Session/User/Device/Deployment 后发布身份。临时失败保留 pending，启动恢复继续 Bootstrap，不重复兑换一次性 code。refresh credential 与 pending idempotency key 加密、原子持久化，access token 仅在内存。接入成功不等于配置已同步或可以执行。

installation ID 在本机企业连接生命周期内稳定。`enrollment_expired`、`enrollment_already_used` 与 `installation_user_conflict` 分别表示过期、已使用和本机绑定冲突，不混用诊断。普通换用户通过显式本机重置清除 installation ID；不能自动换 ID 绕过限制。管理员 hard delete 完成后，Core 可允许同一 installation 绑定新 principal；Android 只有在 Bootstrap 确认新主体后才清除旧 `IDENTITY_DELETED`。若 pending 主体也被删除，原子移除 pending credential 并保留 `SIGNED_OUT + IDENTITY_DELETED`。相同 username 不恢复旧主体、凭据或数据。

身份与配置正文分别恢复。manifest/凭据有效而配置读取、hash 或领域校验失败时，保留原 Session、Applied 引用和空间选择，以 `configurationError` 表示正文不可用。保留身份的导航提交不依赖正文可读；新 Applied 发布仍须完整校验。企业历史按原主体和身份时效授权，不依赖当前助手目录，也不把缺失助手/模型替换为个人默认。企业校验失败不阻断个人数据恢复；整体恢复顺序见 [应用架构](application-architecture.md)。

### 配置格式与兼容

Android 支持的 Snapshot 集合由 `PlatformSnapshotCompatibility.supportedSchemas` 定义，目前为 4、5；Applied manifest 独立使用 `ENTERPRISE_MANIFEST_SCHEMA_VERSION`，目前为 7。不能按 APK 版本、最高格式号或连续区间推断兼容性。

Core 导出的 `contracts/platform/client-control.openapi.yaml` 与 `manifest.json` 是 wire 来源。`tools/generate-enterprise-wire.py` 核验 LF 规范化摘要，从 Core 的版本化定义生成唯一 `PlatformWire.kt`；`--check` 检查漂移。仓库固定导出使普通构建不依赖 sibling checkout，共享 cases 同时覆盖 v4/v5，并保留 v4 golden。接入和 Portal 合同另固定于 `app/src/test/resources/contracts/portal/`，只能随 Core 的 `api/generated/android/portal/` 更新。

Discovery 与 Bootstrap 先校验控制协议及身份，不因 Snapshot 能力不匹配拒绝有效身份；同步非零目标配置时才校验能力交集，实际下载格式还必须由本次 Bootstrap 宣告。`PlatformWireCodec` 在有界严格 JSON 解码后先检查外层正整数 `schemaVersion`，再按该版本的 Android DTO 投影解码；不将非法 v5 降级为 v4。成功响应和 Applied 正文共用 4 MiB 上限。

v4 与 v5 的 MCP/助手 DTO 独立生成。`PlatformWireCodec` 先验证 v4 的端侧投影，再从已解码 DTO 把旧 MCP 服务映射为 ALL、
把去重后的 `mcpServerIds` 投影为 ALL bindings；保留原 schemaVersion，不将非法 v5 降级。
v5 必须提供服务 `toolAccessMode/allowedTools` 与助手 `mcpBindings`，模式的空/非空关系和服务器授权上限由
`EnterpriseConfigurationCodec.validateConfiguration` 校验。具体工具交集及执行复验见 [MCP 架构](mcp-architecture.md)。
`EnterpriseAppliedStore` 从现有执行描述读取 `snapshotSchemaVersion`，随 `LoadedEnterpriseState` 发布到
`EnterpriseState.Available`，供 resolver、查询和执行统一区分 v5 强制集合与 v4 绑定上限；该派生值不另存一份。

`EnterpriseAppliedStore` 将 manifest 6 的旧 MCP 配置转换为同等 ALL 规则，在校验原 hash 后写新不可变 revision，
最后原子发布 manifest 7；旧文件不改写，原 Session、凭据、release/hash、格式版本和空间选择保留。
未记录的历史格式版本仍为 null。manifest 5 的身份迁移同时完成这一转换；正常存储读取没有旧字段 fallback。
迁移 staging 失败保留旧 manifest，可重试；原正文损坏时保留身份与 Applied 修复事实并报告 `configurationError`。

### Core 响应的兼容与校验边界

Android 按实际消费用途定义响应投影，不把服务端完整发布/编制 schema 当作端侧的字段白名单。
`PlatformWireCodec` 在已支持协议版本内递归忽略未进入本端 DTO 的字段，不要求逐个登记扩展名称；
字段存在不代表权限、功能或默认值。升级 Core 的构建号或增加说明字段不应导致整份配置失败。
Snapshot 必须先识别 `schemaVersion`，未知合法版本独立报告不兼容，不能降级解析或猜测新语义。

保留的校验必须能说明用途：身份与部署/Session 关联、发布 generation/release/hash 和 ETag 一致性、
已消费字段的必填/类型/null/范围、执行协议及授权/工具范围/确认策略、资源唯一性与引用闭合、
运行路径与文件条件、开场正文与顺序、额度的模式和数量。缺失可选字段只使用已定义的缺省语义；
已消费字段错型或 null 不转为缺省，未知权限或执行枚举不猜测为允许。
展示枚举只有先定义中性展示或局部不可用行为后才可扩展；未知错误 code 保留为操作失败，不推断身份撤销。

`generate-enterprise-wire.py` 从固定 Core 合同生成端侧投影，新增生成字段前先确认实际消费者。
当前 Snapshot 发布元数据、v4 Starter 废弃说明和 Problem 的 Admin activation/draft revision 不参与 Android
显示、持久化或执行，由生成器排除；不为这些字段的必填、格式或变化设置接纳门槛。
v4 适配只转换已验证 DTO，原始扩展字段不能在 v5 DTO 中重新取得含义；原发布版本与 hash 不变。
MCP Tool 原始定义及其 JCS contractHash 使用独立的完整定义合同，扩展仍参与摘要，不能套用配置投影的忽略规则。

宽容限于响应读取。Client 提交命令、Bridge 请求/params 和扫码接入资料仍保持明确字段及操作意图；
本地存储继续由各 owner 的格式与迁移管理。JSON 语法、重复键、嵌套和正文大小限制仍保护读取边界。
`PlatformWireCodec` 对已消费字段的错型、null、缺失和枚举错误提供稳定 reason 与字段路径，不回显原值或响应正文。
候选仍沿原接纳和提交链完整验证后原子应用；失败保全身份、历史和原 Applied，成功同步即可恢复，不清库或自动重放。

`PlatformSnapshotMapper` 与 `EnterpriseConfigurationCodec` 都校验领域约束。`ManagedPolicy` 的各项默认引用可省略，缺失表示未设置，不取目录首项或复制 Chat 默认；非空模型引用须指向同快照内已启用模型，附件检查还要求 IMAGE 输入。可选 `imageGenerators` 缺失为空集合，已消费字段的显式 null、错型和无兜底的未知枚举仍拒绝；响应扩展按本节兼容规则忽略。Starter 的 v4/v5 条件和开场保全规则见下文。

Applied 旧格式只经显式持久迁移进入当前格式；先核验原 revision hash 再解码，不在正常读取时回退旧格式。历史 Starter 缺少 opening 时保留 null，不补造正文或重写 release/hash。`EnterpriseExecution.Platform.snapshotSchemaVersion` 持久保存下载时验证的版本；历史缺失为 null，不根据字段形状、Discovery 或客户端版本补造。

Snapshot v5 的可选 `webdav[]` 经 `PlatformSnapshotMapper` 保存为 `EnterpriseConfiguration.webdav`；
缺省及旧 Applied 缺字段均为空，不触发强制同步。v4 仍先经独立 DTO 投影，未知 DAV 字段不会取得 v5 含义。
`EnterpriseWebDavResource` 只持平台公开定义，根模板必须精确匹配自身资源 ID，不进入供其他资源使用的 `runtimePaths`。
`PlatformWorkspaceClient` 保留本人投影的唯一 `fileExchangeWebdavResourceId`；DAV 权限缺省、null 或未知字符串时
只关闭 DAV 并返回 `webdav_access_mode_unavailable`，原 Files 能力仍按其自身字段解释。其他已知字段的类型校验不放宽。
`ConfigurationQueryService.observeWebDavConfiguration` 合并本域定义与 `RemoteWorkspaceService.observeDavProjection`，
唯一当前项由 `fileExchangeWebdavResourceId` 决定；查询失败保留指定身份和完整诊断，但不报告可用权限。
`WebDavTarget` 固定原 Session、平台连接、资源及空间绑定；个人目标固定连接修订，显示名称修改不改变访问身份。
`WebDavApplicationService` 在执行时复验这些身份，企业请求通过原平台 operation lease 注入 Bearer，
仅明确未转发的认证拒绝允许一次刷新；PUT 输入不自动重放。切域、改址与退出编排取消并等待原 DAV 工作。
最终本地交付通过 `withEnterpriseWebDavAccess` 在原 Session 和配置 owner 边界复验，网络 IO 不持这些锁。
平台缓存令牌保留 30 秒提前刷新窗口；刷新响应接纳后按实际到期时间判断新令牌能否用于本次请求，
不能把提前刷新窗口当作最低令牌寿命。即使 access token 在提交时已经过期，轮换后的 refresh 凭据仍须先保全。

同步仅在缓存版本同时受客户端和最新 Bootstrap 支持时使用 ETag；304 可复用该配置并上报 Applied。未知或不再支持的缓存执行无条件完整下载；无可复用缓存的 304 以 `snapshot_304_without_cache` 拒绝，保全原 Applied，不循环重试。同 generation 的完整已验证候选可补齐缺失版本证据或修复损坏正文，但低 generation、release/hash/runtime path 冲突仍拒绝。v4 升级为含开场的 v5 由服务端发布新 generation/release，不改写旧发布。

`EnterpriseSnapshotCompatibilityException` 保留服务端格式与客户端集合，不把空集合或混合不匹配一律说成应用过旧。成功 Snapshot 的超限、解码、身份/ETag 或映射失败归 `EnterpriseSnapshotContentException`；网络读流、HTTP、持久化错误和取消保留原分类及 cause。较新格式提示更新应用，过旧格式提示管理员处理，不捏造最低应用版本或下载地址。

### 同步、导航与执行准入

`EnterpriseSynchronizationService` 合并同一主体/Session 的同步，瞬态结果绑定原 `RealmAccess.Enterprise`，不另落盘配置或执行许可。取消等待者不撤销共享工作；结果发布复验 Session，旧任务不得覆盖新主体。命令返回成功、失败已呈现或已被替代，UI 不重复发布通用错误。成功保存同步时间；Applied 回报失败保留已提交配置和诊断，下次同步可重报。

配置同步只由以下入口触发：原生空间页显式同步、Portal 显式 `refresh`、用户确认接入/重新登录后的初始化，以及启动时刚完成同一 Session 未完成 Bootstrap 后的一次初始化。初始化失败不自动重试。已有 Session 恢复、空间切换、页面进入、前后台切换、Portal 开关和执行 barrier 都不自动下载 Snapshot；凭据刷新、工作区与预算查询不是配置同步。

有效身份在 `CONFIGURATION_PENDING` 也可进入企业空间；`readPresentation` 与 `switchRealm` 共用数据访问规则，选择可持久恢复。历史查询不等待当前配置正文。同步和本地配置错误由企业页投影简短原因与可复制诊断；个人会话不显示企业异常。pending Bootstrap 诊断只属于原 Session，替换 Session 后不得写到新接入状态。已观察到的同步失败只能由对应 Session 的成功同步清除。

执行前 `prepareExecution` 查询 Core Managed State，要求非零 generation、READY、版本一致且未阻断；`requireExecutionSupport` 还在开场、执行读取与租约入口检查持久化网络版本。缺配置、未知/退役格式、代际不符或已有同步失败时拒绝本次执行并提示手动同步，保留原 Applied。缓存可读不代替执行准入，也不限制历史、本地退出和个人空间。

`EnterpriseExecutionLease` 固定原 Session、Applied binding、连接与交互，执行仍须逐次授权。有效 `ManagedSnapshotRequired` 永久关闭原 lease，原任务停止且资源释放后结束；不自动同步、重放或因新配置到达复活旧任务。租约清理失败保留原 owner 供重试。

配置详情的 `readPresentation` 只读取同一 manifest 引用的公开 releaseId、snapshotHash 和已保存格式版本，不调用执行准入、不暴露 runtimePaths/凭据。历史 null 显示未记录；generation 不是服务器最新版本或 APK 版本，未保存的发布时间不能以同步时间代替。`EnterpriseResourceDetailTarget` 固定原选择、Session、generation 和资源引用，正文按需复验读取；检查禁用 Starter 不授予使用资格，个人空间查看已连接企业也不授予企业执行权。

### 地址变更、退出与清理

平台地址是可变连接参数，不是 deployment 身份。`changeAddress` 先验证同一 deployment、原用户/设备/Session，再由 Session owner 提交地址与必要的轮换凭据。在途请求继续使用捕获的原连接；验证失败保留原连接及 Portal，已发生凭据轮换时不能回退失效凭据。提交前后的 Portal 关闭、站点清理和请求排空由同一屏障确认。

退出、到期、撤销和身份删除先持久发布 CLOSING，停止新操作并等待原 operation/execution lease。`EnterpriseExitService` 只处理原 Session，重复请求合并；`IDENTITY_DELETED` 是更强持久终态。远端注销失败仍须完成可恢复的本机清理并保留诊断。

本地到期计时只唤醒 `expireIfCurrent`，在 Session 锁内复核原身份与最新期限；旧计时和提交失败后的重试不能撤销已续期 Session。服务端明确的到期/撤销/删除按原终态协议处理。`401 invalid_credential` 仅证明 access token 被拒绝；服务按原 Session/连接串行恢复凭据，只有 refresh credential 明确终态或远端 Session/主体终态才退出。新 token 再次被拒绝仍保留本次错误，不伪称登录到期。

`EnterpriseIdentityDataDisposer` 按 principal 编排各 owner 清理。`EnterpriseDataResetService` 先持久保存“仅删除接入”或“接入和全部企业历史”的 reset intent，由应用 scope 的 worker 持有该操作；页面取消只取消等待，不取消已接纳的清理。重试复用尚在执行的原 intent，UI 区分 running、pending 与 failure。服务复用关闭屏障并在重启后继续；启动 resume 保持 `ApplicationRecoveryCoordinator` 门禁开放前的原恢复顺序，更强删除终态可扩大清理范围。任何分支都不删除个人数据，也不依赖 Core logout 成功；损坏 manifest 不能通过回读旧值假装重置成功。

显式重置先由 `EnterpriseResetPreview` 固定原选择、Session 身份和所有企业 Runtime 的待发版本。
确认只显示当前企业空间数量；个人空间或存储异常时不披露企业数量，始终说明全部企业空间的清理影响。
两个模式都会在停止实际工作后清除企业待发内容及附件；KEEP_HISTORY 保留持久历史，个人待发内容不受影响。
确认范围复验与原 reset intent 的持久接纳在同一 Session 锁内完成，新增或编辑使旧确认失效。
原 `EnterpriseDataResetStore` 中未完成的 intent 同时阻止普通企业准入和新接入；即使之后写 CLOSING 失败，
也不能恢复 READY 准入。它仍是原重置流程的唯一持久事实，Session 只读该事实；文件变更通知用于唤醒授权投影，
不复制可编辑的重置状态。原关闭、重试和启动恢复入口可继续推进该 intent，不要求再次确认。
接入码交换尚未结束时重置返回 `enterprise_enrollment_in_progress`，避免丢弃已取得但尚未登记的凭据。

接入与配置 wire 以固定的 Core 导出 [Client Control 合同](../../app/src/test/resources/contracts/platform/client-control.openapi.yaml)及其 [来源 manifest](../../app/src/test/resources/contracts/platform/manifest.json)核对。Android 的共享 cases、本机 HTTP 与二维码 round-trip 只证明各自边界，真实 Core、相机/相册及发行版验收见 [测试策略](testing-strategy.md)。

APK 的平台合同身份来自同目录 `protocol-baseline.json` 和 Portal manifest。`tools/android_contract.py` 与
Gradle 配置阶段均核验两份 manifest 的主合同、Core 支持集合、基准 Core 和基线 LF SHA-256 一致，
并校验 Client source/format/sourceHash、Portal Bridge 版本及完整材料的来源与实际字节摘要。
`generate-enterprise-wire.py` 在解析 schema 前完成校验，继续保留 `PlatformWire.kt` 的 Core source SHA256 header。
缺失、错误类型或不一致直接失败；普通构建只读取仓库内材料，不调用 Python、其他 checkout 或网络。

`app/build.gradle.kts` 唯一维护产品版本，并独立声明 Android 支持的合同集合。当前目标为
`0.0.20 / 20 → 平台合同 2 → Core 语义基准 0.2.0-preview.23`，Android 仅承诺 `[2]`；
保留 Snapshot v4/v5 adapter 不等于承诺平台合同 1。四项只读 BuildConfig 字段为
`PLATFORM_CONTRACT_VERSION`、`SUPPORTED_PLATFORM_CONTRACT_VERSIONS`、`CORE_BASELINE_VERSION`、
`PLATFORM_CONTRACT_BASELINE_HASH`。关于页显示版本、主合同和基准 Core；它们不随连接的服务器变化，
也不参与运行准入或持久化配置迁移。最终 APK 核验与已有消费测试的使用方式见[测试策略](testing-strategy.md#最终-apk-与-core-版本对应)。

### 按主体解析与使用偏好

`ConfigurationQueryService` 组合 `UserSettingsDocument`、原 Session 和 Applied；`ConfigurationResolver` 按完整 Deployment/User 产生 `ResolvedConfiguration`，只做纯派生，不落盘镜像。用户定义保持一份；企业助手定义、system prompt 与固定 MCP 只读，本域允许的模型和使用偏好通过 typed 命令写回用户文档。助手模型的默认、空间默认、指定引用必须保持可区分，失效的显式引用不能静默替换。所有列表、查询和命令携带原 `RealmSelection` 或 `RealmAccess`；等待 Settings、数据库、文件或网络后须重新验证原主体，切域返回也不恢复旧授权。

资源选择和修改在 Settings 写锁内依据最新文档、原域与准入规则完成；UI 只消费有效目录和不可用原因，不从显示名称推断持久化引用。
MCP 选择差量以本次写入前的持久偏好为基准，仅对真正新增的引用检查准入；显示投影过滤掉的历史企业引用不能阻断其他选择的编辑。
保留历史引用不恢复执行资格，当前有效目录和原 Session 的调用准入仍须逐次检查。企业 `img_*` 是独立图片定义，仅由 resolver 投影到统一图片目录；模型、图片、云端语音和 Direct MCP 执行都须冻结原 Session、generation 与具体 route，并在 Provider I/O 前执行前述受管版本准入。准入成功不代表远端请求成功。

`ModelExecutionService` 在用户配置事务中捕获同一文档的目录与选择，主聊天、子助手和辅助生成沿原域复验。内建搜索选择必须匹配 Provider wire 能力；外挂搜索以同次 `ResolvedConfiguration` 的 SEARCH 选择查用户目录，企业域未选或失效不回退首项。企业固定 Memory Seed 由 resolver 派生，START 捕获后经请求接纳进入 disclosure，不进入可变记忆表。

配置图片由 `ConfigurationApplicationService.importAssistantImage` 或 `AssistantBackgroundService` 创建，目标固定原 `RealmSelection`/`RealmAccess`；个人写共享助手定义，企业写本主体的 `AssistantUsagePreferences`。提交复用 `AssistantPreferenceChange`、`ArtifactSettingsCoordinator` 与 `ArtifactStore.commitSettingsRoots`，锁序为 Session → Settings → Artifact。失败只回收本次未发布副本；旧图片由完整引用图决定回收。

`RealmConfiguration` 读取只接受个人已提交配置根或本主体已提交 usage 根；不能借其他企业根或聊天资产授权。个人根移除后，已提交的企业引用仍可保留本主体读取资格。生成背景以原域和图库 ID 读取后复验目标；企业定义/Prompt 预览不提供重新捕获全局目标的写入口。本域编辑差量、模型三态与重置语义统一见 [助手配置](assistant-configuration.md)。

### 预算、Portal 与数据边界

生产用量、预算和阻断事实归 Core。`PlatformControlClient.budgets` 经原 Session 查询，Android 不持久化预算；`EnterpriseVM` 的投影绑定原 selection 与 origin，刷新中保留最近成功值，只有请求实际失败才标记 stale 并保留原诊断。MODEL、IMAGE_GENERATION、TTS、ASR、MCP 均按平台 Problem 精确分类；个人请求不能被普通远端 429 冒充企业额度失败。

`EnterpriseBudgetAlerts` 只按 `EnterpriseBudgetAvailability.EXHAUSTED` 展示耗尽告警，不从历史限额占比重新推断。
`projectEnterpriseBudget` 优先使用有效无限模式，其次区分缺少限制、核对中和有限额度占用；未处于核对中的有限零额度仍为耗尽。
Core 在恢复无限额度后保留的规则和用量继续留在投影中，但 `EnterpriseBudgetCapabilityRow` 的无限状态只展示累计用量，
不展示历史规则的进度、剩余量或恢复时间。加载和查询失败沿用原刷新、stale 与诊断语义，不改判为耗尽或无限。

`runtimeCompleted` 仅由实际使用平台路由的模型、MCP、云端 TTS 和 ASR 调用触发，并保留原企业 access。
企业空间中的个人 MCP、个人模型/语音和设备 System TTS 不触发企业额度刷新；该条件不改变新企业操作的配置及权限检查，
也不取消企业页面打开或用户手动发起的预算查询。刷新信号和查询投影均归 Android，Core 用量统计与协议没有变化。

平台 Problem 仅在 routed 请求、稳定 code、HTTP status 和 `forwarded=false` 匹配时进入企业失败链；保留 blocker、resetAt、requestId、resourceId 和原 detail。失败不回退个人资源或自动重放业务请求。陈旧预算只供显示，执行仍由 Core 准入。

Portal 文档和消息由 `PortalDocument`、`PortalDocumentRegistry` 与原 `RealmSelection`/Session 共同授权；Bridge v3 严格解码，重复键、未知操作或不匹配的文档身份失败关闭。Android 始终打开 Core `/portal/`；标准或企业自定义页面由 Core 选择。旧文档关闭、站点数据清理和地址切换在同一发布屏障内等待确认，迟到回复不能进入新页面。原生媒体和外链动作均由当前文档 owner 再次授权，Portal 不成为配置或会话 writer。

Portal 原生媒体的暂存、额度、取消交接与删除恢复归 `PortalMediaStore`，文档关闭等待网页和硬件清理全部完成；失败保留原 owner 供重试。站点清理只针对本 Session 的 origin 和 Portal Cookie，不全局清除浏览数据；不具备完整站点清理能力的 WebView 明确拒绝打开。真正的 Core grant、系统浏览器、相机/录音和关闭路径需单独设备验收。

Conversation、Memory、Artifact、GeneratedMedia、收藏、分页与统计中的 durable 行均带完整 scope。个人备份只构建个人数据及其共享资源闭合图；恢复时保留最新企业图，文件由各自 owner 交接。系统备份和设备迁移不直接复制混合域存储。具体备份格式、旧数据迁移与字段目录见下文对应载体；文件与会话的写协议仍归各自专题。

企业远程文件能力独立于 Applied 配置及 MCP 发布。CONFIGURATION_PENDING 中的有效 Session 可查询工作区并管理文件，
不得为此放宽模型执行准入或向 Settings 写入另一份工作区状态。客户端请求随原 RealmSelection、Session 和连接变化撤销；
退出与地址变更通过 `RemoteWorkspaceService` 排空请求及未交付资源，详见 [Workspace](workspace-architecture.md#10-企业远程文件)。

## 4. 资源定义与专题边界

### 4.1 显示偏好

`DisplaySetting` 保存消息外观、自动滚动、触觉、更新提示等显示偏好。页面消费 query/UiModel，
外观选择由 `AppearancePolicy` 统一解释；颜色模式和 AMOLED 的 SharedPreferences 归属见下文。
显示正则、字体或气泡设置不修改 durable 消息或 Provider 请求；请求侧正则另见 [助手配置](assistant-configuration.md)。

### 4.2 Provider 与 Model

用户 `ProviderSetting` / `Model` 保存连接参数、模型能力和高级覆盖，字段以原类型声明为准。
Provider `customHeaders` 缺字段解码为空列表；复制、类型转换与 Model providerOverwrite 保留该字段。
Provider 编辑复用原 typed contract，目录、余额及生成请求共用各自 Adapter 合并入口，详情默认折叠显示请求头编辑器。
`providerOverwrite`、custom headers/body、URL 和 API key 只属于用户定义，可能含凭据；
企业公开 Model 与私有 execution binding 独立，不以这些覆盖字段作为受管执行来源。
有效模型选择由 Resolver 派生，执行 owner 复验原域准入；wire 差异见 [Provider 协议](protocol-reference.md)。

### 4.3 Assistant、Prompt、工具与引用

`Assistant` 的字段、默认值、运行语义及本域使用编辑只在 [助手配置参考](assistant-configuration.md)维护。此处只记录它引用的其他配置事实：`QuickMessage`、`AssistantRegex`、`ModeInjection`、MCP server、Skill、Workspace 与本地工具开关仍属于用户文档或各自 owner，不因 Assistant 引用而转移写入所有权。

`presetMessages` 是完整 `UIMessage` 图，可能包含 metadata、usage 和多模态/工具 parts，不是可直接下发的轻量示例文本。企业 Starter 使用独立受限定义，不把本地历史或 URI 作为受管配置。

### 4.4 企业 Starter 与会话开场

Starter 是企业发布的任务入口；`prompt` 是供用户编辑的起始输入，Opening 是会话绑定的开场定义副本。
二者都不是运行记忆。Snapshot v4 的 Starter 只预填提示词；v5 要求 `openingSnapshot`，包含完整
`systemPrompt` 和有序 `initialContexts(id,content)`。v5 开场只维护一份明确结构；背景标题由界面按序生成，不作为配置字段。v5 Starter 无 `description`，领域模型、配置详情和入口选择器均不保存或展示说明。v4 由其生成 wire 投影验证后转换到当前运行模型，废弃说明不进入 DTO；v4/v5 响应均忽略未知字段，不能用旧字段替代现行必填。已有磁盘配置与会话开场在校验原存储完整性后，仅在本地读取边界丢弃废弃的说明，不改写历史正文、来源或原始文件；新写入不再包含它。
开场格式、已消费字段和重复背景 ID 仍校验；
Android 保留发布值的空串、空白、顺序和字面内容，不把已发布的空 System 重新解释成“继承助手”。
发布端如何编制默认值归 Core 合同；Android 消费 Core 导出的样例，不在客户端重新编译发布定义。

| 阶段 | 当前行为与负责入口 |
| --- | --- |
| 浏览与导航 | 目录包含标题、完整起始 prompt 与 opening 可用性；`StarterOpeningReference` 仅携带定义摘要与原发布身份，opening 的 System/背景正文经授权 query 按需读取 |
| Draft 选择 | `ConversationApplicationService` 经 `BindDraftOpening` 在内存绑定；selection token 比较并交换（CAS）防止迟到选择覆盖新选择，输入拒收只补偿本次 token |
| 显式刷新 | 复验最新完整定义并更新绑定，不再次追加起始提示词；选择 v4 入口会清除已有 Draft opening |
| 首次发送 | `requireCurrentStarterOpening` 比较助手、当前准入和完整 Starter 定义；单独 generation 改变不拒绝，定义变更/撤销则保留草稿与附件并报告原因 |
| 持久化 | 首次 `AppendUserMessage` 在同一事务保存会话根、opening 和用户实际输入；START 是之后的独立命令，其失败不撤销已提交输入 |
| 已有会话 | 再选 Starter 只追加提示词，不替换根 opening；删除首条消息不删除根 opening，Fork 复制该事实而不复制执行任务 |

`ConversationOpening` 保存原助手、releaseId、generation、snapshotHash 和完整 Starter 定义，
不保存凭据、Session 或执行租约。原始 prompt 与用户编辑后发送的 USER 是不同事实；未发送 Draft 不落库。
三个界面入口（空间预览、聊天快捷菜单、空白聊天卡片）使用同一 application 协议，不自动发送。

Opening 的 System 只在当前会话助手等于原助手时参与选择，优先级低于合法非空会话覆盖、高于助手定义；
切走后不应用，切回且获准时可再次应用。模板每个 START 按当次变量渲染，同一 Turn 固定。
背景仍是会话输入，按请求窗口投影，不因换助手变成新用户指令。其模型格式和位置见
[提示词与工具](prompts-and-tools.md)，接纳与历史来源见 [请求上下文](request-context.md)。

### 4.5 搜索配置

`SearchServiceOptions` 保存用户搜索服务定义及凭据，不作为 Model/MCP 路由或企业凭据载体。
默认 `BingLocalOptions()` 使用随机 UUID，不具备跨安装稳定性，不能作为企业资源引用。
参数与默认值以声明为准；搜索装配与模型内建搜索的区别见 [助手配置](assistant-configuration.md)。

### 4.6 语音配置

用户语音定义保存在 Settings，企业 TTS/ASR 定义保存在 Applied 配置，平台执行绑定单独管理。
`selectedTTSProviderId` 与 ASR 选择由本域解析；公共播放倍速属于播放偏好，不是服务端 voice。
类型、协议条件、交互冻结、取消与硬件清理统一见 [语音架构](speech-architecture.md)。

### 4.7 MCP

用户 `McpServerConfig` 保存 SSE/Streamable HTTP 连接定义、静态 headers、OAuth 状态和工具 enable/approval policy；远端 schema 只归独立 `McpCatalogStore`。企业执行描述不能转换为用户定义，企业 token 不进入 headers/OAuth 持久字段。配置与导入校验、目录迁移、OAuth 信任边界及执行协议统一见 [MCP 架构](mcp-architecture.md)。

### 4.8 备份配置

WebDAV 备份通过 `ScopedUserPreferences.webDavBackup` 保存资源 ID、根内目录及归档内容选择，
由 `ConfigurationApplicationService.changeWebDavBackup` 按原空间和 expected 值提交；不复制连接凭据，
不跟随文件管理器或 Agent 的当前资源。个人 `Settings.webDavBackup` 只投影个人域偏好，恢复保留企业主体偏好。
`WebDavSettingsMigration` 在 typed decode 前转换已有文档的旧 `webDavConfig`，独立 `webdav_config` 键
由原 `UserSettingsMigration` 同次接纳。可按旧类型解码的配置保全地址及凭据，以稳定 ID 加入个人连接；目录保留原根内位置，
`items` 中任一 DATABASE/FILES 仍映射为完整持久聚合，个人备份引用该连接，但不自动选为文件管理器当前项。
已存在的新连接、个人当前选择及备份偏好优先，企业 scope 不变；仅空默认旧配置直接移除。
旧界面逐字段即时保存的未完成地址、用户名和目录仍保留为可编辑配置，合法值按原位置规范化，校验失败保留原值；
不赋予当前资源资格，新 Save、当前项选择和执行仍严格校验。导入归档沿 `migrateLegacySettingsJson` 使用相同转换；
损坏 JSON、错误字段类型及未知枚举明确失败并保留输入，成功后移除旧字段，重跑幂等。
S3 和提醒仍由用户 Settings 管理。个人备份包含用户自己的服务凭据，不能纳入企业
Snapshot、执行绑定或 Session。归档格式、混合域隔离、冷恢复和系统备份排除规则统一见
[备份与恢复](data-persistence.md#个人备份与恢复)。

## 5. Settings 之外的 Android 配置

### 5.1 SharedPreferences

主题、语言、启动行为、侧栏和搜索排序属于本机显示偏好，由各自的偏好入口读写，不进入企业发布配置。
CrashHandler 的独立 `crash_handler` SharedPreferences 保存 `crashed` 和截断后的 `stacktrace`，属于崩溃恢复状态。
字段名与默认值以相应声明为准，不能将崩溃标记当成用户配置迁移。

### 5.2 Room 中的配置性事实

Workspace 注册和审批、会话助手及合法覆盖、文件夹和模型上下文归各自数据 owner；
它们不是远端 Settings，也不因配置引用而改变归属。会话覆盖须由对应 Assistant 的 allow 字段开放。
企业会话与文件夹按完整主体隔离，本地 Workspace 是显式共享资源；
`WorkspaceConfig` 的内置限额没有企业下发入口。

结构、迁移及上下文事实见 [数据持久化](data-persistence.md)，Workspace 生命周期与操作见
[Workspace](workspace-architecture.md)。

### 5.3 Skills 与文件资源

- Skill 由 `filesDir/skills/<name>/SKILL.md` 及同目录资源构成；
- frontmatter 至少提供 `name/description`，可带 `compatibility`；
- `Assistant.enabledSkills` 只保存 Skill 名称引用；
- 自定义字体、Assistant 头像/背景、上传和生成图片分别由自己的文件 owner 管理。

`SkillManager` 是 Skill 目录身份、文件树、读取、校验和发布的唯一 owner，UI 与工具只持有 typed metadata/file/result，不取得宿主路径。`SkillFrontmatterParser` 使用 SafeConstructor 与 loader limits，拒绝重复键并限制 alias、嵌套和文档大小。 Markdown、ZIP 和 GitHub 导入遇到 YAML 或字段校验错误时保留 INVALID_SKILL、具体原因与解析异常链；ZIP 诊断包含条目路径。非 YAML 的非预期异常保留原异常并报告 UNKNOWN，取消继续传播，解析失败不得发布 Skill。 优化 Release 保留 `org.yaml.snakeyaml.TypeDescription` 类名：SafeConstructor 初始化会经该类读取自身包名，移入默认包会在 Android 上崩溃；不因此保留整套 JavaBean 反射能力。
`listInvalidSkillDirectories` 单独返回无法加载的目录名、原始诊断及 owner 签发的删除目标；合法旧目录与 frontmatter 名称不同仍按现有身份规则读取。
`deleteInvalidSkillDirectory` 在原备份锁内复验目标未被修复或替换，按实际目录操作，复用整库 staging/rename/backup 恢复协议保留其他目录。
页面确认删除前展示共享用户 Skill 库的影响，过期目标要求刷新；越界、符号链接或 IO 失败保留诊断，不把失败当作已清理。

Skill 文本在 owner 边界先做 4 MiB bounded byte read，再 strict UTF-8 解码；超限、非法编码和 IO 分别返回 typed failure。写主文档/支持文件及删除支持文件时，先复制完整已发布目录到 staging，拒绝 symlink/path escape，校验 frontmatter 与预期 name 后 rename 发布。更新 SKILL.md 保留支持文件；中断 backup 由下一次 owner 访问恢复或清理，歧义时拒绝继续。

ZIP bundle 完整解析并拒绝重复 Skill name 后，复制整个 Skill root、一次 root swap 提交，失败或取消不留下部分更新；root backup 同样可恢复。导入上限为 16 MiB 输入、512 entries、单文件 4 MiB、累计解压 32 MiB。GitHub 导入保留支持文件原字节，仅主文档 strict UTF-8 解码，因此二进制资源不被文本化。SKILL.md 不能作为普通支持文件删除，整项删除经 deleteSkill 与 enabledSkills 引用清理协议。

SkillManager 的 `saveSkill`、`saveSkillFile`、`saveSkillFileBytesAtomically` 与 `deleteSkillFile` 使用单一 suspend 写入口，在 staging 校验完成、发布前检查取消。预期拒绝返回稳定结果码，非预期 IO 抛出原异常，补偿失败保留 suppressed；不再通过 nullable metadata、Boolean 或无 cause 的 IO_FAILURE 丢失原因。GitHub HTTP 429 或带明确限流信号的 403 归为 RATE_LIMITED，普通 403 保留 HTTP 拒绝；status、有限长度响应 detail 与网络 cause 进入同一可复制诊断。搜索只按 name/description 过滤当前 metadata 展示，不更改 enabledSkills 或文件身份。

## 6. 稳定引用与读取物化

用户定义的 Provider、Model、Assistant、MCP 等保持原 ID；按域选择保存类型化引用。
已创建会话的助手归属不随新会话选择改变，资源引用不赋予当前域访问资格，显式失效选择保留原因。

Auto Model sentinel、默认助手、System TTS、内置 Provider、Learning Mode 和预设主题的已发布 ID 参与兼容；
重命名显示文本不能重新生成身份。读取物化补齐内置资源、按 ID 去重并处理部分失效引用，
不把结果静默写回磁盘。跨记录删除、授权清理和默认选择修正仍由对应命令在同次 Settings 提交完成。

## 7. 维护与验证

以下变化必须同步本文：Settings 字段与默认读取语义、Local/Managed owner、有效读模型、持久化与备份边界、稳定引用
规则，以及已经实际接入 Android 的平台 typed definition。

修改当前 Android 配置链至少验证：

- Settings serialization、缺失 key 默认、读取物化和旧备份兼容；
- 用户文档、按域解析、write lock 与“落盘后发布”顺序；
- Local 备份不包含 Enterprise Binding、credential 或 Applied Snapshot；
- 无效、过期、撤销或损坏企业配置的拒绝、历史可读与执行门禁行为；
- 删除与恢复在 Provider、Assistant、MCP、TTS、ASR、Search 和文件引用之间保持原子；
- UI 与运行时只消费同一个 effective read model，不建立第二 owner。

构建/JVM 验证不替代真实平台接入、Android 安全存储和恢复场景的设备验收。新增生产同步路径时，应补充对应的服务端互操作验证。
