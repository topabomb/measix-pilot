# 第 17 批模拟器界面审查

本记录配合 [第 17 批执行记录](batch-17-2026-10-10.md)。截图来自实际安装的 Debug 应用，操作使用专用模拟器的触摸、系统返回、真实键盘和正式应用入口；不是设计稿或 Compose 测试截图。

## 环境与证据边界

- API 37.1：`Measix_Batch17_20261010` / `emulator-5580`，x86_64、16 KiB 页，1080×2400；另将窗口临时设为 720×1280 / 320 dpi，检查 360×640 dp 小屏，结束后恢复。
- API 30：`Measix_Batch17_API30_20261010` / `emulator-5582`，x86_64、4 KiB 页。两者均为本批专用测试设备，使用独立 ADB server 5041。
- 聊天数据通过本机 HTTP 夹具、真实 Provider 请求和正式工具提交链生成。夹具只返回确定的模型列表、图表调用、推理和正文；错误场景返回 503 和长诊断。它不证明真实外站模型推理质量或企业 Core 转发能力。
- 截图保存在工作区 `captures/upstream-batch17/`，该目录沿用仓库忽略规则，不随文档提交。下面链接为本次交付的本地证据。
- 报告引用的 36 张截图完整性见 [SHA256 清单](../../../captures/upstream-batch17/screenshot-manifest.json)。审查结束恢复模拟器原分辨率，停止本批 HTTP 夹具并移除 18717/18718 转发，保留专用设备与测试数据。

## 页面与交互

| 范围 | 实际操作及判断 | 截图 |
| --- | --- | --- |
| P04 Provider headers | 展开、编辑 `X-UI-Review: batch17` 并保存；请求日志中核实实际携带。高级字段默认折叠，保留原编辑器和保存动作，不给普通配置增加常驻表单。 | [折叠](../../../captures/upstream-batch17/01-provider-headers-collapsed.png)、[展开](../../../captures/upstream-batch17/02-provider-headers-expanded.png) |
| P11 模型选择 | 折叠 OpenAI，搜索 `gpt` 临时展开，清空后恢复；关闭、重开保持偏好。API 30 另经真实页面重复该流程，避免以该平台 MockK 限制替代验收。 | [分组](../../../captures/upstream-batch17/03-model-groups-collapsed.png)、[搜索键盘](../../../captures/upstream-batch17/04-model-search-ime.png)、[API 30 搜索](../../../captures/upstream-batch17/37-api30-model-search-ime.png)、[恢复折叠](../../../captures/upstream-batch17/38-api30-collapse-restored.png) |
| P18 Claude 5.5 | 分别选择 Opus / Sonnet，实际打开 effort 控件；Opus 有 XHigh 并解释不支持 Off，Sonnet 的 Off 明确说明工具间仍可能思考。沿用原紧凑滑杆，没有新增平行模型编辑界面。 | [Opus](../../../captures/upstream-batch17/05-claude-opus-effort.png)、[Sonnet](../../../captures/upstream-batch17/06-claude-sonnet-off.png) |
| P08 图表 | 从本地能力入口开启 Charts，发送消息，正式执行 line/bar/scatter 三次工具调用；图表与表格切换、第二页及横向查看数值均实际操作。图形为有限高度，表格每页六行，避免一次展开二十行推走正文。 | [工具入口](../../../captures/upstream-batch17/07-chart-tool-option.png)、[散点](../../../captures/upstream-batch17/09-chart-scatter.png)、[第二页](../../../captures/upstream-batch17/10-chart-table-page2.png)、[横向查看](../../../captures/upstream-batch17/11-chart-table-horizontal.png) |
| P07 导出 | 分享消息，关闭 Include reasoning；Markdown 实际产物保留用户正文、三次工具输入/输出及最终正文，排除推理。PNG 实际导出并读回，三种图表与历史模型名称完整。关闭推理时 Expand Reasoning 禁用，动作语义一致。 | [导出选项](../../../captures/upstream-batch17/16-export-without-reasoning.png)、[实际 PNG](../../../captures/upstream-batch17/17-chart-image-export.png) |
| P09 摘要 | 确认页将保留数设为 0，生成摘要；原消息、图表仍在。编辑摘要出现第二版本；删除摘要后原历史仍可浏览。修复后再生成，摘要动作栏只保留复制与更多，不再显示不适用的重新生成。 | [确认](../../../captures/upstream-batch17/18-summary-confirm.png)、[最终摘要](../../../captures/upstream-batch17/19-summary-preserves-history.png)、[删除后历史](../../../captures/upstream-batch17/21-summary-deleted-history-retained.png) |
| P06/P12 fork 与历史 | 从消息创建 fork，列表显示 `Batch17 UI review(1)`；原会话仍在。UI Preferences 中切换 Recently updated / Newest created，原历史页和抽屉消费同一排序偏好。孤立个人历史的恢复和分页数据保全由 Room/备份门禁另证，不冒充本次手动造出了该场景。 | [历史](../../../captures/upstream-batch17/22-history-fork.png)、[排序选项](../../../captures/upstream-batch17/23-history-sort-options.png) |
| P10 历史模型 | 在 Provider 模型编辑器把当前模型改名为 `Renamed current model`；当前会话配置随之更新，但旧回复的详情仍显示生成时的 `gpt-4.1`。历史信息没有被新配置重写。 | [当前配置](../../../captures/upstream-batch17/31-current-model-renamed.png)、[旧回复详情](../../../captures/upstream-batch17/32-historical-model-retained.png) |
| P14 搜索弹层 | 开启 Local Search，进入 Bing 提供方子页，系统返回先回设置，再返回关闭；通过真实坐标点击可重新打开，之后关闭搜索。子页沿用同一弹层，关闭按钮/遮罩关闭整个弹层。 | [子页](../../../captures/upstream-batch17/14-search-provider-chooser.png)、[第一次返回](../../../captures/upstream-batch17/15-search-first-back.png) |
| P14 IME / 小屏 | 在实际 Gboard 中输入文本，输入框和发送按钮保持在键盘上方；模型搜索仍在弹层内，实际滚动后完整露出 gpt-4.1-mini，并点击成功切换模型、关闭弹层。窄屏收起次要按钮，避免按钮与输入区挤压。 | [普通键盘](../../../captures/upstream-batch17/08-chat-input-ime.png)、[小屏键盘](../../../captures/upstream-batch17/29-compact-ime.png)、[小屏搜索](../../../captures/upstream-batch17/30-compact-model-search.png)、[键盘上方滚动后](../../../captures/upstream-batch17/40-compact-search-scroll.png) |
| P14 长诊断 | 真实请求接收长 503 错误；聊天保留短预览，技术信息弹层可以滚动并复制完整原因，关闭后可继续导航。日志详情保留请求 body 的树形浏览。 | [收起](../../../captures/upstream-batch17/33-long-error-collapsed.png)、[展开](../../../captures/upstream-batch17/34-long-error-expanded.png) |
| P15 图片快捷方式 | 冷启动及已运行应用分别送入系统静态 shortcut 的完整 Intent 语义；进入 Image Generation，未自动发出生成请求。静态快捷方式由系统附加 `NEW_TASK / CLEAR_TASK / TASK_ON_HOME`，零 flags 的随意 Intent 不用于宣称桌面入口验收。系统 ShortcutManager 的包绑定另由两台设备测试核实。 | [冷启动](../../../captures/upstream-batch17/27-image-shortcut-cold.png)、[热启动](../../../captures/upstream-batch17/28-image-shortcut-warm.png) |
| P20 火山 TTS | 新增并保存用户 TTS，浏览 key/base URL/resource/voice/-50～100 rate；选中后测试空 key，明确显示可复制的 `volcengine_tts_api_key_required`，没有假成功。表单沿原滚动区，保存按钮固定可达。未配置真实凭据，未宣称外站合成/播放通过。 | [表单](../../../captures/upstream-batch17/24-volcengine-form.png)、[语音与速度](../../../captures/upstream-batch17/25-volcengine-rate.png)、[校验](../../../captures/upstream-batch17/26-volcengine-validation.png) |

## 实测发现与修复

1. 搜索子页在 `onDismissRequest` 中只切换页面，会在 sheet 已经隐藏后留下透明拦截窗口。修复在弹层内容内用 `BackHandler` 处理子页返回，dismiss 只关闭弹层。手机、宽屏真实系统返回回归均通过，并断言关闭后没有 Dialog。
2. 默认图表刻度把 1.5～37.5 显示为 2～38。线性刻度按十进制端点与步长确定精度，对数刻度按相邻间距确定精度；显式格式优先，极窄范围使用有界科学计数，中间值钳制到域内并去重。补边界行为测试。表格二十行改为六行后，分页动作更容易到达。分页采用原图标体系的 48dp 方向按钮与独立居中页码，保留五语无障碍描述，避免文字按钮在窄卡片挤压；240dp / 1.5 倍字体下实际逐页到 `167 / 167`，验证区域不重叠、末页禁用和退回上一页，设备测试通过。正式页面第二页及横向滚动截图已替换为最终控件。
3. 摘要节点不应出现重新生成；更重要的是服务入口原先可能先截断摘要后的历史。现在原域/worker/tree 锁内，任何写入前按摘要来源拒绝，界面按授权投影隐藏动作。真实应用服务测试证明后续消息、上下文条目和 admissions 未变、生成器未运行、worker 正常退出；独立审查已确认。

4. JSON 字符串子弹层继承外层文本菜单 provider，工具条错误绑定到后面的窗口。统一在 `AdaptiveModalContent` 新窗口边界重置 toolbar/dropdown provider，由内层选择容器创建自己的菜单。正式日志页面实际全选、复制，再粘贴到聊天输入框，内容精确为 `gpt-4.1`；没有发送该粘贴内容。[选择工具条](../../../captures/upstream-batch17/35-json-selection.png)、[实际粘贴](../../../captures/upstream-batch17/39-json-copy-pasted.png)。

嵌套选择回归使用真实系统触摸复制并断言剪贴板，分别覆盖手机 bottom sheet 和宽屏 Dialog、关闭内层后的外层复制。API 37.1 两项通过，API 30 连同弹层反馈和搜索返回六项通过。API 37 的 remote toolbar 通过 SurfaceView 嵌入，测试需逐层读取无障碍 child，不能使用不会进入嵌入树的 findByText；测试也按真实打开顺序先建立外层，再点击打开内层，而不是同时初始化两个 sheet。
