# 消息渲染管线

本文描述消息显示投影如何成为 Compose 内容或 WebView 文档，以及交互、导出和销毁的边界。
页面导航与窗口承载见 [界面架构](ui-architecture.md)，历史输入语义见 [请求上下文](request-context.md)，
文件登记、授权读取与生命周期见 [多模态与资源持久化](multimodal-context-and-turn-durability.md)。

## 1. 数据流与消息分组

```text
ConversationPresentation 的有序消息、阶段和预览能力
  → ChatMessage / groupMessageParts：按消息部分分组
  → 原生正文、工具/子助手卡片、Markdown 或媒体
  → 脚本图表或文档预览：由对应宿主承载 WebView
```

渲染只消费查询投影，不修改持久消息、选择分支或工具状态。流式投影保留其他 variants 与 selectIndex；
`visualTransform()` 只改变显示，不能代替输入转换或终态提交。工具以 `localCallId` 作为 UI 身份，
运行与交互使用 `ToolLivePhase`，不能从 Provider call ID、列表位置或 output 是否为空推断。
`Tool.clientDiagnostic` 是结果检查点保存的脱敏客户端诊断。普通工具详情、归档结果详情和子助手卡片提供
同一展开/复制入口；`ask_user` 专用交互卡片也披露保存的诊断。入口不依赖 output 或可回放结果是否存在，
诊断不进入 Provider 请求。

Assistant 消息的 `MessageModelInfo` 在 START 提交时从捕获模型保存，包含配置引用、wire model ID 和显示名称。
主聊天、子助手、消息详情及导出读取该历史值，不查询当前模型目录重写名称。旧消息缺字段显示未知模型；
正文编辑和 fork 保留原值，重新生成的新 variant 捕获本次模型。显示信息不是执行或设置访问的授权凭据。

`groupMessageParts` 将连续推理和普通工具合成 `ThinkingBlock`；`assistant_call` 单独形成
`SubAssistantCallBlock`，正文和媒体使用 `ContentBlock`。普通 `UIMessagePart.Step` 不显示也不切断折叠时间线；
有已接纳上下文变化的 Step 才结束前组并插入 `ContextUpdateBlock`，分组不改变消息顺序。

`ServerTool` 使用普通内容块承载只读服务端事件，按 ai 的 `ServerToolPresentation` 展示搜索查询、来源链接与错误；
不进入本地 Tool 卡片的审批、执行或详情授权链。流式正文、推理、图片和服务端片段经 Provider 显示槽关联，
图片完成帧替换同槽占位，正文跨工具后保留独立片段；具体协议身份与回放规则见[协议参考](protocol-reference.md)。

折叠时，待审批/待回答工具以及 `generate_image` 保留在原时间线位置。工具即使尚未执行或没有结果也属于可见内容，
空白文本仍为空。子助手卡片只显示目标、任务、阶段、有界回答预览和待回答问题；详情借用父页面授权，
执行语义见 [子助手架构](sub-assistant-architecture.md)。

工作区产出提示仅从 `workspace_write_file` / `workspace_edit_file` 的成功且结构有效结果提取，
接受当前可读 `size` 和历史数值 `sizeBytes`，不从可读大小反推字节数；生产输出与 UI 提取由同一行为测试覆盖。
不能从参数或“存在结果”推断写入成功；统一 diff 来自显示 metadata，不混入 Provider 工具文本。
工具的本地化名称只用于显示，协议名仍是执行和持久化身份。

`chart_display` 已退休，仅保留历史结果的 `ChartSpec` 纯校验；聊天和图片导出通过 `completedChart` 核对明确 COMPLETED 结果与阶段，
畸形历史或非成功调用保留通用详情。原生 Canvas 支持折线、线性零基线柱状和散点，图表与分页表格共享参数。
表格每页六行，列可横向滚动，不增加嵌套纵向滚动；默认线性刻度保留端点和步长的小数精度，极窄范围使用有界科学计数。
图表至多 8 个系列、1000 个数据点、规范化 JSON 64 KiB 字符，标签至多 160 字符；非零数值绝对值在 1e-100 至 1e100，
显式轴范围包含所有数据点。对数值和对数域严格校验，刻度去重后至多六个，对数刻度按相邻间距保留可辨精度，不加载脚本或网络。
不再提供工具开关或执行装配。历史调用参数、结果和成功阶段保持原样，不把未知或失败的历史调用升级为成功图表。

## 2. 应用上下文变化展示

上下文条目不额外插入消息节点。`ConversationPresentationSnapshot.context` 提供轻量摘要和来源，
`MessageContextSummary.updates` 只包含实际新增 EXTERNAL 变化的 Step、requestId 与类别，正文按授权查询读取。
纯 streaming 不重扫全部历史或读取正文。

主聊天和子助手在实际接纳通知的 Step 边界显示标签：首请求位于输出开头，中途位于前批工具结果之后、
受影响输出之前。同一请求合并类别，不同请求分别定位；沿用历史不新增标签。
失败/取消且无正文的消息仍显示已有通知和原终态，没有变化的空消息不增加入口。
标签按稳定类别次序显示完整无障碍名称，不列资源 ID 或数量。Starter 从开场详情查看，预置/摘要沿原来源显示，
均不转换成“上下文变化”；消息“更多”菜单和子助手请求区不另设聚合入口。

`ConversationContextDetails` 固定打开时的 requestId；`forUpdate` 只展示该请求新增的 EXTERNAL 条目和分区。
长正文、完整状态包及技术来源按需展开，使用保存正文和类型化来源，不用当前 Settings 解释历史。
旧记录缺少逐项差异时标明当时完整状态，不推测修改前内容。

`ConversationQueryService.contextDetails` / `contextContent` 提供目录与正文，读取前后复验原页面 lease、域、
所选 Assistant variant 和请求关联。弹层内复用相同 entry；关闭、切域或切换回复后取消读取并拒绝迟到结果，失败保留诊断。
编辑因果 USER 不隐藏仍保存的所选回复通知，兄弟回复不混合。无 admission 的原文可按权限读取，但不能伪造通知或 HTTP 快照。
请求接纳早于 Provider IO，详情不能把“已接纳”显示为模型已收到。

## 3. Markdown、代码与原生媒体

Text 先经过助手视觉正则，再进入 `MarkdownBlock`；视觉转换与生成管道的持久化转换不同。
`ChatMessage` 通过 `MessageRichTextHost` 按原会话与消息身份取得查询层签发的资源来源，子助手详情沿用此入口。
工作区图片使用请求保存的工作区与 cwd；缺来源不查询当前助手。首次内容出现后刷新来源，普通流式文本追加
不重复签发；失败提供诊断和重试。图片导出在点击时为每条选中消息分别绑定来源，保存/分享前复验，
不能用一个会话级 resolver 解释所有历史图片。`/upload` 始终走 ArtifactStore，工作区来源不扩大附件权限。
流式生成时禁用正文选择，结束后恢复。两条解析路径通过 `prepareMarkdownSource` 共用换行与数学分隔符预处理；
只在解析副本中将 CRLF/CR 变为 LF，并用位置映射保留代码复制与导出的原文。AST 识别的围栏、行内和缩进代码
保持字面内容，未闭合与四反引号围栏同样受保护；数学分隔符在单次替换中处理，避免前次替换导致代码位置偏移。
首次同步解析，
后续通过 `snapshotFlow`、`mapLatest` 和后台 dispatcher 更新 AST。

两套 Markdown 的搜索引用角标使用实色 tertiaryContainer / onTertiaryContainer 与中等字重，
避免深色主题下透明底色和细字降低可读性。思考正文以 bodySmall 为基准，字号和行高同时应用聊天 fontSizeRatio，
保留聊天字体；折叠预览与展开正文共用该样式。

| 内容 | 渲染路径 | 边界 |
| --- | --- | --- |
| 普通 Markdown | IntelliJ Markdown AST → Compose | 按节点渲染正文、列表、引用、表格、代码与公式 |
| 含 HTML 且宿主允许 HTML | `MarkdownNew` → HtmlGenerator → Jsoup DOM → Compose | 支持选定标签和内联样式，不提供浏览器完整 CSS 布局 |
| 普通代码 | `HighlightCodeBlock` → `highlight` 模块 | 复制保持源码，行号、折叠、换行和字体连字不改原文 |
| LaTeX | JLatexMath 原生 Canvas | 行内拆分支持换行，失败保留单体公式；块公式可横向滚动 |
| Image part | `ZoomableAsyncImage` / Coil | 使用授权 ImageSource，加载占位不可点击 |
| Document / Audio / Video | 原生附件入口 | 经文件应用服务导出独立副本，再交给外部应用 |

企业动态摘要的 `MarkdownSummary` 只投影文字和样式，展开后才使用完整 Markdown；摘要本身不提供链接、媒体动作或文件权限。

### 代码与全文预览

宿主允许 HTML 预览且代码围栏闭合时，`HighlightCodeBlock` 才提供 HTML/SVG/Mermaid 预览；
流式未闭合内容显示源码高亮。完整 HTML/SVG/Mermaid 默认预览，三类均可切换源码；Mermaid 使用离线图表渲染。
HTML 直接成为文档内容，SVG 包装为展示文档，两者经 `RenderedContentWebView` 读取。

`MarkdownFence` 从 AST 提供闭合状态、块起点和原始代码。HTML 路径通过本次生成的私有标记关联解析后的
Element，随后删除标记；模型原始 HTML 属性不能声明围栏已闭合。`CodePreviewPreferences` 保存文本块的
显示选择，聊天按 messageId 与 part index 持有，在流式追加、解析器切换和 SelectionContainer 重建后保留。
已完成正文再次编辑或替换时清除旧选择；该状态不持久化、不提供资源访问资格。

`EscapedMathProvider` 在 HTML 生成边界转义公式，Jsoup 解码一次；直接 AST 渲染不重复 HTML 转义。
JLatexMath 的测量、分段及绘制都经 `processLatex` 去除不支持的换行间距参数（如双反斜线后的 [4pt]），
不改存储正文或复制内容，普通方括号表达式保留。

内联 Mermaid 使用专用 `Mermaid` 宿主；`buildMermaidHtml` 转义代码并加载本地 `mermaid.min.js`，
以 `themeVariables` 映射当前主题。代码、主题或图片来源解析器变化时重建文档与导出桥。
全屏 Mermaid 使用同一 HTML 生成器，经通用预览宿主显示，不安装内联 PNG 导出桥。

Markdown 全文预览只提取消息顶层 Text，经 `buildMarkdownPreviewHtml` 和 `assets/html/mark.html` 构建后进入原宿主全屏预览。
模板从 CDN 加载 Markdown、公式、高亮与图表依赖，不具有内联 Mermaid 的离线保证；图表主题使用媒体查询。
依赖版本和模板参数以资源文件为准，不在文档重复维护清单。

## 4. 图片、链接与导出来源

`RichTextHost` 提供链接、预览和唯一待处理文档选择器。聊天/子助手使用 `ConversationViewLease`，
受域约束的配置预览使用 `RealmConfiguration`，共享用户配置使用 `UserConfiguration`，开发示例和更新说明使用 `Static`。
渲染组件不访问 Store，也不按当前空间重建来源。无交互宿主时，Markdown 链接使用不可用 handler，代码/表格不安装文档选择器。
`RichTextActions.exportText` 缺失时不显示文本保存动作；文件预览使用原来源的 RichTextHost，文本导出沿同一授权入口，原文件下载仍由文件页负责。

`LocalConversationImages` 在点击时按当前分支顺序收集顶层与 Tool.output 图片，过滤空加载占位，形成会话相册；
未命中时单图打开。Markdown/HTML 正文图片经 `LocalImageSourceResolver` 解析并单图浏览，背景与输入附件不并入相册。
图片预览、信息与保存复用同一 `ImageSource`；本地路径不代替文件身份和页面授权，解析失败不回退直接读文件。
共享配置的本地图片须仍被个人配置引用；有来源的网络与内联图片也保留原来源校验。

非图片附件经 `MediaExportService.openAttachment` 交付临时副本，不向外部应用授予原件路径。
代码/表格下载在点击时冻结正文、文件名、MIME 和来源；原选择器返回前不被新点击替换，迟到回调不能导出新页面内容。
`MediaExportService` 接收宿主已取消或请求缺失时的回调，负责交付复验与失败清理；取消继续传播。
文件复制、发布与补偿协议见 [资源持久化](multimodal-context-and-turn-durability.md)。

聊天截图的离屏树保留来源和预览投影，不安装交互选择器。导出协程直接持有 `BitmapComposer`，
捕获完成或取消均拆除组合树，未交付 Bitmap 回收，不由独立 Handler 或第二协程作用域延续导出。

单附件的预期缺失或 `ArtifactProjectionException` 关闭其访问能力，不中止整段聊天。
非预期数据库/payload IO 故障保留日志，并通过 `AttachmentPreview.diagnostic` 显示脱敏诊断；失败投影不提供 URI、ImageSource 或 fileTarget。
`AttachmentPreviewDiagnostic` 在原位置提供紧凑摘要和可展开/复制详情；主聊天或详情宿主用 `LocalAttachmentPreviewRetry`
重试原查询，只读导出不安装重试，也不从原 URL 绕过失败投影读取图片。

## 5. WebView 文档与生命周期

通用 HTML/SVG 和全屏预览使用 `RenderedContentWebView` / `rememberRenderedContentState`；
内联 Mermaid 使用专用宿主，企业 Portal 的宿主与 Bridge 由 [配置架构](android-configuration-architecture.md)维护。
三者不能视为同一套来源校验或浏览器状态策略。

通用预览每次读取尝试分配随机 `.invalid` origin，并关闭 DOM Storage，使文档不沿用另一来源的 origin。
这不等于禁用网络或建立独立 WebView profile：HTTP(S) 子资源仍可加载，也不会清理 Portal 的全局浏览器状态。
来源未授权或已撤销时不创建/继续展示预览；`RenderedContentReadState` 区分 Loading、Unavailable、Failed 与 Ready。
异常初始化、刷新或资源读取显示诊断，重试沿用原 `RenderedContent` 和来源，不能重建失效权限。
旧回调只更新原读取尝试，不覆盖新文档；预览关闭或失败不主动关闭借用的父 lease，撤权按原查询门禁处理。
每次读取尝试持有独立 Job，WebView 同步资源回调通过该 Job 运行底层挂起读取。关闭、重试、失败或撤权
立即取消旧 Job 并拒绝迟到请求；原组合协程在清理阶段挂起等待读取退出，不在 Main 阻塞等待。
原生回调边界将取消收口为不可访问响应，不把取消显示成读取失败，也不关闭共享文件会话或父 lease。

聊天/配置来源将 `file:` 和 `/upload` 图片映射到当前虚拟 origin，经文件服务读取，动态节点和 CSS 使用同一路径。
外部 `<base>` 不改变这些映射的目标；本地读取只交付通过图片识别的字节，不提供通用文件接口。
原生 file/content 访问关闭，用户手势链接交给原宿主；无手势导航被拦截。
HTML/SVG 不获得 Portal Bridge，也不注入业务 JavaScript 接口。

工作区文件来源为 `RenderedContentSource.WorkspaceFile`，借用 `RenderedFileSource` 的原文档身份和目录；
虚拟 origin 下的相对 URL 经 `readRenderedResource` 交回原服务，按 MIME 返回 CSS/JS/图片等有界字节。
它不访问聊天 Artifact；页面中的 base 标签不能更换借用目录。`FileDocumentPreview` 同时观察文件来源变化，
因此没有新网络请求的已加载 Markdown、HTML/SVG 或 Mermaid 也会在撤权时撤下；细节见 [工作区架构](workspace-architecture.md)。

`WebView.kt` 负责原生实例的创建与释放：普通重组比较数据避免重复加载；`onReset` 停止加载并移除接口；
`onRelease` 仅清空匹配实例的状态引用，停止加载、移除接口和客户端、清空内容并销毁实例。
来源、代码或主题替换时需连同文档状态和回调归属一起替换。

`ContentPreviewPage` 只持有进程内 `RenderedContent` 和原来源，导航不保存 HTML 或授权对象。
恢复后无文档时显示不可用，需从原页面重新打开；刷新复验来源，撤权销毁 WebView，页面不提供通用浏览器能力。

### 内联 Mermaid 与 PNG 导出

内联 Mermaid 直接使用 `rememberWebViewState` / `WebView`，以 `https://measix.local` 加载本地 assets，
只安装专用 `AndroidInterface`。它没有通用预览的随机 origin、DOM Storage 关闭配置和 `RenderedContentReadState` 来源观察；
消息是否继续显示由上层投影/宿主控制，导出沿原图片来源解析器校验。

用户点击产生请求 ID，经 `exportSvgToPng(requestId)` 回到原生桥，只接收当前待处理请求的一次结果。
脚本未就绪时提示失败，可再次点击；旧请求不能完成新请求，新文档不重放旧导出计数。
结果在组件协程中转为 PNG data URL，经原宿主 `ImageSource` 和 `MediaExportService.saveImage` 读取与保存，
不在桥接线程直接解码或写相册。未提供图片解析器的独立使用场景采用 `externalImageSource`，不能据此读取本地文件。
组件销毁取消导出协程；导出失败通过提示反馈，不具备通用预览错误卡的完整诊断展示。

### 发送前图片预览与标注

`MediaFileInputRow` 点击图片打开多图 `ImagePreviewDialog`，标注动作携带冻结列表中的图片序号，
再映射到 `ChatInputState.ImageEditTarget`；重复 URI 或重复 Image 实例不合并为同一个输入附件。
输入被发送、删除重选或切换编辑目标后撤下旧预览/标注，历史消息中的附件位置按独立身份保留。
`ChatImageAnnotation` 经 ChatVM 读取原授权图片并准备预览，`ImageAnnotationDialog` 只持有临时笔画，
支持自由线、椭圆、矩形、箭头、颜色粗细、撤销重做及缩放平移。取消不接纳结果，失败显示诊断并保留重试。
GIF 可预览和发送，但不进入静态标注；空笔画完成保留原附件。绘制、编码及新图片所有权规则见
[多模态资源](multimodal-context-and-turn-durability.md)，不从界面直接写入文件或改写历史附件。

## 6. 验证边界

分组与上下文投影验证 Step、来源、variant 和迟到结果；Android 场景验证 Markdown 路径、代码完整性、
选择与相册、文档替换和销毁、预览重试、离屏树清理及 Mermaid 导出请求归属。
系统交付、撤权与补偿由对应文件服务和设备场景证明，入口与证据限制见 [测试策略](testing-strategy.md)。
