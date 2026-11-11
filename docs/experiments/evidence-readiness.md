# 地点证据准备检查器

这是一项本地离线工具，用于检查叶县试点资料的结构、来源引用和缺项。它不会验证网页中的事实，不会获取 GPS，不会访问高德、数据库或模型，也不会把材料导入推荐服务。

`structurallyValid=true` 仅表示本检查器覆盖的结构规则未发现错误。它不表示材料真实、最新、许可合适或可以用于正式实验。所有报告固定返回 `dataVerifiedByTool=false`、`experimentAuthorized=false`。

## 使用

在项目根目录用 PowerShell 执行：

```powershell
.\scripts\check-offline-evidence.ps1
```

默认只读 `docs/experiments/data/yexian-pilot-evidence-v1.json`，输出到 `backend/target/offline-evidence-readiness/` 下的新目录。离线编译需本机已有 JDK 17 和缓存的 Maven 依赖；不会启动 Spring 或数据库。

也可指定另一个本地登记和全新的输出目录：

```powershell
.\scripts\check-offline-evidence.ps1 -InputFile 'D:\资料\证据登记.json' -OutputDirectory 'D:\资料\本次检查'
```

输出目录不能预先存在，不覆盖之前的检查。脚本无论在哪个目录启动，均根据脚本位置定位项目。

## 报告与状态

每次生成四个文件：

|文件|用途|
|---|---|
|`summary.md`|中文缺项清单，不重复输出正式坐标或坐标线索数值|
|`report.json`|全局及逐地点诊断，包含检查代码和字段路径|
|`input.json`|与原文件逐字节一致的输入快照|
|`manifest.json`|输入、报告、摘要、相关源码及运行字节码的 SHA-256，含 Java 版本|

校验和用于发现文件变化，不能证明资料事实正确，也不是签名认证。报告末尾的输入快照仍含原登记信息，分享前应检查；不要把私人轨迹、账号或密钥填入登记。

|级别|含义|例子|
|---|---|---|
|ERROR|类型、引用、数值或声明矛盾|失效来源 ID、部分填写坐标、负价格、强行勾选资格|
|GAP|资料仍缺少或缺少逐项复核依据|正式入口坐标未知、人工标注缺失、现行服务条件未确认|
|REVIEW|软件不能替代人工判断|照片许可、实体关系核定、坐标精度、Top3 候选竞争不足|

状态为 `INVALID_REGISTER` 时 Java 入口退出码为 3，并保留诊断报告，脚本报错提醒修复。JSON 损坏、重复键、尾随内容、文件过大或源码路径缺失等使入口退出码为 1；输入或源码预检失败不创建输出目录。写盘中断仍可能留下不完整目录，应保留现场、重新指定新目录，不能使用缺少完整清单或哈希不匹配的报告。

`NOT_READY_FOR_EXPERIMENT` 表示结构检查完成但资料不足。退出码 0 只表示检查成功运行，**未授予实验资格**。本工具没有自动批准或正式实验输入导出功能。

## 当前检查边界

- 支持 `PILOT_EVIDENCE_REGISTER` v1；文件最多 1,000,000 字节，节点最多 100，来源最多 200，JSON 深度最多 20、值数量最多 20,000。未知扩展字段不是完整通用 JSON Schema 校验范围。
- 来源编号和节点编号唯一；逐层检查 `sourceId`、`sourceIds`、`identitySourceIds`，校验日期格式。来源必须有 `checkedOn`；`previousNodeId` 是跨登记关联，不当作来源编号，也不自动验证另一登记。
- 正式坐标和相机线索分别保存。未知保持 `null`，不默认成零；合法零值也不意味着真实入口已确认。`verified` 或 `experimentEligible` 标记不能盖过缺项和结构矛盾。
- 历史 `fullCny`、`halfCny` 只接受非负有限数值或显式 `null`。未知提示待补；不从旧票价决定今天收费。已填写的现行条件仍须核对日期、适用人群、审批来源。
- 人工属性和人气材料即使非空，也不会被自动认证。v1 缺少机器可验证的逐项审核契约，不能靠补入一个对象让它自动变为“就绪”。
- 关系项需两个不同名称、来源和结论说明；状态为 `UNRESOLVED` 或 `RESOLVED`。前者提示缺项，后者仍要求人工复核，不能自动合并、拆分地点。
- 三个候选恰好填满 Top3，无法充分检验“谁进入 Top3”的竞争；即使扩充候选，也要逐轮检查实际可行集合。

## 当前叶县资料的处理建议

1. 县衙博物馆、文庙、叶公古街分别取得可追溯的公共入口或明确街区点位坐标，记录坐标系及核查方法。照片拍摄位置和地址推算值继续作为未采用线索。
2. 逐项确认票价、开放时间、预约及停车条件。暂时未知的保留未知；历史文件只能证明其适用日期内的规定。
3. 完成人工属性标注与独立复核，预先确定人气分组证据和标准；不能以“看起来小众”定义长尾。
4. 核清叶公古街与明清街的实体关系，避免把同一空间重复计算成两处地点。
5. 补足真实且已核验的候选、冻结实验输入后，再进行真实节点的离线对照。当前合成实验结果只验证程序，不能用于宣称现实曝光收益。

## 验证记录

2026-10-28 完成：

- 新增检查器测试 45 项、文件入口测试 9 项，共 54 项通过。先运行失败测试，再逐项实现；补齐了深层来源、关系项、日期缺失、超大版本号、历史金额和 Markdown 编码的边界。
- 全部离线实验 + 原推荐引擎 + 原偏好调整回归：176 项通过，失败 0、错误 0、跳过 0。日志：`backend/target/evidence-readiness-regression-final.log`。
- 离线生产 JAR 打包通过；确认 JAR 不含实验工具及合成样例。打包前已独立执行上述测试，打包阶段不重复测试。未运行数据库集成测试或前端测试；这些部分本轮未改。
- Windows PowerShell 5.1 实际执行两次，四个输出文件逐字节一致。中文脚本使用 UTF-8 BOM，避免系统自带 PowerShell 误读。
- 结构错误 Java 入口返回 3 并保留诊断；已存在目录返回 1，旧报告校验值不变。
- 实际叶县登记：3 个节点，ERROR 0、GAP 34、REVIEW 6；状态 `NOT_READY_FOR_EXPERIMENT`，两个验证/授权字段仍为 false。条目数含多项相关缺口，不代表独立事实或完成百分比。
- 最终报告：`backend/target/offline-evidence-readiness/verified-20261028-a/summary.md`；复现目录：同级 `verified-20261028-b`。早期 `final-20261028-*` 是修复摘要转义前的检查，不作为最终报告。
- 434 个本轮开始前已有文件的 SHA-256 均未改变。只新增下面 7 个文件，不改在线推荐、数据库、生产环境或 Git 提交。

## 本轮文件清单

|文件|用途|
|---|---|
|`backend/src/test/java/com/example/personality/experiment/OfflineEvidenceReadiness.java`|纯离线结构与缺项检查|
|`backend/src/test/java/com/example/personality/experiment/OfflineEvidenceReadinessMain.java`|报告与校验清单输出|
|`backend/src/test/java/com/example/personality/experiment/OfflineEvidenceReadinessTest.java`|检查规则回归|
|`backend/src/test/java/com/example/personality/experiment/OfflineEvidenceReadinessMainTest.java`|文件、防覆盖、可重复性与摘要编码回归|
|`scripts/check-offline-evidence.ps1`|Windows 本地运行入口|
|`docs/experiments/evidence-readiness.md`|本说明与验收记录|
|`docs/superpowers/plans/2026-10-28-evidence-readiness.md`|实施计划与 TDD 过程|
