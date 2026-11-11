# TravelMind：旅行决策与地方文化公平曝光研究MVP

React + TypeScript / Spring Boot / PostgreSQL / Flyway / 高德Web服务。

本分支保存截至2026-11-11的本地源码快照。用途是比赛演示、项目学习和离线研究复现，不是正式商业产品；上传不代表新版本已经部署。AI与BYOK在默认运行说明中保持关闭。

## 目前做到哪里

| 模块 | 已实现 | 实际边界 |
|---|---|---|
| 基础旅行业务 | 登录、画像、景点Top3、反馈及撤销、GPS和导航 | 84个历史地点坐标尚未全部核实；新地名搜索中心不接历史景点距离计算 |
| 实时餐饮 | WGS84转GCJ02、独立高德周边搜索、分页、去重、排除、导航 | 不猜测人均、营业、人流或安静程度，不复用本地景点ID反馈 |
| 行为记录 | 事件幂等、来源追溯、账号会话隔离、保留策略 | 导航不等于到访，事件不等于模型已经学习；未做TravelMemory或Agent |
| AI基础 | 受控operations、权限/限频/并发/持久化预算、可信解释 | 模型选择合法依据ID，由服务端生成解释；默认不发起付费调用 |
| 地名搜索 | 受保护候选API、确认/取消、餐饮中心与旧请求失效 | 本地开发分支功能；不能以此宣称任意地区有个性化景点数据 |
| 公平曝光研究 | 原评分复用、距离/相关性/公平三组离线策略、多种子对照 | 尚未接入生产推荐；统计的是模拟入选，不是真实曝光 |
| 独立网页 | 报告回放、调参重算、结果导入、答辩摘要、可核对的推荐取舍 | 内置6节点18查询均为合成测试，不是真实叶县研究结论 |

叶县三个试点尚无完整实地采集、独立标注和真实用户实验。不能把程序测试或“假设核验完成”写成实证结果。收集、标注和冻结辅助工具仍保留在本地资料包，本分支不上传个人申报书、联系方式、原始调查附件或数据库备份。

## 目录

- `backend/`：Spring Boot业务、V1–V17迁移及Java离线实验测试代码。
- `frontend/`：现有React业务页面。
- `research/web-demo/`：独立本地文化导览实验网页及合成夹具。
- `docs/experiments/`：指标、输入和证据核验方法。
- `deploy/`：仅含模板、容器定义、备份恢复脚本，不含生产.env或数据。

## 快速查看实验网页

需要Node.js 24。无需数据库、地图Key或模型Key。

```shell
cd research/web-demo
node build.mjs
node server.mjs
```

打开 http://127.0.0.1:5186/ 。进入“实验结果与答辩”，载入合成演示，选第3轮synthetic-q10，查看公平组节点03的“为什么入选”：原相关性第4、公平第3、此前计数、奖励及换入/让出代价。

静态`index.html`是有意随源码提供的演示快照，不是React生产dist。它可用于报告回放；点击实时实验前还需以下Java准备。服务器仅绑定本机，不是手机公网部署。

## 重算与测试独立网页

需要JDK 17（JAVA_HOME或系统PATH）、Maven Wrapper及前端测试依赖。首次下载依赖需要网络，不执行数据库初始化。

```shell
cd frontend
npm ci
cd ../backend
./mvnw test-compile org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/offline-fairness-classpath.txt
cd ../research/web-demo
node lab-build.mjs
node build.mjs
node --test --test-concurrency=1 *.test.mjs
node server.mjs
```

Windows使用`mvnw.cmd`；PowerShell不展开通配文件名时，使用`node --test --test-concurrency=1`（该目录没有历史备份测试）。实时实验仍只接受四个固定合成参数，保留并发1、超时和有界输出，不上传真实位置或改变原引擎。

## 运行基础业务

JDK17、Node24、PostgreSQL17。使用独立开发库和独立测试库；不要连接生产库跑测试。根`.env.example`只列变量名和空值，Spring Boot本身不会自动读取根.env：用IDE或当前终端配置环境变量，Docker Compose才会读取.env。

必需设置`DB_USER`、`DB_PASSWORD`及相应`DB_URL`；集成测试可设置`TEST_DB_URL`。不要使用或提交安装默认密码。保持`AI_ENABLED=false`、`AI_ALLOW_USER_KEYS=false`、`AMAP_ENABLED=false`，无需Key即可测试非外部能力。

```shell
cd backend
./mvnw test
./mvnw spring-boot:run
```

另开终端：

```shell
cd frontend
npm ci
npm test
npm run build
npm run dev -- --host 127.0.0.1 --port 5174
```

前端通过相对`/api`及已有Vite代理访问后端。Flyway只追加迁移；禁止修改历史脚本、清空开发/生产数据或把新分支直接覆盖到生产。

## 本次验证

本地副本前端137项测试及Production build通过；Java源码离线编译通过。后端非数据库与独立网页的最新数量见[本次验证记录](docs/release/source-checkpoint-20261111.md)。本机Docker引擎未正常就绪，未冒充本轮已重跑真实PostgreSQL全量测试；发布分支的GitHub Actions使用独立临时PostgreSQL执行全量验证。具体通过状态以该提交的Actions结果为准。

不上传密钥、Token、用户备份、GPS轨迹、日志、node_modules、target、React dist或个人比赛材料。不修改线上AI预算，不触发GCP部署，不合并main。
