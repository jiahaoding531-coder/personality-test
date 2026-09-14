# personality-test

> 一个人的**人格测试 → 5 维画像 → AI 个性化反馈**闭环。
> V0.1 完成的是后端主干链路：答题、计分、存画像、查结果。

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

## 这不是什么

⚠️ **本项目的测评结果定位为「自我探索 / 娱乐性质」，不是心理诊断，也不是专业人格测评。**
它基于一个简化的 5 因子模型，20 道题、固定权重，没有经过心理测量学验证（信效度检验）。
请勿用于任何临床、招聘、评估他人的用途。

同样地，本项目**不实现**：用户注册登录、社交、支付、推荐算法、模型训练、微服务。
这些不是遗漏，是 V0.1 刻意排除的范围。

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
| 测试 | JUnit 5 | 纯单元测试，不依赖数据库和网络 |

---

## 快速开始

### 前置要求

- **JDK 17 或更高** —— `java -version` 能打出来即可
- **PostgreSQL 17** —— 见下方安装步骤
- **Maven 不需要装** —— 项目自带 `mvnw`

### 第 1 步：安装并启动 PostgreSQL

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

### 第 2 步：创建数据库

```bash
# Windows（注意路径，psql 不在 PATH 里的话用全路径）
"C:\Program Files\PostgreSQL\17\bin\psql" -U postgres -c "CREATE DATABASE personality_mvp;"
```

会提示输入第 1 步设置的密码。**表结构不用手动建** —— 应用启动时 Flyway 会自动执行
`src/main/resources/db/migration/` 下的脚本。

### 第 3 步：配置数据库密码

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

### 第 4 步：启动

```bash
cd backend
# 首次运行会下载 Maven 和依赖，约 1~3 分钟
./mvnw spring-boot:run          # Windows 用 mvnw.cmd spring-boot:run
```

看到这行就成功了：

```
Started PersonalityApplication in X.XXX seconds
```

### 第 5 步：验证

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

### 第 6 步：打开页面

浏览器访问 **<http://localhost:8080>**，就能看到完整界面：

```
首页（介绍） → 开始测试 → 20 道题逐题作答 → 提交 → 结果页
```

结果页是一张**横向条形图**（5 个维度分数）+ 逐维度的中文解读 + 免责声明。
支持深色模式（右上角切换）和表格视图（图表的无障碍等价形式）。

> 这是**单个静态 HTML 文件**（`src/main/resources/static/index.html`），
> 由 Spring Boot 直接托管，**不需要 npm、不需要构建步骤**。
> 把它留着是有意为之：clone 仓库的人不装 Node 也能立刻跑起来看效果。

### 第 6b 步（可选）：React 前端

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

### 关于登录（可选）

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

### 第 7 步（可选）：启用 AI 解读

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
| `POST` | `/api/test-sessions` | 创建测试会话，返回 `sessionId` |
| `POST` | `/api/test-sessions/{id}/answers` | 批量保存作答（可多次提交，支持中断续答） |
| `POST` | `/api/test-sessions/{id}/submit` | 提交并计分，返回画像 |
| `GET` | `/api/test-sessions/{id}/result` | 查询已生成的画像 |
| `POST` | `/api/test-sessions/{id}/ai-report` | AI 个性化反馈（需配置 API Key，否则返回 501） |

### 最小调用示例

```bash
# 1. 创建会话
curl -X POST http://localhost:8080/api/test-sessions -H "Content-Type: application/json" -d '{}'
# → {"sessionId":1,"status":"IN_PROGRESS","createdAt":"..."}

# 2. 提交答案
curl -X POST http://localhost:8080/api/test-sessions/1/answers \
  -H "Content-Type: application/json" \
  -d '{"answers":[{"questionId":1,"score":5},{"questionId":2,"score":4}]}'

# 3. 提交计分  ← 闭环收口
curl -X POST http://localhost:8080/api/test-sessions/1/submit

# 4. 查询结果
curl http://localhost:8080/api/test-sessions/1/result
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
├── docs/
│   ├── CODE_GUIDE.md           ★ 代码导读：Spring 概念 ↔ 已有 Java 知识
│   ├── FRONTEND_GUIDE.md       ★ React 版导读：与静态页的对照
│   ├── api.md                  接口文档
│   └── MVP.md                  功能边界与验收标准
│
├── backend/                    ── Spring Boot 后端 ──
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
│       │   └── db/migration/   Flyway 迁移脚本（V1 ~ V4）
│       └── test/java/com/example/personality/
│           ├── service/ScoringServiceTest.java   13 个计分断言
│           └── ai/AiPromptBuilderTest.java        6 个提示词约束断言
│
└── frontend/                   ── React + TypeScript 前端 ──
    ├── vite.config.ts          含 /api 开发代理
    └── src/
        ├── types.ts            后端 DTO 的类型映射
        ├── api.ts              请求封装 + CSRF + 统一错误处理
        ├── App.tsx             状态机
        ├── screens/            Home / Test / Result / Auth / History
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
cd backend && ./mvnw test        # 19 个单元测试
cd frontend && npm run typecheck # 类型检查（前端没有单测，见路线图）
```

```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
```

**这些测试不需要数据库** —— 它们直接 `new ScoringService()` / `new AiPromptBuilder()`，
不启动 Spring 容器，跑完只要 0.15 秒。这是把核心逻辑与框架解耦的实际好处。

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

### V0.5 计划中

- [ ] 多次结果对比（历史页并排两张画像，看变化）
- [ ] 让 `rawScore` / `itemCount` 返回真实值（目前是 `-1` 占位）
- [ ] 前端单元测试（Vitest + Testing Library）
- [ ] 历史记录分页（目前最多返回 100 条）

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
