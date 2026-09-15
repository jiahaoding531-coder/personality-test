# personality-test

> 两条完整可跑的链路：
> **人格测试 → 5 维画像 → AI 个性化反馈**，
> 以及 **旅行偏好测试 → 8 维画像 → 结合定位的 Top 3 推荐**。
> 两套前端（零构建静态页 + React），157 个自动化测试，CI 全绿。

[![CI](https://github.com/jiahaoding531-coder/personality-test/actions/workflows/ci.yml/badge.svg)](https://github.com/jiahaoding531-coder/personality-test/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

---

## 这是什么

用户在浏览器里答完 20 道题，后端把答案换算成 **5 个人格维度**的分数（0~100），
存入数据库，并返回一份带解读文案的结果。

**5 个维度**：开放性、外向性、责任心、宜人性、情绪稳定性

## 两条链路

这个仓库里其实有两个流程，它们**共用同一套会话机制和计分引擎**，
但画像的维度、结果页、后续动作完全不同：

| | 人格测试 | 旅行偏好测试（TravelMind） |
|---|---|---|
| 题目 | 20 道，每维度 4 题，含 8 道反向题 | 8 道场景题，每维度 1 题，全正向 |
| 画像 | 5 维，分数连续 | 8 维，分数只能取 0/25/50/75/100 |
| 结果 | 5 维条形图 + 档位解读文案 + AI 深度解读 | 8 维条形图 + **结合定位的 Top 3 地点推荐** |
| 反馈 | AI 解读可重新生成 | 👍/👎 **回写画像** + "换一批" |
| 规模 | 完整 | 59 个杭州模拟景点，不接真实地图和天气 |

旅行侧是 `TravelMind.docx` 计划书的第一阶段——**先把推荐算法本身跑通**，
证明了它准不准之后，再接高德这类真实数据源。推荐分数由确定性算法算出
（兴趣匹配 × 距离衰减 × 质量修正），**AI 生成自然语言理由**是下一步。

## 这不是什么

⚠️ **人格测试的结果定位为「自我探索 / 娱乐性质」，不是心理诊断，也不是专业人格测评。**
它基于一个简化的 5 因子模型，20 道题、固定权重，没有经过心理测量学验证（信效度检验）。
请勿用于任何临床、招聘、评估他人的用途。

⚠️ **旅行推荐用的是模拟数据**：59 个景点的属性是手工标注的，只有杭州一个城市。
真实世界的 POI 查询、天气、营业状态都得接外部 API，那是计划书里的 Phase 3。

同样地，本项目**不实现**：社交、支付、模型训练、微服务。
这些不是遗漏，是刻意排除的范围——**先证明核心价值，再扩大技术复杂度**。

**已有的**：匿名/登录两种模式并存的测试流程、基于 DeepSeek 的个性化解读、
Session Cookie 认证、测试历史、速率限制、Top 3 旅行推荐。详见下方「路线图」。

---

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | Java 17 | Temurin LTS |
| 框架 | Spring Boot 4.1 | Web + Data JPA + Validation |
| 认证 | Spring Security | Session Cookie + BCrypt + CSRF 防护 |
| 数据库 | PostgreSQL 17 | 本机安装 |
| 迁移 | Flyway | 表结构由版本化 SQL 脚本管理 |
| 构建 | Maven Wrapper | **无需单独安装 Maven** |
| AI | DeepSeek（OpenAI 兼容协议） | 可选启用，默认关闭时用桩实现 |
| 前端 | React 19 + TypeScript + Vite | 独立目录 `frontend/`，另有一个零构建的静态页 |
| 测试 | JUnit 5 + MockMvc | 157 个：68 个纯逻辑单测 + 89 个集成测试 |
| CI | GitHub Actions | push/PR 自动跑测试 + 类型检查 |

---

## 快速开始

### 方式一：Docker（一条命令）

装好 [Docker Desktop](https://www.docker.com/products/docker-desktop/) 后，在项目根目录：

```bash
docker compose up
```

起来之后：

| 地址 | 是什么 |
|---|---|
| **<http://localhost:3000>** | **React 前端**（从这里进） |
| <http://localhost:8080> | 后端 API，同时托管着一个零构建的静态页 |

**不需要装 JDK、PostgreSQL、Node** —— 全在容器里。
数据库数据存在具名卷里，`docker compose down` 不会丢，
想彻底清空用 `docker compose down -v`。

想启用 AI 解读：

```bash
DEEPSEEK_API_KEY=sk-xxx AI_ENABLED=true docker compose up
```

> **国内提示**：Docker Hub 拉镜像很慢甚至超时，建议先配镜像加速源。
> npm 也慢的话：`docker compose build --build-arg NPM_REGISTRY=https://registry.npmmirror.com`

---

### 方式二：本地直接跑

#### 前置要求

- **JDK 17 或更高** —— `java -version` 能打出来即可
- **PostgreSQL 17** —— 见下方安装步骤
- **Maven 不需要装** —— 项目自带 `mvnw`

#### 第 1 步：安装并启动 PostgreSQL

Windows（推荐用 winget）：

```bash
winget install PostgreSQL.PostgreSQL.17
```

安装过程中会要求设置 `postgres` 超级用户密码，**请记住它**。

macOS：

```bash
brew install postgresql@17 && brew services start postgresql@17
```

Linux（Debian/Ubuntu）：

```bash
sudo apt install postgresql-17 && sudo systemctl start postgresql
```

#### 第 2 步：创建数据库

```bash
# Windows（注意路径，psql 不在 PATH 里的话用全路径）
"C:\Program Files\PostgreSQL\17\bin\psql" -U postgres -c "CREATE DATABASE personality_mvp;"
```

会提示输入第 1 步设置的密码。**表结构不用手动建** —— 应用启动时 Flyway 会自动执行
`src/main/resources/db/migration/` 下的脚本。

#### 第 3 步：配置数据库密码

应用通过环境变量 `DB_PASSWORD` 读取密码。**不要**把密码直接写进 `application.yml`
（那个文件要提交到 Git）。

Windows PowerShell：

```powershell
$env:DB_PASSWORD = "你的密码"
```

macOS / Linux：

```bash
export DB_PASSWORD="你的密码"
```

#### 第 4 步：启动

```bash
cd backend
# 首次运行会下载 Maven 和依赖，约 1~3 分钟
./mvnw spring-boot:run          # Windows 用 mvnw.cmd spring-boot:run
```

看到这行就成功了：

```
Started PersonalityApplication in X.XXX seconds
```

#### 第 5 步：验证

```bash
curl http://localhost:8080/api/health
```

期望返回：

```json
{
  "status": "UP",
  "database": "UP",
  "questionCount": 20
}
```

#### 第 6 步：打开页面

浏览器访问 **<http://localhost:8080>**，就能看到完整界面：

```
首页（介绍） → 开始测试 → 20 道题逐题作答 → 提交 → 结果页
```

结果页是一张**横向条形图**（5 个维度分数）+ 逐维度的中文解读 + 免责声明。
支持深色模式（右上角切换）和表格视图（图表的无障碍等价形式）。

> 这是**单个静态 HTML 文件**（`src/main/resources/static/index.html`），
> 由 Spring Boot 直接托管，**不需要 npm、不需要构建步骤**。
> 把它留着是有意为之：clone 仓库的人不装 Node 也能立刻跑起来看效果。

#### 第 6b 步（可选）：React 前端

想要 TypeScript 版本：

```bash
cd frontend
npm install
npm run dev          # → http://localhost:5173
```

两个版本**功能完全一致**，共用同一个后端。React 版通过 Vite 的
dev server 代理转发 `/api` 请求到 8080，所以开发时不需要处理跨域。

| 命令 | 作用 |
|---|---|
| `npm run dev` | 启动开发服务器（5173），带热更新 |
| `npm run build` | 类型检查 + 生产构建，产物在 `frontend/dist/` |
| `npm run typecheck` | 只做类型检查，不产出文件 |

#### 关于登录（可选）

**不登录也能完整使用**——做测试、看结果、生成 AI 解读都不需要账号。

登录只多做一件事：**把这次测试存进你的历史记录**。所以两种模式是并存的：

| | 未登录 | 已登录 |
|---|---|---|
| 做测试 / 看结果 / AI 解读 | ✅ | ✅ |
| 测试记录会被保存 | ❌（`user_id` 为 NULL） | ✅ |
| 查看历次记录 | ❌（401） | ✅ |

页面右上角「登录 / 注册」进入，注册后会自动登录。

> **会话存在服务端**（HttpSession），浏览器只拿一个 HttpOnly 的会话 ID——
> JavaScript 读不到，所以 XSS 也偷不走。注销时服务端直接销毁会话，
> 是真的失效，不像 JWT 那样存在"令牌还没过期"的窗口期。
>
> 密码用 **BCrypt** 哈希存储（自带随机盐、故意慢），登录失败时
> 「用户不存在」和「密码错误」返回**完全相同**的消息，防止用户名枚举。

也可以用 IntelliJ IDEA 打开 `backend/api.http`，每个请求左边有绿色 ▶ 按钮，
按顺序点一遍就能在纯接口层面跑完整个流程。

#### 第 7 步（可选）：启用 AI 解读

结果页底部的「生成 AI 解读」需要一个 DeepSeek API Key。**不配也能跑**——
这时接口返回 501，页面上会显示明确的失败原因，其余功能完全不受影响。

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY = "sk-你的key"
$env:AI_ENABLED = "true"
cd backend; ./mvnw spring-boot:run
```

```bash
# macOS / Linux
export DEEPSEEK_API_KEY="sk-你的key"
export AI_ENABLED="true"
cd backend && ./mvnw spring-boot:run
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AI_ENABLED` | `false` | 是否装配真实的 AI 实现 |
| `DEEPSEEK_API_KEY` | 空 | API Key，**只从环境变量读** |
| `AI_BASE_URL` | `https://api.deepseek.com/v1` | 换厂商时改这里（通义、智谱、Kimi 多兼容同一协议） |
| `AI_MODEL` | `deepseek-chat` | 模型名 |

> **关于费用**：生成一次约消耗 1000 token（输入提示词 + 输出 400~600 字），
> DeepSeek 的价格大约每次不到 ¥0.01。
>
> **关于重复点击**：同一份画像的报告会被缓存，再次点击直接返回旧结果、
> 不重复调用 API。想强制重跑就在请求里加 `?regenerate=true`
> （页面上对应「重新生成」按钮）。

---

## API 一览

完整文档见 [`docs/api.md`](docs/api.md)。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/health` | 健康检查（含数据库连通性） |
| `GET` | `/api/questions` | 获取 20 道题 + 量表选项 |
| `POST` | `/api/auth/register` | 注册 |
| `POST` | `/api/auth/login` | 登录（建立会话） |
| `POST` | `/api/auth/logout` | 注销（销毁会话） |
| `GET` | `/api/auth/me` | 当前登录用户（未登录返回 200 空体） |
| `GET` | `/api/me/test-sessions` | 🔒 我的测试历史 |
| `POST` | `/api/test-sessions` | 创建测试会话，返回 `sessionId` + `accessToken` |
| `POST` | `/api/test-sessions/{id}/answers` | 🔑 批量保存作答（可多次提交，支持中断续答） |
| `POST` | `/api/test-sessions/{id}/submit` | 🔑 提交并计分，返回画像 |
| `GET` | `/api/test-sessions/{id}/result` | 🔑 查询已生成的画像 |
| `POST` | `/api/test-sessions/{id}/ai-report` | 🔑 AI 个性化反馈（需配置 API Key，否则返回 501） |

旅行偏好测试（TravelMind）：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/questions?scale=TRAVEL` | 获取 8 道旅行偏好题（不传 `scale` 仍是人格题） |
| `POST` | `/api/travel/sessions` | 创建旅行测试会话 |
| `POST` | `/api/travel/sessions/{id}/answers` | 🔑 保存作答 |
| `POST` | `/api/travel/sessions/{id}/submit` | 🔑 提交并计分，返回 8 维旅行画像 |
| `GET` | `/api/travel/sessions/{id}/profile` | 🔑 查询旅行画像 |
| `POST` | `/api/travel/sessions/{id}/recommendations` | 🔑 结合定位推荐 Top 3（`excludeSeen` 用于"换一批"） |
| `POST` | `/api/travel/sessions/{id}/recommendations/{rid}/feedback` | 🔑 👍/👎，返回画像被调整的结果 |

> 🔑 = 需要在 `X-Session-Token` 请求头里带上创建会话时拿到的令牌，
> 或者是该会话的登录所有者。详见 [`docs/api.md`](docs/api.md)。

### 最小调用示例

```bash
# 0. 先拿 CSRF 令牌（详见 docs/api.md）
JAR=/tmp/cookies.txt
curl -s -c $JAR -b $JAR http://localhost:8080/api/auth/me
CSRF=$(grep XSRF-TOKEN $JAR | awk '{print $NF}')

# 1. 创建会话 —— 拿到 sessionId 和 accessToken
RESP=$(curl -s -X POST http://localhost:8080/api/test-sessions \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" -c $JAR -b $JAR)
SID=$(echo "$RESP" | jq -r .sessionId)
TOKEN=$(echo "$RESP" | jq -r .accessToken)
# → {"sessionId":1,"accessToken":"b3993532-...","status":"IN_PROGRESS"}

# 2. 提交答案（每次都要带 sessionToken）
curl -X POST "http://localhost:8080/api/test-sessions/$SID/answers" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" -H "X-Session-Token: $TOKEN" \
  -c $JAR -b $JAR \
  -d '{"answers":[{"questionId":1,"score":5},{"questionId":2,"score":4}]}'

# 3. 提交计分  ← 闭环收口
curl -X POST "http://localhost:8080/api/test-sessions/$SID/submit" \
  -H "X-XSRF-TOKEN: $CSRF" -H "X-Session-Token: $TOKEN" -c $JAR -b $JAR

# 4. 查询结果
curl "http://localhost:8080/api/test-sessions/$SID/result" -H "X-Session-Token: $TOKEN"
```

---

## 计分规则

```
用户原始选择 (1~5)
      ↓
① 反向计分：reverse_scored = true 的题 → 有效分 = 6 - 原始分
      ↓
② 维度求和：同一维度的有效分相加 → 范围 [itemCount, itemCount × 5]
      ↓
③ 归一化：  (rawSum - itemCount) / (itemCount × 4) × 100  → 0.00 ~ 100.00
```

**20 道题，每维度 4 题，其中 8 道反向计分（40%）。**

归一化的三个边界：

| 原始分 | 归一化结果 |
|---|---|
| 4（4 题全选 1） | `0.00` |
| 12（4 题全选 3） | `50.00` |
| 20（4 题全选 5） | `100.00` |

即每道题的权重恰好是 `6.25` 分。

> `itemCount` 是**算出来的**而不是硬编码 4 —— V0.2 想把某个维度加到 6 题，
> 只需往数据库插数据，`ScoringService` 一行都不用改。

---

## 项目结构

```
personality-test/
├── README.md
├── LICENSE
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
├── docker-compose.yml          ★ 一条命令跑起全部三个服务
├── docs/
│   ├── CODE_GUIDE.md           ★ 代码导读：Spring 概念 ↔ 已有 Java 知识
│   ├── FRONTEND_GUIDE.md       ★ React 版导读：与静态页的对照
│   ├── api.md                  接口文档
│   └── MVP.md                  功能边界与验收标准
│
├── backend/                    ── Spring Boot 后端 ──
│   ├── Dockerfile              多阶段构建（Maven 构建 → JRE 运行）
│   ├── .dockerignore
│   ├── pom.xml                 Maven 依赖清单（≈ pyproject.toml）
│   ├── mvnw / mvnw.cmd         包装器，无需全局安装 Maven
│   ├── api.http                ★ IntelliJ HTTP Client 调试文件
│   └── src/
│       ├── main/java/com/example/personality/
│       │   ├── domain/         领域对象（不依赖 Spring）
│       │   ├── entity/         JPA 实体，映射数据库表
│       │   ├── repository/     数据访问接口（无需实现类）
│       │   ├── service/        业务逻辑 + 事务边界
│       │   ├── controller/     HTTP 接口层
│       │   ├── dto/            请求/响应对象
│       │   ├── security/       自定义 UserDetails（携带用户 id）
│       │   ├── exception/      自定义异常 + 全局处理
│       │   ├── ai/             提示词构建 + DeepSeek 客户端 + 桩实现
│       │   └── config/         Security / CORS / AI 配置
│       ├── main/resources/
│       │   ├── application.yml 应用配置
│       │   ├── static/index.html  ★ 零构建演示页，Spring Boot 直接托管
│       │   └── db/migration/   Flyway 迁移脚本（V1 ~ V9）
│       └── test/java/com/example/personality/
│           ├── service/ScoringServiceTest.java        13 个人格计分断言
│           ├── service/ScoringServiceTravelTest.java   7 个旅行计分断言
│           ├── service/RecommendationEngineTest.java  16 个推荐算法断言
│           └── ai/AiPromptBuilderTest.java             6 个提示词约束断言
│
└── frontend/                   ── React + TypeScript 前端 ──
    ├── Dockerfile              多阶段构建（Vite 构建 → nginx 托管）
    ├── nginx.conf              静态托管 + /api 反向代理
    ├── .dockerignore
    ├── vite.config.ts          含 /api 开发代理
    └── src/
        ├── types.ts            后端 DTO 的类型映射
        ├── api.ts              请求封装 + CSRF + 统一错误处理
        ├── App.tsx             状态机
        ├── screens/            Home / Test / Result / TravelResult / Auth / History
        ├── components/         BarChart / AiPanel / ThemeToggle / Status
        └── styles.css          设计令牌 + 全部样式（含深色模式）
```

> **为什么根目录没有 `ai/` 和 `tests/`？**
> 计划书里画的结构假设 AI 将来可能是**独立的服务**（比如 Python 写的推荐系统），
> 测试也可能跨模块。但实际情况是：
>
> - AI 代码是后端的几个 Java 类，放在 `backend/src/main/java/.../ai/` 包里。
>   单独抽成根目录的 `ai/` 会让一个 Maven 模块的源码散落在两处，编译器根本找不到
> - 测试是**每个模块各管各的**（`backend/src/test/`、将来的 `frontend/src/**/*.test.ts`），
>   根目录再放一个 `tests/` 只会让人不知道该往哪写
>
> 结构的目的是让人一眼找到东西，而不是机械照搬一张图。

---

## 测试

```bash
cd backend && ./mvnw test        # 157 个测试
cd frontend && npm run typecheck # 类型检查（前端还没有单测，见路线图）
```

```
Tests run: 157, Failures: 0, Errors: 0, Skipped: 0
```

分成两层，**各自解决不同的问题**：

| 层 | 数量 | 需要数据库 | 耗时 | 测什么 |
|---|---|---|---|---|
| **纯逻辑单元测试** | 68 | ❌ | 0.15 秒 | 计分算法（两套量表）、推荐引擎、提示词约束 |
| **集成测试** | 81 | ✅ | ~30 秒 | HTTP 契约、安全规则、事务、用户隔离 |

**纯逻辑单元测试不需要数据库** —— 它们直接 `new ScoringService()` /
`new AiPromptBuilder()`，不启动 Spring 容器。这是把核心逻辑与框架解耦换来的：
算法和提示词的回归能在 0.15 秒内跑完，不需要任何环境准备。

**集成测试需要一个测试库**：

```bash
# 只需建一次
psql -U postgres -c "CREATE DATABASE personality_mvp_test;"
```

表结构不用手动建——Flyway 在测试启动时会自动跑一遍 V1~V9 迁移
（顺带验证了迁移脚本本身是可执行的）。

> ⚠️ **改动或重命名迁移文件后必须先 `./mvnw clean test`。**
> Maven 复制资源时不会清理 `target/classes` 里已经消失的源文件，
> 于是新旧两个同名迁移会同时出现在 classpath 上，报
> `Found more than one migration with version N`——看着像代码问题，其实是构建残留。

> **为什么集成测试不用 H2 之类的内存数据库？**
> 本项目的表结构用了 PostgreSQL 特有的东西：`timestamptz`、
> `numeric(5,2)`、`GENERATED BY DEFAULT AS IDENTITY`。
> H2 就算开 PostgreSQL 兼容模式也对不上，测出来的结果没有意义。
>
> CI 里用 GitHub Actions 原生的 `services: postgres` 起真容器，
> 配置见 `.github/workflows/ci.yml`。

覆盖范围：反向计分镜像、归一化的三个边界、每题 6.25 分、
正反向混排的具体场景、`itemCount` 可变、缺维度/分值越界/空列表的拦截、返回值不可变。

---

## 设计决策

几个刻意的选择，以及为什么：

| 决策 | 原因 |
|---|---|
| **实体间不用 `@ManyToOne`**，只存裸 `Long sessionId` | 规避懒加载代理、N+1 查询、循环序列化三个新手最难 debug 的坑 |
| **DTO 不暴露 `reverseScored`** | 否则用户能在浏览器里看到哪些题是反向的，据此操纵结果 |
| **`ddl-auto: validate`** 而非 `update` | 表结构的唯一管理者是 Flyway，不让 Hibernate 抢管理权 |
| **`open-in-view: false`** | 不跨请求持有数据库连接，避免高并发下连接池耗尽 |
| **所有自定义异常继承 `RuntimeException`** | Spring 默认只在非受检异常时回滚，受检异常会导致"抛异常了但事务提交了" |
| **AI 调用放在事务外** | 外部 API 耗时长，包在事务里会占满连接池 |
| **`itemCount` 参数化** | 扩题量时不用改代码 |
| **`BigDecimal` 而非 `double`** | 分数要展示、比较、写测试断言，浮点误差会带来持续困扰 |
| **`EnumType.STRING`** | `ORDINAL` 在枚举重排时会静默损坏全部历史数据 |

---

## 路线图

### V0.1 ✅ 后端完整闭环

- [x] 20 道题 / 5 维度 / 8 道反向计分
- [x] 答案提交、计分、画像存储、结果查询
- [x] 幂等性保护（重复提交返回 409）
- [x] 统一错误响应（400 / 404 / 409 / 501）
- [x] 13 个计分单元测试

### V0.2 ✅ 页面 + AI

- [x] 单文件静态页面（首页 / 答题卡 / 结果页 / 深色模式 / 表格视图）
- [x] 接入 DeepSeek 生成个性化解读（含缓存与重新生成）
- [x] 提示词独立成可单测的纯逻辑类（6 个测试）

### V0.3 ✅ React 前端

- [x] Vite + React 19 + TypeScript 工程（`frontend/`）
- [x] 类型安全地对接全部 6 个接口
- [x] 三个页面 + 答题卡 + 条形图 + AI 解读面板
- [x] 深色模式、减少动态效果适配

### V0.4 ✅ 用户体系

- [x] Spring Security + Session Cookie 认证
- [x] 注册 / 登录 / 注销 / 当前用户
- [x] 测试会话自动关联登录用户（未登录仍可匿名测试）
- [x] 我的测试记录（含 5 维缩略图，可点进查看完整结果）
- [x] CSRF 防护、防用户名枚举、会话固定攻击防护

### V0.5 ✅ 自动化测试与 CI

- [x] 集成测试基础设施（`@SpringBootTest` + 真实 PostgreSQL + 事务回滚）
- [x] 认证与授权测试（CSRF、防用户名枚举、防会话固定攻击）
- [x] 测试流程与幂等性测试（重复提交 409、未答完 400、答案可改）
- [x] 历史数据隔离测试（防越权访问）
- [x] GitHub Actions：push/PR 自动跑测试 + 前端类型检查和构建

### V0.6 ✅ 速率限制

- [x] 登录限流：按用户名 5 次/15 分钟 + 按 IP 20 次/15 分钟，双维度独立生效
- [x] 注册限流：按 IP 10 次/小时
- [x] 滑动窗口算法、`Retry-After` 响应头、内存自动清理
- [x] 10 个集成测试覆盖（含"换 IP 不能绕过"和"正常用户不被误伤"）

### V0.6.1 ✅ 修复越权漏洞（IDOR）

- [x] 给会话加随机 UUID 访问令牌（V5 迁移，历史数据自动回填）
- [x] 四个会话端点校验：令牌匹配 **或** 是登录用户本人的会话
- [x] 拒绝时返回 404 而非 403（不泄露会话是否存在）
- [x] 13 个回归测试，把实测确认过的两条攻击路径钉死

> **修复前**实测能成功、**修复后**返回 404 的两条攻击：
> ```
> ① 匿名 GET  /api/test-sessions/24/result   → 读到别人的画像
> ② 匿名 POST /api/test-sessions/22/answers  → 写进别人未提交的会话
> ```
> 根因是把 `permitAll` 当成了访问控制——**认证不等于授权**。
> 详见 [`docs/CODE_GUIDE.md`](docs/CODE_GUIDE.md) 第 10.7 节。

### V0.7 ✅ Docker 化

- [x] 后端多阶段构建（Maven 构建 → JRE 运行，非 root 用户）
- [x] 前端多阶段构建（Vite 构建 → nginx 托管 + `/api` 反向代理）
- [x] `docker compose up` 一条命令跑起 postgres + backend + frontend
- [x] 数据库具名卷（`docker compose down` 不丢数据）
- [x] 健康检查与启动依赖（等数据库 ready 再启动后端）

**自建镜像体积**：后端 219MB、前端 28.9MB（都是内容大小，不含基础层）。

> 前端镜像只有 28.9MB，因为运行阶段**只用 nginx**——
> 编译完之后 Node 就没用了，留在镜像里纯属浪费。

### V0.8 ✅ TravelMind V0：旅行偏好测试 + Top 3 推荐

- [x] `ScoringService` 泛型化，两套量表共用一套计分算法（`ScaleDimension` 接口）
- [x] 8 道旅行偏好题（V6 迁移）、59 个杭州模拟景点（V7 迁移）
- [x] `RecommendationEngine`：硬过滤 + 兴趣加权匹配 × 距离衰减 × 质量修正
- [x] 旅行画像 `travel_profiles`、推荐记录 `recommendations`、反馈表 `recommendation_feedback`（V9 迁移）
- [x] `/api/travel/**` 五个端点 + `?scale=TRAVEL`
- [x] 前端旅行结果页（8 维画像 + 定位面板 + Top 3 卡片）
- [x] 11 个旅行链路集成测试 + 7 个旅行计分单测

**刻意没做**（下一步）：用户反馈（👍/👎、"换一批"）——表已经建好了，
但没有接口。反馈是整个闭环里最有价值的数据（计划书第十九节的核心指标
"推荐接受率是否随使用次数提升"要靠它），所以排在下一步而不是顺手做。

### V0.9 ✅ 用户反馈闭环

- [x] 👍/👎 接口，反馈记在"某一次推荐的某一条"上
- [x] **反馈回写画像**：问卷画像一个字节都不动，每次推荐时叠加反馈实时算出「有效画像」
- [x] "换一批"是真的换——排除本会话看过、且没被点过 👍 的地点
- [x] 前端反馈按钮 + 画像调整提示（「自然风光 50 → 40」）
- [x] 11 个修正逻辑单测 + 10 个反馈集成测试

> ⚠️ **归因是启发式的**：一条反馈针对一个地点，但地点有 7 个属性维度。
> 系统只能按"这次推荐里贡献最大的维度"来归因，可能出现"因为太吵被否掉、
> 却被算到自然风光头上"的误判。要真正准确得让用户说出原因，那是更重的交互。

### V1.0 ✅ 当前状态层

- [x] 把「此刻的处境」做成独立一层（`RecommendationContext`），**不持久化**
- [x] 状态：`TIRED` / `HUNGRY` / `WANT_WALK`，作用于**地点属性**而不是用户偏好
- [x] 硬约束：**预算上限**（此前 `ticket_price` 查出来了但打分时一分钱都没算）
- [x] 前端 6 个场景按钮：「我想散步一下」「我有点累了」「我想吃饭」
      「只剩 1 小时」「不想走远」「预算不多」

> ⚠️ **踩过的坑**：最初把"我累了"实现成"降低步行意愿"，测试直接红了——
> 兴趣分是归一化加权平均，调小某个维度的权重**不改变排序**。
> 语义也错了：应该是"费腿的地方要变差"（关于地点），不是"我没那么在乎走路了"（关于你）。

### V1.1 ✅ 跨会话记忆（反馈部分）

- [x] 👍/👎 的修正**跨会话累积**——换个会话重新测，之前的反馈依然生效
- [x] "换一批"的排除**仍限本会话**（否则隔天来点一次会把历史全排掉）
- [x] 只取最近 20 条反馈，避免旧偏见撞上 ±40 封顶后锁死

> ⚠️ **匿名用户没有跨会话记忆**——没有稳定的用户身份，"上次"无从谈起。
> 这和"历史记录只有登录用户才有"是同一条边界。
>
> ⚠️ **画像本身仍挂在 `session_id` 上**：用户重新测一次，画像是新的
> （这本来就该重算），继承下来的是**反馈修正**。让画像本身也跨会话复用
> （"下次来不用重新答题"）是另一个产品问题——"什么是用户的当前画像"——
> 值得单独想，没有塞进这一轮。

### V1.2 ✅ 默认自动模式：不再让用户填表单

- [x] 进页面**自动拿定位、直接出推荐**——一个字段都不用填
- [x] **按时间推断处境**：饭点推断「想吃饭」（`ContextInferrer`，纯逻辑可单测）
- [x] 推断**可见可撤销**：结果上方写着「我按 12:30、想吃饭（我猜的）推的」+ 一键否定
- [x] **没把握才追问**：最高分低于 35% 或候选不足 3 个时，才问一句
- [x] 手动表单折叠成「改一下」兜底（定位被拒 / 想纠正系统猜的东西）
- [x] 时间走注入的 `Clock`——否则这个功能中午跑绿、下午跑红

> ⚠️ **现在只推得动时间，别的都推不了**：天气没接、真实 POI 没有、
> 手机的移动状态要 App 才拿得到。**宁可少推也不装作能推**——
> 猜错了比不猜更糟，用户会莫名其妙。
>
> 特别是"天黑了推室内"这种看起来很自然的规则：地点的类别是
> `NATURE`/`MUSEUM`/`FOOD`，**根本没有"室内/户外"字段**，硬推就是瞎猜。

### V1.3 计划中

- [ ] **画像跨会话复用**：现在每次打开都要重答 8 道题
- [ ] **反馈归因改进**：让用户说"为什么不喜欢"（太远/太累/太贵/不合口味），
      而不是系统按"贡献最大的维度"猜
- [x] **「为什么是它」**：把打分的四个因子摊开给用户看（V1.3 完成）
- [ ] **导航前确认**：去这里 / 换一个 / 我先看看别的（半小时的活）
- [ ] **AI 生成推荐理由**：`recommendations.reason` 字段已预留，把结构化的
      `reasons` 喂给 DeepSeek 生成自然语言解释——计划书第七节明确划给 AI 的职责
- [ ] 导航（拼一个高德/苹果地图 URL 跳转，半小时的活）
- [ ] 天气 + 真实 POI：接高德开放平台。**在这之前不要碰 Agent**——
> 计划书第十三节说 Agent 的价值是"调用工具"，而现在一个真实工具都没有，
> 做出来的 Agent 会"判断缺什么信息"，然后发现什么都没得调
- [ ] 多次结果对比（历史页并排两张画像，看变化）
- [ ] 让 `rawScore` / `itemCount` 返回真实值（目前是 `-1` 占位）
- [ ] 前端单元测试（Vitest + Testing Library）
- [ ] **接真实 POI 数据**（高德开放平台）：把 `PlaceRepository.findAll()` 换成
      按定位的边界框查询——现在是模拟数据阶段的简化
- [ ] 历史记录分页（目前最多返回 100 条）
- [ ] 限流阈值改成可配置（目前是 `LoginRateLimiter` 里的常量）

### V1.0

- [ ] Docker 化 + docker-compose 一键启动
- [ ] CI/CD（GitHub Actions）
- [ ] 题目扩充到 30 题，重新标定权重
- [ ] 心理测量学检验（信度、效度）

---

## 参与贡献

Issue 和 Pull Request 都欢迎。开发流程遵循
`Issue → Branch → Code → Test → Pull Request → Merge`。

提交前请确保 `cd backend && ./mvnw test` 全绿，
以及 `cd frontend && npm run typecheck` 无错误，并且没有把任何密钥提交进来。

---

## 免责声明

本项目产出的测评结果仅供**自我探索与娱乐参考**，不构成任何心理诊断、
医学建议或专业评估。请勿将其用于临床诊断、人员筛选或对他人进行评估。

分数由固定权重的简单模型计算得出，未经过信效度检验，且**人格会随情境和时间变化**，
任何单一测评结果都不应被视为对一个人的固定结论。

---

## License

[MIT](LICENSE)
