# WebDAV 响应头 EOF 调查与主分支合并验证

## 结论与责任边界

此前将 EOF 只记为外部集成观察不够准确。本次已复现并确认一个客户端缺陷：
`WebDavClient` 为避免写入重放，对全部请求设置 `retryOnConnectionFailure(false)`，
连 GET 的正常失效连接恢复也一并关闭。服务端按空闲超时关闭 HTTP/1.1 连接后，
客户端复用旧连接，读取响应头遇到 EOF，直接把错误交给文件管理器。

修复责任在 Android 的读写传输策略。服务端关闭空闲连接是正常行为，不能据此认定 Core 或 DAV 服务异常。
本次没有修改 Core，没有增加文件同步状态机，也没有通过延长服务端超时或要求用户刷新来掩盖问题。

历史局域网 Core 的原始事件仅有客户端堆栈，没有当时的服务端连接日志或抓包，
不能追溯证明那一次连接由谁关闭、是否恰好触发空闲超时。下述对照用于证明可复现的客户端机制，
不把相同异常文字当作全部历史事件同因的证据。

## 原始现象与对照实验

环境：Windows 11、Android API 37 模拟器 `emulator-5590`、OkHttp 5.4.0、
WsgiDAV 4.3.5 / Cheroot 11.1.2。主分支合并工作树保留了远端 MCP 变更和 WebDAV 实现。

- 历史 Core 事件：`WebDavClient.download` → `Call.execute` → `Http1ExchangeCodec.readResponseHeaders`，
  `IOException: unexpected end of stream` 的 cause 为 `EOFException: \n not found: limit=0`。
  失败发生在响应头读取阶段，不是图片解码、SAF 写入或 `/upload` 映射阶段。
- 本轮原生界面：上传 1,048,832 字节任意二进制成功；停留后下载发生相同 EOF。
  WsgiDAV 访问日志没有该次 GET，失败的 SAF 目标已清理。不能把这个失败记为下载通过。
- 原生 Android 对照：同一客户端连续两次 GET 成功，等待 12 秒后第三次 GET；
  旧策略仍取得同一个连接对象和本地端口，随后 EOF。只开启读恢复的对照在旧连接失效后取得新连接并读到完整 24 字节。
- 主机直接探测 WsgiDAV：完整读取一次响应后保持 socket，约 **10.453 秒**后收到正常 EOF；
  与安装的 Cheroot `HTTPServer.timeout = 10` 一致，响应未声明 `Connection: close`。
- 主机直接探测原局域网 Core `192.168.31.235:19100`：无认证 `GET /` 得到完整 404、19 字节，
  随后连接在 **120.000 秒**正常关闭。当前 Core 源码 `server.NewWithGrace` 配置 `IdleTimeout: 120s`。
  这个探测没有消耗接入码，也没有访问用户文件。
- 模拟器直接连接该 Core（不经 ADB reverse），两个客户端同时完成首次 `GET /` 后等待 **125 秒**：
  旧 `retry=false` 策略复用原连接并重现响应头 EOF；修复后的 `WebDavClient` 换连接，正确收到第二次 HTTP 404。
  404 是所选无认证根路径的正常业务响应，此实验验证 HTTP 连接恢复，不宣称 DAV 认证或文件下载成功。
  `dav-core-idle.json` 同时记录两侧连接对象、本地端口和完整异常。
- 安装修复后的主 APK 后，再跑 WsgiDAV 的两组 12 秒空闲对照，均恢复连接并读到完整 24 字节；
  `dav-idle-after.json` 的 retry 字段是注入基础客户端的设置，实际 GET 均受生产读白名单策略控制。
- `10.0.2.2:19191` 对照在建连阶段超时，未进入 HTTP，故不计为成功或 EOF 样本。
  不据此认定 ADB 有故障；不依赖 ADB 的 JVM loopback socket 回归同样能复现旧策略失败。

临时诊断 instrumentation 是结果采集程序，其 `OK` 只表示采集执行结束；是否成功按各请求结果判断。
原始材料保存在 `build/reports/master-merge-20261011/`：`dav-idle-before.json`、
`server-idle-close.json`、`core-idle-close.json`、`download-idle-eof.xml`，以及前次
`build/reports/webdav-adjust-20261011/ui-download-eof.log`。这些本机原始产物不作为仓库运行依赖。

## 机制与最小修复

核对实际依赖版本的上游源码：

- [RealRoutePlanner](https://raw.githubusercontent.com/square/okhttp/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealRoutePlanner.kt)：GET 不启用 extensive health check。
- [RealConnection](https://raw.githubusercontent.com/square/okhttp/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealConnection.kt)：池连接的深入探测同时受空闲时长和该标志控制，TCP 已被对端关闭不一定立即反映为本地 Socket 的 closed 状态。
- [RetryAndFollowUpInterceptor](https://raw.githubusercontent.com/square/okhttp/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)：关闭 retry 会拒绝连接恢复；响应正文交给调用方后不在这段恢复循环中。

`WebDavClient` 仅对 GET、HEAD、PROPFIND 使用共享连接池的读客户端，开启标准连接恢复。
恢复保留原请求的 URL、凭据、条件头和 Call；仍受原 Session、资源身份和取消约束，
不会重新选择“当前 WebDAV”。正文断流不重试、不续传、不再次追加输出；原发布前复验和失败清理保留。
重定向、认证器重发和缓存仍关闭。

审查同时发现写侧遗漏：OkHttp 的 `503 + Retry-After: 0` follow-up 不受 retry=false 限制。
PUT 原本已是 one-shot；现将 MKCOL、COPY、MOVE、DELETE 的零字节 body 也标为 one-shot，
保留写连接恢复关闭，避免服务端已执行后被自动重发。原未知写入核实机制不变。

`WebDavConnectionRecoveryTest` 使用真实 loopback socket/HTTP 服务覆盖：

1. 已关闭的池连接恢复后，只输出一份完整正文。
2. 响应头成功但正文中途 EOF 时失败，不重放、不重复追加。
3. PUT、MKCOL、COPY、MOVE、DELETE 遇到 503/Retry-After:0 各只发送一次。
4. 替换连接等待响应期间取消，Call 和对端 socket 均退出。

修复前四项中三项失败，正文不重放原本已通过；修复后四项全部通过。
临时诊断代码留在本机证据目录，未加入产品代码或长期测试集。

## 主分支合并检查

合并输入：远端 `master` 的 `7f5fa3a6a` 与功能分支终点 `05a74a578`。
远端两个 MCP 提交属于有效需求：v5 企业助手可选择额外受管 MCP；受管工具短命名空间及来源显示保留。
功能分支已有的 Android 平台契约关联、[第 17 批上游同步](upstream-sync/batch-17-2026-10-10.md)和 DAV
随原提交历史合入；不能把合并涉及的全部文件记为本次 EOF 新改动。本次额外变更为冲突消解、联合回归、上述传输修复及报告。

- `ResolvedConfiguration` 同时保留企业 snapshot schemaVersion 与个人 DAV 配置/当前项/备份选择。
- v4/null 与 v5 的 MCP 准入差异保留；DAV 当前目标独立于可选本地 Workspace。
- 新增联合行为测试通过正式配置命令和 Resolver，在 USER/SUB_ASSISTANT 两种装配中验证：
  无 Workspace 时可选企业 MCP 与当前 DAV 同时存在；没有 DAV 目标时仅去掉 DAV 工具。
- 合并后的 OpenAPI SHA-256 为 `55fb1ea7404ed71135948a94733b3160366adb991be3abda0c0af481f10e2cc8`，
  与 Core 当前导出一致；协议校验与生成代码 `--check` 通过。
- 两位独立审查者核对重叠文件、版本透传、准入、工具装配、DAV 授权和旧 JS 清理。
  修复了自动合并后一个测试遗漏 `webDavTransfers` 构造参数的问题，没有删除远端 MCP 能力。

## 最终验证记录

本轮单元测试共 3,303 项，0 failure / 0 error，13 项按既有条件跳过；跳过不计为已验证。
EOF 修复经独立只读复审，无剩余可证实的授权、取消、正文重放或写入重发问题。

可见模拟器保留数据安装、字体缩放 1.3，完成以下实际操作：

- 原生 MCP 选择器 8 项测试通过，并检查企业必选/可选/个人来源截图。这个验证使用受控目录投影，
  不冒充旧 Core 实际下发了 v5 可选 MCP。
- 设置中 MCP 后的 WebDAV 入口、添加配置、连接测试、当前项和独立文件管理器均可用。
- 任意二进制上传、在系统保存界面停留跨过空闲超时后的下载均成功。
  源、服务端和下载副本均为 1,048,832 字节，SHA-256 均为
  `dd7e5c49d123e860c8bb7016bada722b5d0baa37ef8b19d5e270cf2a3000c31d`。
- 文本预览、编辑、条件保存成功，服务端实读为 `Master_merge_DAV_verified`。
- 个人连接可用时，切到未下发 DAV 的旧 Core 企业域，DAV 菜单父节点 `enabled=false`，不回退到个人资源。
  删除本次个人测试连接后，个人抽屉入口也禁用。
- 清理本次配置、设备测试文件、临时诊断输出和 ADB reverse，并关闭本次专用 DAV 进程；企业接入与原 Workspace 保留。

截图和 XML：`07-download-after-idle-success.png`、`08-text-edit-save-success.png`、
`09-enterprise-no-personal-fallback.png`、`personal-no-dav-disabled.xml`，均位于前述本机证据目录。

最终完整门禁于 2026-10-11 完成，**BUILD SUCCESSFUL，27 分 6 秒**：

```powershell
$env:ANDROID_SERIAL='emulator-5558'
.\gradlew.bat test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest --continue --no-parallel --max-workers=1
```

最终 XML 汇总：单元测试 3,303 项（13 跳过）、设备测试 484 项（27 跳过），均为 0 failure / 0 error。
Debug、arm64-v8a / x86_64 Release 与 lint 均完成；lint 为 0 错误、340 警告、7 提示。
原始门禁日志为 `build/master-merge-final-gates.log`；证据目录中的 `verification.json` 及
`final-unit/`、`final-device/` 保存统计与最终 XML 副本。可见设备测试 APK 也已替换为不含临时诊断类的版本。
协议检查、生成代码一致性、UTF-8 无 BOM / CRLF 和最终 diff 检查通过；版本号不变。
合并在 `master` 完成，两个父提交及本报告可用于追溯，不新建分支。

验证边界：用户已明确改为仅用模拟器，没有实机结论；当前旧版企业接入没有 DAV，
本轮没有重新完成带认证的企业 DAV 文件往返或真实 Provider 自主调用。
Core 根路径实验只验证传输层恢复。4 GiB 与大视频 opt-in 本轮未重跑；不能以普通文件通过代替这些场景。
