# 企业 WebDAV 大视频与文件预览调研、优化及验证

日期：2026-10-12。范围：Android 三类文件管理入口及本机企业 Core 实测。
Android 从 `7f5fa3a6a` 拉取到 `09865c72439814d480574a62bc3ca4505595c980` 后实施；
优化、测试和本报告按后续用户授权整理为本地提交，未推送。
按用户要求，其他端问题只在本任务记录；没有修改、部署或重启 Core / Agent Space，也没有向其他任务发送消息。

## 结论与责任

原有企业 WebDAV 媒体读取存在明确的 Android 性能问题：解码器命中已有数据窗口时，仍重复调用远端工作区状态接口；
拖动到非零位置时，必须等待整个 8 MiB Range 下载完成才能给播放器第一批数据。两者叠加网络延迟，造成可避免的等待。
优化应先落在 Android 的原服务和播放器数据源，本次已完成。

真实环境在旧及中间部署另复现了独立的服务端目录故障：企业 DAV 根目录 `PROPFIND` 在发出 `207` 后被 Core 的
`invalid DAV path` 中断；Files 根目录返回 `400 invalid_file_path`。同一测试目录中的文件 HEAD、Range 和完整下载均可成功。
这类故障需要 Core 与 Agent Space 核对目录 URI / 路径契约，不能由 Android 忽略不完整目录、放宽授权或重试半份正文来解决。
11:51:38 后的新 Core 二进制上，1.09 GB 样本从两个正常根目录入口完成播放和 seek，目录复测已恢复。
此前失败记录及其他端建议继续保留；没有捕获具体被拒绝的上游 href，也未核验服务端修复 diff，
不能断言某个文件名、Agent Space 实现或 Core 某次提交就是唯一根因。

## 三类链路与其他预览

| 场景 | 当前读取机制 | 本次判断及处理 |
| --- | --- | --- |
| 本地工作区 | 本机文件访问，共用媒体 UI / DataSource | 没有远端状态往返；媒体窗口优化共用，保留本地集成测试 |
| 远程工作区 Files | Core `/workspace/files` 与 `/workspace/content`；本地已发布事实校验缓存访问，网络请求校验原句柄 | 原缓存检查不额外查远端；共用小首窗口优化；真实内容链路已核对 |
| 企业 WebDAV | 工作区绑定投影 + Relay DAV；HEAD 与带版本条件的 Range | 修复解码缓存命中的重复状态查询、seek 首窗口等待及 Range 内存复制 |
| 音频 | 与视频相同的 `MediaPreviewSource` / DataSource | 同类读取问题会受益；本轮真实 Core 样本为视频，没有单独作真实音频长播放结论 |
| 图片 / SVG | 授权 ImageSource、有界完整读取、图片解码；信息面板读取字节取得尺寸等属性 | 修复同一预览反复打开信息面板重复读取；只保存元信息，复用前后仍检查权限；首屏读取仍有网络成本 |
| 文本 / Markdown | 有界完整读取后进入原编辑会话；正文上限 2 MiB，Markdown 相对图片另有限额 | 没有解码器小块读引起的重复校验机制；每次打开及相对图片读取仍受网络影响，不改为无限制流式编辑 |
| PDF | 下载到本次会话负责的临时文件后渲染，预览默认上限 24 MiB | 首次展示依赖完整下载，与 Range 播放问题不同；保留大小限制、进度、取消及临时文件清理 |

目录入口故障发生在选择文件前，会影响所有预览类型。只凭“大视频卡”不能推导所有图片、文本或 PDF 都有相同传输问题。
如后续需要支持更大 PDF，应另测真实等待时间并设计原临时文件 owner 的下载行为；本轮没有引入持久媒体缓存或后台同步。

## Android 已实施方案及授权边界

1. `RemoteWorkspaceService.readPublishedDavProjection` 从唯一 owner 已发布的原选择 / Session / 平台连接投影读取。
   `WebDavApplicationService.validateLocal` 仅供浏览器缓存访问和有效性检查；打开企业浏览器先刷新。
   真正网络请求、写入、工具、备份和对外发布仍执行原远端校验。
2. DAV 状态查询成功通过同一 owner 发布当前投影；以查询序号和原 generation 阻止迟到响应覆盖更新事实。
   绑定 / 空间变化撤销原 Files 句柄；迟到 DAV 查询不能恢复已失效的空间。
3. 每次 DataSource open，包括非零 seek，从 256 KiB 开始；连续窗口按 512 KiB、1 / 2 / 4 / 8 MiB 增长，
   单窗口最大 8 MiB。下载下一窗口前释放已消费数组；close / seek 不接纳旧任务的迟到结果。
4. DAV Range 直接填充精确数组，消除 ByteArrayOutputStream 的第二份完整复制；严格检查 `206`、强 ETag、
   `Content-Range`、identity 编码、声明长度及实际正文。少字节或多字节都失败；不发布半份窗口。
5. 图片信息只在当前预览、当前图片及重试版本内复用。关闭整个预览不保留该缓存，显式重试刷新远端状态，撤权不显示缓存信息。

本地校验不是离线授权：它能立即拒绝应用已经观察到的切域、退出、连接或绑定变化，不能宣称立即知道尚未同步的远端撤权。
真实网络读取仍由服务端鉴权；没有扩大原句柄访问范围，没有重绑定到新选择，也没有自动重放写入或中断正文。
职责详见 [工作区架构](../references/workspace-architecture.md)、[UI 架构](../references/ui-architecture.md)
与 [测试策略](../references/testing-strategy.md)。

## 性能对照及真实内容证据

受控 DAV：专用模拟器、80 ms 响应延迟、1 MiB/s 限速、60 秒 640×360 H.264 视频，15,195,367 字节。
固定 8 MiB 读取与新 seek 首窗口通过同一生产传输代码对照，不是两份完整 APK 的端到端对比。

| 测量 | 结果 |
| --- | --- |
| 32 次 1 KiB 已有窗口读取的额外远端状态查询 | 修复前 32 次，修复后 0 次；绑定变化仍拒绝读出 |
| 非零位置固定 8 MiB / 新首窗口完整返回 | 8,070 ms / 348 ms |
| 准备播放 | 1,769 ms |
| 连续播放 | 30,012 ms，0 次重新缓冲 |
| 暂停后拖动到 2 秒并解码新画面 | 1,043 ms，时间戳 2,000,000 μs |
| 关闭 | 数据源关闭、PlayerView 解绑均确认 |

真实企业链路使用本机 `19100`，模拟器通过 `http://10.0.2.2:19100` 接入原部署。
15 MB 样本在两个正式文件页面完成目录进入、播放至少 15 秒、暂停拖动到 57 秒及关闭：

| 链路 | 准备 | 15 秒解码 / 重新缓冲 | 暂停 seek |
| --- | --- | --- | --- |
| 企业 DAV | 2,656 ms | 362 帧 / 0 次 | 227 ms，57 秒新画面 |
| Files | 1,561 ms | 362 帧 / 0 次 | 310 ms，57 秒新画面 |

两条链路的 HEAD、非零 64 KiB Range 和完整 SHA-256 均核对一致，目录和本地输入均清理。
15 MB 完整入口结果对应下述旧 Core 二进制，不能自动归于后来替换的二进制。

1.09 GB 样本为本地生成的 72 分钟视频，1,094,007,482 字节，SHA-256：
`679174442cd70f4779b602e2fd802f0bf80bae666e5fac110e6700029174dca5`。
在旧、新 Core 上均完成上传、双链路 HEAD、非零 Range 及逐字节完整摘要；未把整个视频加载进内存。
旧 Core 的正式 DAV 根目录入口失败，新 Core 的正式 Files 根目录入口失败，均发生在播放器创建前。
追加验证分别保留根目录与播放结果，已失败的旧部署记录不会因后来实测成功而改成通过。

一次追加实测在 11:51:38 的外部 Core 进程替换中被打断：PUT 连接重置，原句柄随后因
`webdav_binding_changed` 拒绝清理。这次没有到达内容核对或播放器，不能计为媒体失败或通过。
后续核对原部署 / Session / 空间后，用新授权句柄独立清理该次已确认创建的 UUID 目录，再重新上传；没有恢复旧操作。

最后一轮在 Core exe `551C77FA…` 上完成，JUnit `OK (1 test)`，采集结果 `outcome=passed`、
`rootDirectoryFailures={}`；两个页面均记录 `navigation=root_directory`，没有使用根目录失败后的独立目录诊断路径。

| 1.09 GB / 72 分钟视频 | 准备 | 连续解码 / 重新缓冲 | 暂停 seek 到 71:57 |
| --- | --- | --- | --- |
| 企业 DAV | 2,531 ms | 15,041 ms、362 帧 / 0 次 | 887 ms，新画面时间戳 4,317,000,000 μs |
| Files | 2,798 ms | 15,031 ms、362 帧 / 0 次 | 579 ms，同一新画面时间戳 |

本次上传 170,795 ms；双链路 HEAD、非零 Range、完整摘要均一致。所有播放器 / 页面 / 句柄释放、
远端测试目录及本地视频 / 输入清理均为 `completed`，前次中断目录也已独立清理。
实际查看两个暂停截图，控制条为 `1:11:57 / 1:12:00`，画面为本地生成的编码测试视频。
这是各播放 15 秒加末尾 seek 的真实验证，不是全程播放 72 分钟，也不是高码率或 4K 性能保证。

## 其他端问题报告：旧及中间部署的根目录查询失败

### 企业 DAV：成功响应头后的正文断流

2026-10-12 10:38:09，本机 Core 同一时段记录：

```text
httputil: ReverseProxy read error during body copy: invalid DAV path
route=/runtime/v1/resources/* method=PROPFIND httpStatus=207 durationMs=53
```

Android 堆栈是 `Http1ExchangeCodec.ChunkedSource.readChunkSize` → `WebDavClient.parseMultiStatus` →
`ValidatingDavXmlParser.nextText` → `EOFException`，失败点在目录 XML 正文，和图片 / 视频解码无关。
与 [此前的响应头 EOF](webdav-eof-investigation-2026-10-11.md)不同；已有读连接恢复仍保留，中断正文不重放。

核对本轮 Core 源码路径 `backend/internal/relay/runtime/webdav_xml.go` 与 `webdav.go`：
`rewriteDAVXML` 用 io.Pipe 流式改写 `mapDAVXML`；`davMapper.fromUpstream` 经 `davRelative` 校验资源 href。
遇到不符合路径规则的资源，mapper 返回错误并关闭 pipe；ReverseProxy 此时可能已发送 `207`，无法换成完整 Problem。
当前访问日志的 `207` 只证明响应头状态，不证明正文完整。

### Files：根目录返回稳定业务拒绝

新 Core 上同一大文件已完成两条内容链路核对，随后正式 Files 页面根目录返回：

```text
PlatformHttpException: HTTP 400: invalid_file_path: Remote workspace operation could not be completed
```

该错误由服务端返回，不是 Android 对返回文件列表的本地解析断言。
当前 Core 的 `workspace.Service.OpenFiles`、`agentspace.Client.List`、href 映射及 `RelativePath / FilePath`
位于目录访问链；具体拒绝位置和被拒绝路径尚未捕获。不能把它直接登记为已证明与 DAV mapper 完全同因。
测试 UUID 子目录列举和文件内容操作已成功，根目录失败足以阻断正常文件浏览及所有预览入口。

### 建议由 Core / Agent Space 核对的原因及回归保障

1. 在原会话授权下捕获根目录 PROPFIND 的合法脱敏输入、上游 href 和对应 mapper 拒绝原因，
   核对 root / 尾斜杠、逐段编码、保留目录和实际普通文件名契约；目前只确认拒绝机制，未确认具体坏 href。
2. 对 Files `invalid_file_path` 补充同请求的路径阶段诊断，区分用户请求不合法与上游目录条目不合法。
   不应为正常目录列举要求客户端放宽路径、忽略整个目录或跳过原空间授权。
3. 保持 origin、目录边界、穿越及受保护目录约束；定义合法资源编码和受保护条目的处理规则。
   对必需的非法上游响应，应明确完整失败语义及正文中断诊断，避免仅有 `207` 成功日志。
4. 当前新二进制的正常根目录和双链路大视频已复测通过；服务端仍应核对本次修复原因并补目录失败测试，
   并验证特殊文件名、畸形 href、中断 XML、取消及已执行写入不重放。健康检查、MCP 成功或普通 GET 不能替代此验收。

本任务不实施这些其他端修改；未删除或改名测试账号既有文件，也未改企业发布、绑定或工作卷来规避错误。

## 构建、回归及未通过边界

| 验证 | 结果 |
| --- | --- |
| 定向 JVM 行为回归 | 93 项通过，0 跳过 |
| 全量 `test assembleDebug lintDebug assembleRelease` | 构建完成；JVM 3,258 项，0 失败，13 跳过 |
| 全量 `connectedDebugAndroidTest` | 487 项，457 通过、1 失败、29 跳过；完整门禁未全绿 |
| 受影响媒体 / 图片 / Files / DAV 设备套件 | 43 项通过，1 项显式受控网络 opt-in 跳过；该 opt-in 后续单独执行通过 |
| 本机企业 1.09 GB 场景，最后一轮 | 正常双根目录进入、双链路内容摘要、15 秒播放、末尾暂停 seek 和资源清理通过 |
| 全量设备唯一失败 | 未修改的 `SearchPickerBackAndroidTest.expandedBackReturnsToSettingsThenRemovesModal`；展开搜索后返回断言超时 / 仍显示，未查明根因，未声称修复 |
| 参考文档检查 | 本次变更范围 0 错误；仓库全局另有 40 处既有缺失捕获 / 许可链接，未修复 |

所有 Gradle 验证串行附带 `--no-parallel --max-workers=1`。
初次 JVM 门禁发现旧下载中断 fixture 没有保证正文已消费再关连接，改为明确握手并保留“不重放”断言，重跑通过。
初次设备发现 Debug 增量产物 `Source.$stable` 字段与消费者不一致；隔离本任务生成缓存并非增量重建后，
字段及实际文件页面运行恢复。未通过产品 keep rule 或持久构建开关掩盖该构建问题。

设备均为本任务专用 API 36 模拟器；没有物理设备、生产 Core 或高码率 / 4K 持续播放结论。
本轮真实大文件为 1.09 GB；既有超过 2 GiB 偏移 / 4 GiB 稀疏媒体设备测试不能代表本轮完成了 4 GiB 网络传输。
受控延迟和本机 LAN 指标只适用于记录条件，不是所有服务及网络的延迟保证。

## 验证身份与资源清理

生产 Debug APK SHA-256：`1079F2800906C1FD5FA259FE5CA7CA9351288B76985AC1F2BB33A1E57E7E6052`。
模拟器 `Codex_Media_20261012` 完成全量及受控场景；`Codex_MediaLive_20261012` 当时保留数据完成企业场景。
测试 APK 追加采集程序时未更换上述生产 APK，一次性接入材料不写入报告。

本机部署 `dep_800cc17b-784b-4ca7-8c37-02e6f42bf5e5` 在本轮期间被其他任务更新：

- 15 MB 正常入口和首次大文件 DAV 根目录断流：Core exe SHA-256
  `F6CA94A3519ADA6E07596079221B9B06FABC8ECAFB31953C4C2733515BD9E349`。
- 10:44:57 后大文件 Files 根目录失败，以及被后续更新打断的追加实测开始时：Core exe SHA-256
  `D2D9D50ABFD38B74DA1B7211CA6F78C88C6B5A45EFD190CB1658BCBD33C091EA`。
- 11:51:38 的进程替换及最后追加实测：Core exe SHA-256
  `551C77FAFDA87FDB7C787FB89F73F847362F1C82E7480DF06279A33413098B32`。

未核验这些 exe 与 Core 当前脏工作树的逐文件对应；源码机制说明不替代部署源码身份。
Agent Space 容器镜像为 `agent-space:schema3-1418d5c5474fafd1`，本任务未重启。

用户随后明确要求删除相关模拟器和历史资源，不制作备份。本次已删除两个专用 AVD 的目录及 `.ini` 注册文件，
以及本仓库的 `build/`、各模块构建输出、项目 `.gradle` / `.kotlin` 和 Workspace `.cxx` 生成缓存。
旧预览日志、截图、测试视频、APK、设备及 JVM 原始报告和临时采集工具均随这些生成目录删除；
本文保留已核对的指标、部署身份、关键故障堆栈和验证边界，原始产物已不可读取。
删除后的 AVD 列表和目标不存在性已复核；D 盘可用空间从约 3.59 GiB 增至 20.43 GiB，盘点前后增加约 16.83 GiB。
源码、永久测试及本报告保留；没有清理 Core 或 Agent Space 运行数据。

重新实测需建立新的专用模拟器，按 [测试策略](../references/testing-strategy.md) 准备接入与一次性 cache 输入；
同一轮重试仍须保留设备数据并核对原身份。
用例只在 MKCOL 已确认成功后取得 UUID 测试目录清理责任，finally 等待播放器 / 页面 / 原句柄释放，
确认远端目录不存在后删除本次本地视频和输入；清理失败会写入证据并使结果失败。
