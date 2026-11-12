# Everse demo 实际验收（2026-11-12）

## 结论与范围

已跑通现有网页上传 → 自有 Revit 2026 自动打开/导出/无保存关闭 → 标准 Draco → Java 归档 → 原编辑器加载及属性 → 两模型保存 → dev 整页重载与正式构建预览加载。
这是本机受控 demo 验收，不是源模型内容完整、全部视觉一致、测量精度或公网生产安全认证。无 commit/push，无测试数据清理。

前置原版验证：Everse v4.5.242，commit `fb1832d0784288f13927395b1b8a20f278570a17`；未经核心修改的原版 HVAC/Architectural 已先行运行。
原版详细证据：`C:\project\3D\Astral3D\.codex-handoffs\revit-everse-20261112\upstream-verification\ORIGINAL_VERIFICATION.md`。

本次服务证据目录：`C:\project\3D\Astral3D\.codex-handoffs\revit-everse-20261112\service-verification`。下表文件名均相对此目录。

## 真实网页任务

| 任务 | 页面 / 配置 | 结果 |
| --- | --- | --- |
| 112 | dev 8090，HVAC，标准三维视图 + 着色 + 属性 | 成功；69,804,112 → 12,111,684 bytes，约 13.55 秒；已加入保存场景 |
| 113 | dev，Architectural，精确名称 + 贴图 | 真实失败 `DisplayStyle cannot be modified.`；无成功 GLB；文件、名称、选项仍保留 |
| 114 | dev，同一建筑样本与选项重试 | 成功；296,108,560 → 35,689,164 bytes，约 32.92 秒；已加入保存场景 |
| 115 | 正式构建 preview 8092，HVAC，标准三维视图 + 着色 + 属性 | 新 DLL 回归成功；12,111,684 bytes，约 10.39 秒；未重复加入保存场景 |

转换时间为服务执行时间，不含网页上传、解析和场景保存。文件大小用 bytes，非原 RVT 到 GLB 的压缩比例。

任务 113 发现的是宿主多余的 `view.DisplayStyle = ...`：上游通过 `Preferences.materials` 区分材质/贴图，本就不修改指定视图显示样式。只删除此 setter，保留材质枚举映射、精确视图选择及真实错误；没有解除视图模板、换视图兜底或改导出核心。先红测后修复，任务 114/115 验证两分支。

任务 112/114 标准 decoder 独立核验：
- HVAC：2,628 nodes、2,627 meshes、92,805 条参数；建筑：6,862 nodes、6,861 meshes、134 材质、39 内嵌图片、166,573 条参数。
- `extras.parameters` 与原属性面板的 `BIM.parameters` 包装逐项一致。建筑 39 图片字节、材质值/贴图引用与变换在 Node 后处理前后保持。
- Java 归档与 `verified.glb` 大小、SHA256 一致；原 RVT 与私有上传副本哈希不变。
- 任务派生 `source.rvt` 用 TransmissionData 卸载 6 个 Revit 链接，哈希会变化，不能称所有副本均不变；未保存/修改原件。

证据：`task-112-artifact-check-02.json`、`task-114-artifact-check.json`、`task-114-material-check.json`、各任务 archive JSON。
任务 115 独立核验与 112 的结构、变换、属性、材质、图元布局和世界包围盒相同，见 `task-115-artifact-check.json`、`task-112-vs-115-semantic-check.json`。最终三份产物经实际 HTTP 静态下载均为 200，下载 bytes/SHA256 与归档相同；不存在的 GLB 返回 404，见 `final-http-download-check.json`。这不是浏览器点击验证。
首次 `task-112-artifact-check.json` 错将派生副本纳入原件哈希不变要求，其 false 原报告保留；后续报告明确纠正核验范围，没有篡改历史。

## 场景保存与重载

测试库始终是 `astral_webgpu_acceptance_20261031`，没有重启 Java 连原库。

- 场景：`Everse自动轻量化Demo-20261112`，ID `694ec9d315985a2f943f3c5cd2b6d05b`，网页确认保存由 V1 → V2。
- dev：<https://127.0.0.1:8090/#/editor/694ec9d315985a2f943f3c5cd2b6d05b>
- 正式构建本机预览：<https://127.0.0.1:8092/#/editor/694ec9d315985a2f943f3c5cd2b6d05b>
- dev 整页 reload 后两模型恢复；同一建筑构件 `7b148712-ae82-4cfe-890c-0ff53e4aabba-0016ee3a` 的属性可读；preview 独立打开同一场景也可读建筑属性。
- 两页面均显示 15,685 objects / 3,516,665 vertices / 4,027,407 triangles。此统计不是工程精度或 Revit 元素完整率。
- 5 个既有格式 ZIP，共 73,946,508 bytes（页面 70.52 MB）。离线按 UniqueId 全量比较两模型参数及 BIM 包装，无增删改；节点局部/世界矩阵保留，多 primitive 对应成立。没有改保存格式。
- 包内 `sceneInfo.zip=""` 和旧 `zipSize="0"` 是已有打包行为；以保存 API 返回定位实际归档。

证据：`demo-scene-before-save.json`、`demo-scene-after-save.json`、`demo-scene-package-audit.json`。
截图：`hvac-properties-dev.jpg`、`architectural-properties-dev.jpg`、`dev-saved-reloaded-properties.jpg`、`preview-saved-reloaded-properties.jpg`、任务 113 失败及输入保留截图。

## 检查与当前运行

- Node 15/15，前端 BIM 12/12；C# 协议检查和上游未改算法 SHA 边界检查通过；具名视图回归断言先失败后通过。
- SDK/editor 正式构建通过；插件最终构建 0 errors / 60 warnings，主要为沿用上游警告，不冒称零警告。
- 两仓库 `git diff --check` 通过（现有 LF/CRLF 提示保留）。保存后的日志查询未返回 error/warn，但没有进行覆盖全生命周期的控制台捕获，不能证明全程无警告。
- 当前插件构建目录 `C:\project\3D\Astral3D\astral-service\revit-converter\addin\bin\named-view-20261112`；用户注册指向此 DLL，旧自写引擎及原版验证驱动均保持禁用。
- 记录时 Node PID 18804，自有 Revit PID 52204，8081 仅 loopback。PID 仅为证据，不作为后续结束进程的授权/恒定身份。Revit 保持副屏，不操作用户其它应用。
- 信任提示由用户处理；本机初始化曾等待 5 分钟超时，已真实记录、重新启动，没有隐藏失败或关闭信任校验。普通转换期间不需要用户手动打开/导出模型。

## 已确认取舍、风险与未验证事项

- 只接收已在 Revit 2026 保存的文件；仅主模型、内部坐标、标准临时三维或精确名称、着色/贴图。无旧版兼容功能、链接上传管理、GIS、共享坐标自动对齐、旧 1 mm 指标或优化矩阵。
- 沿用 Everse 默认的缺图退颜色、RGB24/alpha 损失、同路径 tint/fade 去重、RPC/几何读取边界；已观察原版局部绕序/法线问题，不在 demo 中另造内核修正，也不宣称完整 PBR/无损。
- 浏览器画面仍有白色/斑驳区域；未逐构件确定是否来自上游几何/材质、双模型重叠或其它显示因素，未为掩盖它们改成双面材质。多模型展示通过不等于视觉保真通过。
- 标准 Draco 默认压缩；未做逐顶点/拓扑/测量精度验证。保存包审计只核参数、节点/矩阵/图结构，未逐字节核全部几何与纹理缓冲。
- 保留可访问的绝对/网络贴图路径、Autodesk 与 Revit.ini 目录行为；Revit 打开模型本身也未沙箱化。不适合直接暴露为任意不可信公网 RVT 转换服务；无人值守部署、许可/信任生命周期仍需单独验证。
- 未验证全部 RVT/视图/材质范围，未重跑新引擎完整断线/超时/崩溃实机矩阵，未重新做真实下载按钮回归。未重建/另改 Java 后端。
- preview 的 115 已有成功服务/归档证据，但末次浏览器观察在派发前中止；用户继续后内置浏览器报告该会话本地浏览器访问不可用，未把该次完成弹窗/取消加载算作 UI 验证。此前 dev 保存重载和 preview 双模型/属性已实际观察；没有用接口检查冒充浏览器结果。
- 合并前仍需开发者按下面路径手测；当前未提交/推送，不代替此门禁。

## 开发者手测路径

1. 保持测试库与本机资源目录；确认新插件与自有 Revit ready，安全提示仅自行选择“载入一次”。
2. 打开上面的 dev 或 preview 场景，核对两个模型及 V2，点建筑构件查看参数。
3. BIM 上传已有 2026 样本，选择标准三维/着色或准确名称/贴图；等待真实完成，再加载。
4. 在独立测试场景保存，整页刷新，核对模型、属性、相对位置。错误时应保留输入，不出现假的完成模型。
