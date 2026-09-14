# API 文档

Base URL：`http://localhost:8080`

所有请求和响应都是 `application/json; charset=UTF-8`。

> **想直接跑起来看？** 用 IntelliJ IDEA 打开 `backend/api.http`，
> 每个请求左边有绿色 ▶ 按钮，点一下就能发。

---

## 通用约定

### 错误响应格式

**所有**错误返回同一个结构，前端写一处处理逻辑就够：

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "请求参数不合法",
  "path": "/api/test-sessions/1/answers",
  "fieldErrors": [
    { "field": "answers[0].score", "message": "score 最大为 5" }
  ]
}
```

`fieldErrors` 只在参数校验失败（400）时非空，其余情况是空数组。

### 状态码

| 码 | 含义 | 什么时候出现 |
|---|---|---|
| `200` | 成功 | 常规请求 |
| `201` | 已创建 | 创建测试会话、注册账号 |
| `204` | 成功无内容 | 注销 |
| `400` | 参数不合法 | 分值越界、答案列表为空、没答完就提交、**请求体不是合法 JSON**、**路径变量类型不对** |
| `401` | 未认证 | 用户名或密码错误、访问需要登录的端点 |
| `403` | 无权限 | **缺少 CSRF 令牌** |
| `404` | 资源不存在 | 会话 ID 不存在、结果还没生成 |
| `409` | 状态冲突 | 重复提交已提交过的会话、用户名已被占用 |
| `429` | 请求过于频繁 | **触发登录/注册限流**（响应带 `Retry-After` 头） |
| `501` | 未实现 | AI 报告，但未配置 API Key（`AI_ENABLED` 不为 true） |
| `502` | 上游故障 | 大模型服务超时或报错 |
| `503` | 服务不可用 | 数据库连不上（仅健康检查） |

### ⚠️ CSRF 令牌（写操作必读）

项目启用了 CSRF 防护。**所有 POST / PUT / PATCH / DELETE 请求都必须带上
`X-XSRF-TOKEN` 请求头**，否则返回 **403**。

令牌由后端通过 `XSRF-TOKEN` 这个 Cookie 下发（它是可读的，
而会话 Cookie `JSESSIONID` 是 HttpOnly 的，JS 读不到）。

```js
// 前端封装里做的事：
const token = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)?.[1]
fetch('/api/auth/login', {
  method: 'POST',
  credentials: 'include',                    // 带上 Cookie
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': decodeURIComponent(token),  // 带上令牌
  },
  body: JSON.stringify({ username, password }),
})
```

**第一次调用任何接口**（哪怕只是 `GET /api/health`）就会下发这个 Cookie。
所以前端启动时先调一次 `GET /api/auth/me`，既确认登录状态，也顺便拿到令牌。

用 curl 测试时：

```bash
JAR=/tmp/cookies.txt
curl -s -c $JAR -b $JAR http://localhost:8080/api/auth/me          # 拿令牌
TOKEN=$(grep XSRF-TOKEN $JAR | awk '{print $NF}')
curl -s -c $JAR -b $JAR -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"username":"alice","password":"password123"}'
```

> **关于 400 的两个容易漏掉的分支。**
> 它们不是"业务校验失败"，而是**请求本身没法解析**，所以不经过 DTO 上的校验注解：
>
> ```jsonc
> // ① 请求体不是合法 JSON（多写了逗号、少了引号、类型不匹配）
> { bad json
> // → 400 {"message":"请求体不是合法的 JSON，或字段类型不匹配"}
>
> // ② 路径变量类型不对
> GET /api/test-sessions/abc/result
> // → 400 {"message":"参数「sessionId」格式不正确，期望 Long，实际是：abc"}
> ```
>
> 这两类如果不单独处理，会掉进兜底分支返回 **500**——把"调用方写错了"
> 误报成"服务端挂了"，排查方向会整个跑偏。详见 `GlobalExceptionHandler`。

### 时间格式

所有时间字段是 ISO-8601 UTC 格式：`2026-09-14T12:34:56.789Z`。
前端展示时记得转成本地时区。

---

## `GET /api/health`

健康检查。**会真的去数据库查一次**，所以能同时验证"应用起来了"和"数据库连得上"。

**200 OK**

```json
{
  "status": "UP",
  "timestamp": "2026-09-14T12:34:56.789Z",
  "database": "UP",
  "questionCount": 20
}
```

**503 Service Unavailable**（数据库不通时）

```json
{
  "status": "DOWN",
  "timestamp": "2026-09-14T12:34:56.789Z",
  "database": "DOWN",
  "error": "Connection refused"
}
```

---

## 认证

> **登录是可选的。** 不登录也能做完整测试，只是记录不会保存。
> 只有 `GET /api/me/**` 需要登录。

### `POST /api/auth/register`

```json
{ "username": "alice", "password": "password123" }
```

用户名 3~50 位，只能含字母、数字、`_`、`-`；密码 8~72 位。

**201 Created**

```json
{ "id": 1, "username": "alice", "createdAt": "2026-09-14T13:47:46.089Z" }
```

| 错误 | 状态码 | 说明 |
|---|---|---|
| 用户名已被占用 | `409` | `用户名「alice」已被占用，换一个试试` |
| 格式不合法 | `400` | 见 `fieldErrors` |

> **响应里没有 `passwordHash`**——后端返回的是 `UserResponse` DTO 而不是实体。
> 这条边界在后端就守住了，不依赖前端"记得别显示"。

> **注册不会自动登录**，需要再调一次 `/login`。这样两个接口职责清晰。

### `POST /api/auth/login`

```json
{ "username": "alice", "password": "password123" }
```

**200 OK** —— 同时下发 `JSESSIONID`（HttpOnly）和 `XSRF-TOKEN` 两个 Cookie

```json
{ "id": 1, "username": "alice", "createdAt": "..." }
```

**401 Unauthorized**

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "用户名或密码错误"
}
```

> ⚠️ **「用户不存在」和「密码错误」返回完全相同的消息**，
> 而且服务端会让两条路径的耗时基本一致（用户不存在时执行一次假密码比对）。
> 否则攻击者能靠错误消息或响应时间**枚举出系统里有哪些用户名**。

### 速率限制

登录和注册都有限流，防止暴力破解和批量刷账号：

| 维度 | 阈值 | 窗口 | 拦什么 |
|---|---|---|---|
| 按**用户名** | 5 次失败 | 15 分钟 | 针对某个账号的密码爆破 |
| 按 **IP** | 20 次失败 | 15 分钟 | 一个 IP 扫大量账号（撞库） |
| 按 **IP**（注册） | 10 次尝试 | 1 小时 | 批量刷账号 |

**两套登录限流同时生效，任一套超限就拦。** 只用一种都有明显漏洞——
只按 IP 的话换个代理就能继续打同一账号；只按用户名的话一个 IP 能扫遍所有账号。

几个行为细节：

- **登录成功会清除该账号的失败计数**，打错几次后想起来正确密码不受影响
- **只清用户名那套，不清 IP 那套**——否则攻击者用一个自己的账号就能反复重置 IP 计数
- **被限流后即使密码正确也返回 429**。这是刻意的：限流要"阻断"而不是"减速"。
  代价是存在故意打错把真实用户锁在门外的可能，但窗口只有 15 分钟且会自动恢复
- **在校验密码之前就检查限流**——否则攻击者即使被拦，也已经让服务端跑了 BCrypt
  （每次约 100ms），等于免费拿到一个资源耗尽的手段

**429 响应示例**

```json
{
  "timestamp": "2026-09-14T13:47:45.536Z",
  "status": 429,
  "error": "Too Many Requests",
  "message": "该账号的登录尝试过于频繁，请 847 秒后再试",
  "path": "/api/auth/login",
  "fieldErrors": []
}
```

响应头里带标准的 `Retry-After`，客户端可以据此显示倒计时：

```
Retry-After: 847
```

> **⚠️ 计数器存在单机内存里。** 多实例部署时每个实例各算各的，
> 实际允许次数会翻倍。那时把存储换成 Redis 即可
> （用 `ZADD`/`ZREMRANGEBYSCORE` 实现滑动窗口），
> `LoginRateLimiter` 的接口不用改。
>
> 阈值定义在 `LoginRateLimiter` 的常量里，需要调整改那里。

### ⚠️ 会话访问令牌（会话相关端点必读）

操作**某个具体会话**的四个端点都需要在 `X-Session-Token` 请求头里带上令牌：

```
POST /api/test-sessions/{id}/answers
POST /api/test-sessions/{id}/submit
GET  /api/test-sessions/{id}/result
POST /api/test-sessions/{id}/ai-report
```

令牌在**创建会话时返回一次**，之后拿不到第二次：

```json
{ "sessionId": 26, "accessToken": "b3993532-1350-4d6d-b600-1d5c7a030538", "status": "IN_PROGRESS" }
```

**两种放行方式（满足其一即可）**：

| 方式 | 何时用 | 怎么带 |
|---|---|---|
| **令牌匹配** | 匿名测试 | `X-Session-Token: <uuid>` |
| **是登录用户本人的会话** | 从历史记录点进来 | 不用带，凭 Cookie 里的会话身份 |

#### 为什么需要它

`sessionId` 是数据库自增的**连续整数**，从 1 数到 N 就能遍历全站。
如果只用它判断所有权，会有一个严重的越权漏洞（IDOR，OWASP A01）——
修复前实测确认过两条攻击路径：

```
① 匿名 GET  /api/test-sessions/24/result   → 200，读到了别人的画像
② 匿名 POST /api/test-sessions/22/answers  → 200，写进了别人未提交的会话
```

令牌是 128 位随机 UUID，猜不到。**知道 id 不重要，拿到令牌才算数。**

#### 拒绝时返回 404 而不是 403

这是刻意的。403 等于告诉攻击者「这个会话存在，只是你没权限」——
那依然能被用来枚举出哪些 id 是有效的。404 让「不存在」和「没权限」
看起来完全一样。

同理，**令牌格式非法也返回 404 而不是 400**：如果格式错返回 400、
格式对但不存在返回 404，两者的差异本身就是信息泄露。

#### curl 示例

```bash
JAR=/tmp/cookies.txt
curl -s -c $JAR -b $JAR http://localhost:8080/api/auth/me          # 拿 CSRF 令牌
CSRF=$(grep XSRF-TOKEN $JAR | awk '{print $NF}')

# 建会话，同时拿到 accessToken
RESP=$(curl -s -X POST http://localhost:8080/api/test-sessions \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" -c $JAR -b $JAR)
SID=$(echo "$RESP" | jq -r .sessionId)
TOKEN=$(echo "$RESP" | jq -r .accessToken)

# 后续操作都带上令牌
curl -X POST "http://localhost:8080/api/test-sessions/$SID/answers" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -H "X-Session-Token: $TOKEN" \
  -c $JAR -b $JAR \
  -d '{"answers":[{"questionId":1,"score":5}]}'
```

> **令牌不会出现在历史记录等任何列表接口里。** 那些场景靠登录身份鉴权，
> 多返回一个凭证只会增加泄露面（被浏览器缓存、被日志记录、被前端存到 localStorage）。

### `POST /api/auth/logout`

无请求体。**204 No Content**，服务端销毁会话。

### `GET /api/auth/me`

**未登录：200 + 空响应体**（不是 401，见下方说明）

**已登录：200**

```json
{ "id": 1, "username": "alice", "createdAt": "..." }
```

> **为什么未登录返回 200 而不是 401？** 前端启动时会调它判断登录状态，
> "没登录"是完全正常的情况。返回 401 会让前端为一个正常场景写 try/catch。
> 返回 200 + null，一句 `if (user)` 就处理完了。
>
> 真正需要保护的 `/api/me/**` 未登录仍返回 401——那才是错误场景。

---

## `GET /api/me/test-sessions` 🔒

当前登录用户的测试历史，最近的在前。**最多返回 100 条。**

需要登录，未登录返回 401。

**200 OK**

```json
[
  {
    "sessionId": 24,
    "createdAt": "2026-09-14T13:49:12.000Z",
    "submittedAt": "2026-09-14T13:49:53.000Z",
    "status": "SUBMITTED",
    "dimensions": [
      { "key": "OPENNESS", "name": "开放性", "score": 50.00, "levelLabel": "中等" }
    ]
  }
]
```

| 字段 | 说明 |
|---|---|
| `status` | 可能是 `IN_PROGRESS`——用户答到一半就关了页面 |
| `dimensions` | 未提交时是**空数组**（还没计分），前端要显示成"未完成" |

> **为什么单独建一个 DTO 而不是复用结果页的？** 结果页只展示一次测试，
> 可以带几百字的解读文案；历史列表要展示几十次，每条都带上会让响应体膨胀几十倍。

> **路径为什么是 `/api/me/...` 而不是 `/api/users/{id}/...`？**
> 后者有个经典漏洞：服务端如果不校验"路径里的 id 是不是当前用户"，
> 改一下 URL 就能看别人的数据（IDOR）。用 `/me` 从 URL 里彻底消除了这个参数，
> 也就消除了整类漏洞。

---

## `GET /api/questions`

获取题库和量表选项。

> **`GET` 请求没有副作用**，调多少次结果都一样，可以放心缓存。

**200 OK**

```json
{
  "options": [
    { "value": 1, "label": "非常不同意" },
    { "value": 2, "label": "比较不同意" },
    { "value": 3, "label": "说不好" },
    { "value": 4, "label": "比较同意" },
    { "value": 5, "label": "非常同意" }
  ],
  "questions": [
    {
      "id": 1,
      "content": "我喜欢尝试没吃过的菜和没走过的路线。",
      "dimension": "OPENNESS",
      "dimensionLabel": "开放性",
      "sortOrder": 1
    }
  ]
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `options` | 李克特量表选项。**前端不要硬编码这些中文**，直接渲染后端返回的 |
| `questions[].dimension` | 英文标识：`OPENNESS` / `EXTRAVERSION` / `CONSCIENTIOUSNESS` / `AGREEABLENESS` / `EMOTIONAL_STABILITY` |
| `questions[].sortOrder` | 展示顺序。维度是交错排列的，按这个字段排就行 |

> ⚠️ **响应里故意没有 `reverseScored` 字段。**
> 如果暴露了"哪些题是反向计分"，用户打开浏览器 F12 就能看到，
> 然后有针对性地答题来操纵结果。

---

## `POST /api/test-sessions`

创建一次测试会话。

**请求体可选** —— V0.1 不做登录，直接空 POST 就行。

```json
{}
```

**201 Created**

```json
{
  "sessionId": 1,
  "status": "IN_PROGRESS",
  "createdAt": "2026-09-14T12:34:56.789Z"
}
```

后续所有请求都要带上这个 `sessionId`。

---

## `POST /api/test-sessions/{id}/answers`

保存作答。**支持批量提交**：

- 传 1 道题 → 用户每答一题就自动保存
- 传全部 20 道题 → 前端攒着最后一起交

**已经答过的题会更新，不会重复插入**，所以可以放心重复调用。
这实现了"中断后继续答题"。

**请求体**

```json
{
  "answers": [
    { "questionId": 1, "score": 5 },
    { "questionId": 2, "score": 4 }
  ]
}
```

| 字段 | 类型 | 约束 |
|---|---|---|
| `answers` | 数组 | 不能为空 |
| `answers[].questionId` | Long | 必填，必须存在 |
| `answers[].score` | Integer | 必填，1~5 |

**200 OK**

```json
{
  "sessionId": 1,
  "savedCount": 2
}
```

**400 Bad Request** —— 分值越界

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "请求参数不合法",
  "path": "/api/test-sessions/1/answers",
  "fieldErrors": [
    { "field": "answers[0].score", "message": "score 最大为 5" }
  ]
}
```

**400 Bad Request** —— 题目不存在

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "题目不存在：id=999",
  "path": "/api/test-sessions/1/answers",
  "fieldErrors": []
}
```

**409 Conflict** —— 会话已提交，不能再改答案

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 409,
  "error": "Conflict",
  "message": "会话 1 已提交，不能再修改答案",
  "path": "/api/test-sessions/1/answers",
  "fieldErrors": []
}
```

---

## `POST /api/test-sessions/{id}/submit`

**提交并计分 —— 整个 MVP 的闭环收口点。**

触发计分，把 5 个维度的分数存进数据库，并直接返回结果（省一次往返）。

**无需请求体。**

**200 OK**

```json
{
  "sessionId": 1,
  "status": "SUBMITTED",
  "createdAt": "2026-09-14T12:34:56.789Z",
  "submittedAt": "2026-09-14T12:40:12.345Z",
  "dimensions": [
    {
      "key": "OPENNESS",
      "name": "开放性",
      "score": 100.00,
      "rawScore": -1,
      "itemCount": -1,
      "level": "HIGH",
      "levelLabel": "偏高",
      "description": "你对新事物、新观念有比较强的好奇心……"
    }
  ],
  "disclaimer": "本结果是一份自我探索性质的参考……"
}
```

**⚠️ 这个接口在服务端是幂等的，但会返回 409 而不是重复计分。**

已提交过的会话再次提交 → **409 Conflict**。这是刻意的：
重复提交会覆盖已有的画像，且掩盖"前端重复点击"这类 bug。

**400 Bad Request** —— 没答完就提交

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "还有 3 道题没有作答，无法提交",
  "path": "/api/test-sessions/1/submit",
  "fieldErrors": []
}
```

### 结果字段说明

| 字段 | 说明 |
|---|---|
| `dimensions[].key` | 英文标识。**前端用它做 Map 的 key、图表分组，不要拿去展示** |
| `dimensions[].name` | 中文名，给用户看 |
| `dimensions[].score` | 归一化分数，`0.00` ~ `100.00` |
| `dimensions[].rawScore` | ⚠️ **V0.1 固定返回 `-1`**（见下方说明） |
| `dimensions[].itemCount` | ⚠️ **V0.1 固定返回 `-1`** |
| `dimensions[].level` | `LOW` / `MEDIUM` / `HIGH` |
| `dimensions[].levelLabel` | 中文档位：偏低 / 中等 / 偏高 |
| `dimensions[].description` | 该档位的中文解读文案 |
| `disclaimer` | 免责声明。**请务必在结果页展示** |

> **关于 `rawScore` / `itemCount` 返回 -1：**
> `personality_profiles` 表只存了归一化分数，没存原始分。要给出真实值需要
> 多查一次 `answers` 表并重新计分。V0.1 判断不值得为此多一次查询，
> 但**字段保留在契约里**，V0.2 填上真值时前端不用改。

---

## `GET /api/test-sessions/{id}/result`

查询已生成的画像。内容与 `submit` 的返回一致。

**这是 `GET`，没有副作用** —— 不会触发计分。会话还没提交过就返回 404。

**404 Not Found** —— 会话不存在，或还没提交

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 404,
  "error": "Not Found",
  "message": "会话 1 还没有结果，请先调用 submit 完成计分",
  "path": "/api/test-sessions/1/result",
  "fieldErrors": []
}
```

---

## `POST /api/test-sessions/{id}/ai-report`

生成 AI 个性化解读。把 5 个维度的画像交给大模型，写一段针对**这组维度组合**的分析。

用 `POST` 而不是 `GET`，因为它**不幂等**：大模型输出有随机性，
而且会写 `ai_reports` 表。`GET` 不该有副作用。

**查询参数**

| 参数 | 默认 | 说明 |
|---|---|---|
| `regenerate` | `false` | 为 `false` 时，已有报告直接返回旧结果，**不调用大模型**；为 `true` 时强制重新生成 |

> **为什么默认要缓存？** 用户误点两次不该白白消耗两次 token，
> 而且两次生成的文本不一样反而让人困惑。前端对应「重新生成」按钮。

**200 OK**

```json
{
  "sessionId": 1,
  "content": "你身上有一种挺少见的组合：脑子活、爱往外跑，情绪又稳……",
  "provider": "deepseek:deepseek-chat",
  "generatedAt": "2026-09-14T13:28:51.338791100Z",
  "cached": false
}
```

| 字段 | 说明 |
|---|---|
| `provider` | 由哪个模型生成。未启用 AI 时是 `stub` |
| `cached` | `true` 表示这是**之前生成过的**，本次直接复用、没调大模型 |
| `generatedAt` | 报告的**首次**生成时间。缓存命中时它是过去的时间，不是本次请求的时间 |

**501 Not Implemented** —— 未配置 API Key 时（即 `AI_ENABLED` 不是 `true`）

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 501,
  "error": "Not Implemented",
  "message": "AI 报告功能将在 V0.2 实现。当前为占位实现（stub），画像数据已准备就绪，共 5 个维度可供分析。",
  "path": "/api/test-sessions/1/ai-report",
  "fieldErrors": []
}
```

**502 Bad Gateway** —— 上游大模型服务超时或报错

```json
{
  "timestamp": "2026-09-14T12:34:56.789Z",
  "status": 502,
  "error": "Bad Gateway",
  "message": "连接 AI 服务超时或失败，请稍后重试",
  "path": "/api/test-sessions/1/ai-report",
  "fieldErrors": []
}
```

> 502 的语义是「我这个服务是好的，但我依赖的上游出问题了」。
> 这比笼统的 500 精确得多——调用方看到 502 就知道该去查上游，而不是翻自己的代码。

### 提示词里有什么约束

生成的内容由 `AiPromptBuilder` 控制，它是一段**可单元测试的纯逻辑**（不联网、不花钱）。
系统提示词里写死了四条安全边界，来自计划书第九节的要求：

1. 不做心理疾病诊断
2. 不声称结果具有临床意义
3. 不把人格描述成固定不变的人生结论
4. 不提及具体分数、题目数量

外加写作要求：第二人称、串联维度组合而非逐条念分数、每个维度既讲优势也讲代价、
结尾给 2~3 条**具体**建议（明令禁止「多与人交流」这类空话）、限 400~600 字。

---

## 完整调用示例

```bash
# 1. 创建会话
curl -X POST http://localhost:8080/api/test-sessions \
  -H "Content-Type: application/json" -d '{}'
# → {"sessionId":1,...}

# 2. 提交答案（这里只示例 2 道，实际要 20 道）
curl -X POST http://localhost:8080/api/test-sessions/1/answers \
  -H "Content-Type: application/json" \
  -d '{"answers":[{"questionId":1,"score":5},{"questionId":2,"score":4}]}'

# 3. 提交计分
curl -X POST http://localhost:8080/api/test-sessions/1/submit

# 4. 查询结果
curl http://localhost:8080/api/test-sessions/1/result
```

完整的 20 题请求体见 `backend/api.http`。

---

## 跨域（CORS）

开发环境下，后端允许来自本机任意端口的跨域请求
（`http://localhost:*` 和 `http://127.0.0.1:*`）。

**上线前必须改掉** `config/CorsConfig.java` 里的 `allowedOriginPatterns`，
换成真实域名。配置成 `"*"` 且允许携带凭证等于开放 CSRF 攻击面。
