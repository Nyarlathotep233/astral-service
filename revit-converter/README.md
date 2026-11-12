# H3D Revit → GLB demo

固定 Everse `v4.5.242` / `fb1832d0784288f13927395b1b8a20f278570a17` 导出核心，接回现有 Java BIM 上传、归档及网页编辑器。只有这一条运行引擎；不回退旧自写导出器。

## 范围

- 输入为已经在 Revit 2026 保存的 RVT；不自动升级旧版本，不保存源文件。
- 主模型几何；不提供链接文件上传/管理。Revit 自身打开文件时的外部资源访问不等于已被沙箱隔离。
- `Default` 创建临时标准等轴测三维视图，不等于原生默认三维视图；`Name` 精确匹配已有非模板三维视图。
- `Colour` 使用上游普通材质，`Realistic` 使用上游支持的 diffuse 贴图；保留上游材质能力边界。`DisplayStyle` 只是沿用协议字段名，不修改 Revit 视图显示样式。
- 仅内部坐标 `Origin`，`relocateTo0=false`。多模型可共同加载，但不承诺不同内部坐标依据模型的自动对齐。
- 上游 raw 单文件 GLB，压缩为 `None`；Node 只做属性包装及标准 Draco。无实例化、合并、减面、居中或第二套材质/几何内核。
- 基础参数不改名、不改值：上游 `extras.parameters` 保留，并包装为 `extras.BIM.parameters[{GroupName:'参数',Parameters:[{name,value}]}]` 供原面板读取。仍是上游平铺参数，不恢复原生分组或重复同名项。

## 构建与启动

Node 22+、已许可并可交互运行的 Revit 2026。本机验证版本为 26.0.4.409；插件构建/注册见 [addin/README.md](addin/README.md)。安全或插件信任提示必须由用户手动处理，不关闭安全校验。

在此目录执行 `npm ci`，依赖独立安装，不借用前端缓存路径。主依赖固定为 glTF Transform 4.3.0、draco3d 1.5.7、ws 8.18.1。

本机隔离验收示例（只启动转换器，不重启既有 Java/前端）：

```powershell
$testRoot = 'C:/project/3D/Astral3D/Astral3D/.codex-logs/service-acceptance-20261031'
$env:H3D_RVT_SOURCE_ROOT = "$testRoot/bim-sources"
$env:H3D_RVT_WORK_ROOT = "$testRoot/revit-converter-work/everse"
$env:H3D_RVT_EXECUTABLE = 'D:/software/AutoDesk/RVT2026/Revit 2026/Revit.exe'
npm start
```

先核对 8081 未被占用及插件部署，不能同时启动旧服务。不要裸启动 Java 后端连接原库；继续使用已有 `astral_webgpu_acceptance_20261031` 测试库和资源目录，详见前端 `LOCAL_WEBGPU_ENVIRONMENT.md`。后台启动 Node 时用 `Start-Process -WindowStyle Hidden` 并记录本次 PID/日志；Revit 的授权提示保持可见。

服务只监听 `127.0.0.1:8081`，拒绝带 Origin 的浏览器 WebSocket 连接；沿用 Java 已鉴权入口，转换器不直接开放给远端浏览器。这里不是独立的多租户安全边界。

## 协议与生命周期

Java 侧串行排队，转换器一次只接受一个活动任务；忙碌时真实失败，不另建任务平台。文本 `ping` 回 `pong`。

请求：

```json
{"fileId":123,"filePath":"C:\\...\\bim-sources\\uuid\\sample.rvt","options":{"useDraco":true,"optimize":false,"exportProperty":true,"view":"Default","viewName":"","displayStyle":"Colour","coordinateReference":"Origin"}}
```

只支持上面的范围；不支持的选项明确失败，不自动换配置。回复保持原数值 `fileId` 与原字符串 `filePath`：

```json
{"fileId":123,"filePath":"原请求路径","type":"progress","progress":80}
{"fileId":123,"filePath":"原请求路径","type":"completed","gltfPath":"123-唯一标识.glb","runSeconds":12.5}
{"fileId":123,"filePath":"原请求路径","type":"failed","message":"实际失败原因"}
```

`gltfPath` 是 basename。转换器将解码检查后的 GLB 原子发布到上传 RVT 同目录，Java 按原流程归档后删除该临时产物；源 RVT 和独立任务证据保留。

- 每次启动只管理自己 spawn 的 Revit handle；随机命名管道，以 nonce/PID/2026 版本握手校验。插件管道限当前用户。
- 源文件 `realpath` 必须处于显式上传根；工作根与源根不能互相包含；插件 raw 产物必须处于本任务目录。
- 启动等待 5 分钟，任务执行上限 60 分钟（Java 等待 65 分钟）。失败、连接断开、超时不重放该模型；自有实例失效后尝试一次恢复，恢复失败保持不可用。
- 成功只能在上游返回、文档关闭、标准 Draco 写出并回读通过后发布。每次请求最多一个终态。
- `service.jsonl`、每任务 `task.jsonl`、raw GLB、后处理配置/报告留在工作目录；不自动清理历史测试数据。

## 已知取舍与风险

按当前 demo 决定沿用 Everse 默认行为，以下不是完整正确性验收：

- 部分真实样本存在局部绕序/法线不一致，暂不修几何、不用双面材质掩盖。解析通过不代表所有构件渲染正确。
- 上游图片重新编码为 RGB24，会丢失图片 alpha；同一路径图片去重不区分不同 tint/fade，因此不能称纹理无损或所有材质一致。
- 找不到上游支持的贴图时可能退为颜色；本期不增加缺图失败或固定 Autodesk 目录限制。
- 上游会读取模型引用的可访问绝对图片路径、Autodesk 材质目录及 Revit.ini 配置的附加纹理目录。路径读取不是 RVT 上传根的附属沙箱；不要将此 demo 当作可安全处理任意不可信公网 RVT 的服务。
- 上游未支持的材质/schema、RPC 代理与其几何读取替代行为沿用其边界，不能声称完整 PBR 或与 Revit 逐项一致。
- 不承诺工程测量精度、1 mm 误差、大坐标双精度、三坐标/GIS 或旧模型升级内容完整性。Draco 使用标准库默认参数，不做私有顶点 ID、拓扑精度回读或量化重试。

## 检查

本机实际结果、已确认取舍、未验证事项与开发者手测路径见 [2026-11-12 验收记录](ACCEPTANCE-20261112.md)。

`npm test` 只使用临时微型夹具/模拟 worker 和独立随机 loopback 端口，不启动 Revit、不访问业务数据库。覆盖标准 Draco 回读、参数保留、坏产物失败、路径/选项边界、任务唯一终态、超时及自有进程恢复。

真实网页验收另行使用隔离测试场景：上传 2026 RVT → 完成或真实失败 → 下载/加载 → 选构件看属性 → 加入第二模型 → 保存 → 整页重载；dev 与 production preview 都需浏览器验证。单元测试不能替代这条闭环，也不能证明上游几何/材质正确。

Everse MIT 许可证及依赖声明保留在 vendor；Node 依赖及锁文件在本目录。不自动提交或推送。
