# 参与贡献

Issue 和 Pull Request 都欢迎。这份文档说明怎么把项目跑起来、以及提交前需要确认什么。

---

## 项目结构

```
personality-test/
├── backend/     Spring Boot 后端（Java 17 + PostgreSQL）
├── frontend/    React 前端（TypeScript + Vite）
└── docs/        设计文档与代码导读
```

两个模块**互相独立**：后端不依赖前端，前端通过 HTTP 调后端。
你可以只跑后端，用 `backend/api.http` 或 curl 验证接口。

---

## 环境准备

| 需要 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | `java -version` 能打出来即可 |
| PostgreSQL | 17 | 见 README 的安装步骤 |
| Node.js | 20+ | **只有跑 React 前端才需要**，静态页不需要 |

**Maven 不用装** —— `backend/mvnw` 是项目自带的包装器，会自动下载正确版本。

---

## 跑起来

```bash
# 1. 建库（只需一次）
psql -U postgres -c "CREATE DATABASE personality_mvp;"

# 2. 配置数据库密码（不要写进 application.yml，那个文件要提交）
export DB_PASSWORD="你的密码"          # Windows PowerShell: $env:DB_PASSWORD = "..."

# 3. 启动后端 —— 表结构由 Flyway 自动创建，不用手动建表
cd backend
./mvnw spring-boot:run                 # Windows: mvnw.cmd spring-boot:run
```

后端起来后访问 <http://localhost:8080> 就能看到静态页。

React 前端（可选）：

```bash
cd frontend
npm install
npm run dev                            # → http://localhost:5173
```

---

## 提交前必做

```bash
cd backend  && ./mvnw test        # 单元测试必须全绿
cd frontend && npm run typecheck  # 类型检查必须无错误
```

**CI 会跑这两条，本地先确认能省一轮来回。**

---

## 代码约定

这些约定在 `docs/CODE_GUIDE.md` 里有详细解释，这里只列结论：

### 后端

- **分层**：`controller` 只接参数调 service；业务逻辑全在 `service`；数据访问在 `repository`
- **异常**：所有自定义异常继承 `RuntimeException`（Spring 默认只在非受检异常时回滚事务）
- **实体 ≠ DTO**：永远不要把 `@Entity` 直接序列化成 JSON 返回，一定经过 `dto` 包
- **关联**：实体之间用裸 `Long` 外键，不用 `@ManyToOne`（避开懒加载、N+1、循环序列化）
- **事务**：`@Transactional` 只加在 service 的写方法上；**事务里绝不调用外部 HTTP 接口**
- **注释**：解释"为什么这么做"，不解释"这行在干什么"。代码本身能说明后者

### 前端

- **类型**：`types.ts` 里的接口和后端 DTO 一一对应，改后端字段时**必须同步改这里**
- **状态**：能用现有 state 算出来的值，不要再用 `useState` 存一份
- **联合类型**：多状态用 `{kind: 'a'} | {kind: 'b'}`，不要用一堆 boolean
- **外部内容**：渲染任何来自服务端或大模型的文本，用 `textContent` / `{text}`，**不要用 `innerHTML`**

### 都适用

- **不要提交密钥**。API Key 走环境变量，数据库密码走 `DB_PASSWORD`
- **不要改已经执行过的 Flyway 脚本**（`V1__`、`V2__`…）。要改表结构就加新脚本

---

## 开发流程

即使是个人项目，也按真实软件工程的方式走：

```
Issue → Branch → Code → Test → Pull Request → Merge
```

分支命名建议：

| 前缀 | 用途 | 例子 |
|---|---|---|
| `feat/` | 新功能 | `feat/result-comparison` |
| `fix/` | 修 bug | `fix/duplicate-submit-race` |
| `docs/` | 只改文档 | `docs/api-examples` |
| `refactor/` | 不改行为的重构 | `refactor/extract-scoring-service` |

**提交信息**用一句话说清"做了什么、为什么"，例如：

```
fix: 连点选项时取消未执行的跳转定时器

原来每次点击都新排一个 setTimeout 却从不取消，连点两次会跳过一道题。
```

---

## 添加数据库迁移

1. 在 `backend/src/main/resources/db/migration/` 新建 `V{下一个版本号}__{描述}.sql`
2. **只加新文件，绝不修改已执行过的脚本**——改了会让所有已部署环境的校验和失败
3. 启动应用，Flyway 会自动执行并记录到 `flyway_schema_history`

---

## 报告问题

提 Issue 时请附上：

- 复现步骤（越具体越好）
- 期望行为 vs 实际行为
- 后端日志里的报错（如果有）
- 浏览器控制台的报错（如果是前端问题）

---

## 许可

提交贡献即表示你同意以本项目的 [MIT 许可证](LICENSE) 发布你的代码。
