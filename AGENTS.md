# AGENTS.md

给 AI 编程助手的接力说明。**先读这一份，再动手。**
用户本人看的教学文档在 `docs/CODE_GUIDE.md`（那份是讲"为什么"，这份是讲"别踩什么"）。

---

## 1. 这个仓库是什么

**一个人格测试 + 旅行推荐的项目。** 两件事**在同一个仓库里**，不是两个项目：

- **personality-test** —— 20 题 → 5 维画像 → AI 解读（V0.1~V0.5）
- **TravelMind** —— AI 旅行决策助手（V0.6 之后并入）。8 道旅行偏好题 → 8 维画像 →
  结合**当前时间/位置/天气/用户状态**从 59 个杭州 POI 里推荐 Top 3 → 反馈闭环

旅行功能的原始计划书是 `C:\Users\dingjiahao\Downloads\TravelMind.docx`（24 节）。
**仓库里有一份转换好的纯文本副本：`docs/TRAVELMIND.md`** —— 查内容看这份，
**不要**去解 docx（它是 zip + XML，用文本工具打开是一堆二进制）。
权威原件仍是那个 docx，改计划书时改它、再重新导出。

**技术栈**：Spring Boot **4.1.1**（⚠️ 4.x 的 artifact 名变了：`spring-boot-starter-webmvc` 而非 `-web`）
+ PostgreSQL 17 + Flyway + Spring Data JPA + JUnit 5；前端 React 19 + TypeScript + Vite。

---

## 2. 命令（照抄）

```bash
# ⚠️ 所有 Maven 命令必须在 backend/ 下执行。
#    根目录没有源码，在根目录跑会生成一个没用的 target/ 并且找不到源文件。
cd backend
./mvnw test              # 全部测试
./mvnw clean test        # ⚠️ 改了 record 字段 / 迁移文件后必须 clean，见 §3.1

cd frontend && npm run dev   # 前端，Vite proxy 把 /api 转发到 8080，不用管跨域

# 数据库（本机 PG 跑在 Docker 里，不是宿主机的原生服务）
docker compose up -d postgres
```

**启动后端**需要环境变量（缺了相应功能会降级，不是启动失败）：

| 变量 | 作用 |
|---|---|
| `AMAP_KEY` + `AMAP_ENABLED=true` | 高德：逆地理编码 + 天气。**缺失时引擎仍能跑，只是没有天气/定位** |
| `AI_ENABLED=true` + `DEEPSEEK_API_KEY` | 服务端的 AI key（解读 + 推荐理由） |

⚠️ **key 绝不进仓库**。`AMAP_KEY` 曾出现在历史对话记录里。
⚠️ `application-local.yml` 被 gitignore 了，但 **Spring Boot 只在 `local` profile 激活时才读它**——
所以实际是用环境变量启动的，别以为改那个文件有用。

---

## 3. 铁律

### 3.1 数据库与迁移

- **已执行过的迁移永远不许改**（尤其 `V6__add_session_scale.sql`，它在开发库跑过了）。
  Flyway 会校验已执行迁移的校验和，事后修改会让**所有**迁移过的库启动失败。
  要补东西就新开版本号。这条已写在 `V8__add_session_scale.sql` 的文件头。
- **改了迁移文件 / 改了 record 字段后**：先 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`
  重置测试库，再 `./mvnw clean test`。
  - 不 `clean` → `target/classes` 里残留旧资源，报 `Found more than one migration with version X`（看着像代码问题）
  - 不 `clean` 且改了 record 字段 → 测试类不重编，运行时 `NoSuchMethodError`
- **CRLF 会让校验和对不上**：工作区是 CRLF、仓库存 LF。**同一个库只能由同一种行尾的检出迁移**。
  编辑器把某个迁移存成 LF 就会触发 `Migrations have failed validation`。
- 当前最新迁移是 **V12**。测试库 `personality_mvp_test`，开发库 `personality_mvp`。
- 开发库不受测试库重置影响。

### 3.2 事务边界

- ⚠️ **事务里绝不许有网络调用**。`RecommendationService.recommend` 上有 `@Transactional`，
  全程占着数据库连接（连接池只有 10 个）。
  正确做法见 `AmbientService`：**在事务外**取地名+天气，打包成 `AmbientContext` 当参数传进 `recommend`。
  **往 `recommend` 里加 `RestClient` 之前先想清楚这一条。**
- AI 调用同理。照 `TravelReasonService` 的三段式：**短事务读 → 事务外调模型 → 短事务写**。
- 所有自定义异常继承 `RuntimeException`（Spring 默认只在非受检异常回滚）。

### 3.3 打分与数值边界

- 引擎是**五个因子相乘**：`兴趣 × 距离 × 质量 × 状态 × 天气`，每个都 ≤ 1。
  数据库有 `CHECK (score BETWEEN 0 AND 1)` 卡着。
- ⚠️ **偏向系数必须显式夹到 [-1, 1]**。系数 = `(1 + 偏向 × 地点属性) / (1 + max(偏向, 0))`，
  **偏向低于 -1 时系数变负 → 分数变负 → 撞 CHECK → 接口 500**。
  `RecommendationEngine` 现在按维度求和后夹取。**改打分公式或加状态时先看这一条。**
- ⚠️ **外部依赖必须是软的**：provider 一律返回 `Optional`、**永不抛异常**。
  拿不到天气时 `weatherFactor` **精确等于 1.0**（乘法单位元）——这是既有测试不改一行还全绿的原因。
  「开了开关却没配 key」属于配置错误，**启动时直接炸**（判据：重试有没有用）。
- 前端「为什么是它」按**输入在不在**决定列哪几项，**不是**看等不等于 1.0——
  下雨天推室内地点时天气因子正好是 1.0，那个 1.0 是有信息量的。
- ⚠️ 序列化数字必须 `Locale.ROOT`——德语区小数点是逗号，而逗号又是分隔符。
- ⚠️ **加 `TravelState` 枚举值时必须检查它和已有状态的维度有没有重叠**——
  重叠会相加，相加就可能越界。纪律写在 `TravelState` 的注释里。

### 3.4 安全

- ⚠️ **新加挂在 `session_id` 上的接口必须复用 `SessionAccessGuard`**，
  否则 V0.6.1 修过的越权漏洞（IDOR）会重开。
  拒绝时返回 **404 而非 403**（403 泄露 id 存在）；**令牌格式非法也返回 404**（否则状态码差异泄露格式信息）。
  判断本人时**必须先判 `getUserId() != null`**，否则匿名会话变成谁都能看。
- **BYOK 三条不能破的约束**（访客带自己的大模型 key）：
  1. **base-url 绝不来自请求**。访客只发厂商标识，URL 由 `ai/AiProvider` 枚举查表。
     允许请求带 URL = 服务器成了 SSRF 跳板（内网、`169.254.169.254` 云元数据）。**枚举里没有任何 setter。**
  2. **key 不进日志、不落库、不缓存**。`DeepSeekChatClient` 的 `RestClient` **只配超时**，
     每次调用传绝对 URL + 当次 Authorization。
  3. **401 分两种人**：访客的 key 被拒 → **400**（改输入）；服务端的 key 被拒 → **502**（重试）。
- **AI 调用是花钱的**，端点上的 `SessionAccessGuard` 那行不能省。

### 3.5 测试

- 现共 **279 个**，改动前后都要全绿。
- **跑测试前先确认数据库在跑**（Docker 里的 PG）。本机原生 `postgresql-x64-17` 被
  Windows 智能应用控制拦停，已弃用。
- 不用 H2（表结构用了 `timestamptz`/`numeric`/`IDENTITY` 等 PG 特有语法）。
- ⚠️⚠️ **凡是要断言"结果里有哪几个地点"的集成测试，必须固定 `Clock`**
  （在 `@TestConfiguration` 里加 `@Primary Clock` = `Clock.fixed(...)`）。
  引擎有个硬过滤会排除"这个点已经关门"的地点，**候选集随服务器本地时间变化**；
  **CI 容器是 UTC、本地是 CST**，同一测试两边结果不同 → 本地绿、CI 红。
  `ClockConfig` 的类注释早就警告过，但集成测试一直没全用上。
- **更稳的断言写法**：别赌"哪个地点进前三"（依赖候选集），改成**逐地点精确对账**，
  例如天气那条断言 `weatherFactor == 1 - 0.5 × (1 - indoor/100)`，`indoor` 从 repository 现查。
  这样和时钟、和候选集彻底无关，覆盖面还更大。
- ⚠️ 天气缓存是**进程级单例**，集成测试里必须 `weatherService.clearCache()`，否则用例互相污染。
- 限流计数存内存、`@Transactional` **回滚不了**，测试里必须在 `@BeforeAll`/`@BeforeEach` 手动 `clearAll()`。
- CI：`.github/workflows/ci.yml`，push/PR 自动跑。
  ⚠️ `mvnw` 必须有可执行位（100755）——Windows 上搬移文件会丢，Linux runner 会 `Permission denied`。

### 3.6 高德

- ⚠️ **坐标顺序：经度在前**。高德 URI 和 regeo 的 `position`/`location` 都是。
  **传反了不报错，只是定位到地球另一边。**
- 天气按 adcode 缓存 10 分钟——**这是配额保护，不是性能优化**。
  缓存走注入的 `Clock`（直接取系统时间的话，"10 分钟过去了"在测试里没法发生）。
- ⚠️ **"把 59 个模拟 POI 换成高德真实 POI"这件事不能做**。不是难，是会把算法掏空：
  引擎的主信号是每个地点人工标注的 **7 个属性维度**（自然/文化/美食/摄影/小众/热闹/费腿，0~100），
  高德 POI 搜索返回的名字/地址/分类/坐标里**这 7 个一个都没有**。
  换数据源 = 引擎失去主信号，退化成按距离排序。
  **高德的角色是增强（天气、定位、导航），不是替换数据源。**
- key 是 **Web 服务类型**。前端「导航过去」用的是高德 URI，**纯前端链接、不要 key**。

---

## 4. 当前进度

**主线全部做完了。** Phase 0~6 全 ✅（Phase 5 反而超前），计划书第 24 节第一阶段 5 项全完成，
第 9 节 MVP「必须实现」10 项全打勾。

已完成的能力链路：旅行测试闭环 → 画像（8 维）→ 推荐 Top 3（五因子）→
反馈闭环（👍/👎 + 换一批，**跨会话累积**）→ 打分拆解「为什么是它」→
高德定位/天气 → 一键导航 → **AI 推荐理由**（AI 只解释不参与打分）→
**BYOK** → **自然语言状态输入**（说一句"我累了想找个安静的地方"，AI 翻译成结构化条件，
**摊开给用户看**再重新推荐）。

**明确没做**：RAG（Phase 7）、Agent（Phase 8）、上云（Phase 9，Docker + CI 已有）。

**两处刻意偏离计划书**（都是和用户确认过的）：技术栈用 Vite + Java Spring Boot
（计划书写的 Next.js + Python FastAPI）；第 17 节要求 Issue→Branch→PR 流程，实际是直接 push main。

---

## 5. ⚠️ 立刻要做的第一件事：修「空结果 → 下一次推荐 500」

**这是目前唯一一个用户点两下就能撞上的问题，未修，已 100% 复现。**

**复现**：用户在**杭州以外**（实测平顶山）用真实定位 → 第一次推荐返回 `places: []`
（59 个 POI 全在杭州）→ **第二次点推荐直接 500**。

**根因链**（是 V11 引入的，不是老代码）：

1. 用户位置离所有 POI 都超过 10 公里 → 引擎过滤掉全部候选 → `top` 是**空列表**
2. `saveAll(空)` 什么都不写，**但 `batchRepository.save(...)` 无条件写了一条批次行**
3. 于是 `recommendation_batches` 有 `(session, 1)`，`recommendations` 里什么都没有
4. 下次请求：`nextBatchNo` 从**空的 recommendations 表**算出 `0+1 = 1`
5. 再插 `(session, 1)` 的批次行 → 撞 `uq_recommendation_batches` → **500**

位置：`backend/src/main/java/com/example/personality/service/RecommendationService.java`
（`nextBatchNo` 在 **:226**，`saveAll` 在 **:227**，`batchRepository.save` 在 **:237**）。

**修法（两处）**：

- **`top` 为空时不写批次行**——没有推荐就没有"这批是怎么算的"可解释。
  这也恢复了"每条批次行都有对应推荐"这个不变量。
- **`nextBatchNo` 取两个表的最大值**——防已经产生的脏数据
  （库里 session 9、11 已经是"有批次行、没推荐行"的状态，**不修的话它们会一直 500**）。

**顺带**：空结果本身是对的（数据就只有杭州），但**前端得把原因说清楚**——
"59 个地点全在杭州，你离得太远了"，而不是让用户以为功能坏了。

---

## 6. 待办

- **计划书第 5 节要的自然语言反馈还没做**：`RecommendationFeedback.Reaction` 仍然只有
  LIKE/DISLIKE，而"我累了"这类自由文本反馈进不来。加值的话**要改迁移**（CHECK 里写死了这两个值）。
  ⚠️ 注意区分：自然语言**状态**输入已经做了（`/interpret` 端点），
  没做的是自然语言**反馈**（对某条推荐说"我不想走太远"）。这是两回事。
- `RecommendationContext` 里还没有天气和"已去地点"。
- `QuestionService.countQuestions(scale)` 是**死代码**（全仓只有定义、无调用点），要么删要么用起来。
- BYOK 没做自定义 base-url（见 §3.4 约束 1）；也没做 AI 端点限流（非目标）。
- 人格侧 V0.5 搁置项：多次结果对比、让 `rawScore`/`itemCount` 返回真实值（现在返回 `-1`）、
  前端单元测试、历史分页。**这几项未复核是否还成立。**
- ⚠️ `TravelFlowIntegrationTest` 断言 `places.length() == 3`，
  跑在 UTC 深夜（杭州凌晨）会红。**这是改动之前就存在的**，尚未处理（见 §3.5 的 Clock 规矩）。

---

## 7. 和这个用户怎么协作

**CS 大学生（2026 年 9 月）。Java 语言层扎实，Java 工程层空白**——这个落差决定了该怎么写代码和讲解。

- **不用再教**：OOP、泛型（手写过 `MyHashMap<K,V>`）、集合、异常、文件 IO、
  **JDBC + PreparedStatement + 手写事务**（最强的一块）、多线程基础。
- **必须显式讲解**：Maven、JUnit、Git、任何框架、自定义注解、
  **lambda / Stream / Optional**（`@Override` 是他唯一用过的注解）。
- **关键切入角度**：他 **Python 侧的工程习惯反而更强**（已在用 `uv` + `pyproject.toml` + `.venv`、
  sklearn、手写过 LLM 工具调用循环）。
  所以 **Maven 对他来说是"换个写法的 pyproject.toml"，不是新概念**——用这个类比最有效。
- **注释密度可以高，每一处框架特性都应映射到他已经会的东西**
  （如 `@Transactional` ↔ `setAutoCommit(false)`），否则 Spring 会变成黑盒。
  `docs/CODE_GUIDE.md` 整份文档就是按这个思路写的，是新代码注释的范本。
- 学习方式是 **Project Driven Learning**，偏好"学一点立刻用一个"。
- 他可能自己起了实例在跑旧代码——**改了代码要提醒他重启**。
- **环境坑**：bash 的 `/tmp` 是 `AppData\Local\Temp`，但 Python 在 D 盘会解析成 `D:\tmp\`——
  跨 bash/Python 传路径必须写完整 Windows 路径。
  终端中文乱码是代码页问题（非数据问题），查库前 `export PGCLIENTENCODING=UTF8`，
  psql 里别用中文列别名。
  **跑完整后端测试时别并着跑别的大任务**（本机内存 15.5G，会把 PG 压到降级状态）。

---

## 8. 去哪读更多

| 文件 | 内容 |
|---|---|
| `docs/CODE_GUIDE.md` | **930 行**，把 Spring 挂在已有 Java 知识上的教学文档。**改动前先看**。第 9.3 节讲条件装配范式为什么被拆掉 |
| `docs/FRONTEND_GUIDE.md` | 前端要点 |
| `docs/api.md` | 接口契约 |
| `docs/MVP.md` | 范围定义 |
| `backend/src/api.http` | 可直接执行的请求样例 |

**核心设计决策速览**（细节见 CODE_GUIDE）：

- `ScoringService` / `RecommendationEngine` 是**纯 Java 类**——无注入依赖，可直接 `new` 出测试。
  这是全项目最重要的架构切分。
- 实体间**只用裸 `Long sessionId`，不用 `@ManyToOne`**，规避懒加载 / N+1 / 循环序列化。
- DTO 层**不暴露 `reverseScored`**，否则用户可反推反向题操纵结果。
- AI **只做理解与解释，绝不参与打分**；推荐接口和 AI 接口**是分开的**
  （推荐毫秒级、AI 几秒，不能绑一起）。
- 报告默认缓存复用，`?regenerate=true` 强制重跑。
- 上游故障返回 **502** 不是 500。
