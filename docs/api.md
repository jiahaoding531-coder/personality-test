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
| `404` | 资源不存在 | 会话 ID 不存在、结果还没生成、**没带令牌访问别人的会话**（和"不存在"返回一样的消息，防止探测） |
| `409` | 状态冲突 | 重复提交已提交过的会话、用户名已被占用、**拿人格会话去调旅行的 submit** |
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

**查询参数**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `scale` | `PERSONALITY` \| `TRAVEL` | `PERSONALITY` | 取哪套题库 |

> ⚠️ **枚举按名字绑定，大小写敏感**：`?scale=travel` 会返回 400，必须写 `TRAVEL`。
>
> 默认值是 `PERSONALITY` 而不是"必填"，是为了让 V0.1 的调用方（老静态页、老测试）
> 一行都不用改——不传参数时拿到的仍然是那 20 道人格题。

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

---

# 旅行偏好测试（TravelMind）

链路：建会话 → 答 8 道题 → 提交算画像 → 带定位拿 Top 3 推荐。

> **为什么是独立的 `/api/travel` 前缀，而不是复用 `/api/test-sessions`？**
> 两者底层共用同一张 `test_sessions` 表和同一套会话机制，但**返回结构不同**：
> 人格侧是 5 维 + 档位 + 解读文案，旅行侧是 8 维的 key/name/score。
> 硬塞进一个端点会让响应变成"两套字段并存、各自一半是 null"的形状。
>
> **安全模型完全一样**：同样是匿名可做，同样靠 `X-Session-Token` 保护数据。
> 推荐结果和旅行画像都挂在 `session_id` 上，而会话 ID 是自增整数——
> 所以每个端点都会校验所有权，失败返回 **404 而不是 403**（403 会泄露"这个 ID 存在"）。

## `POST /api/travel/sessions`

创建一次旅行偏好测试会话。请求体为空，响应结构和 `POST /api/test-sessions` 完全一致。

**201 Created**

```json
{
  "sessionId": 28,
  "accessToken": "3f2b8c1e-...",
  "status": "IN_PROGRESS",
  "createdAt": "2026-09-15T03:09:45Z"
}
```

> `accessToken` 是匿名用户访问这个会话的唯一凭证，**只在这一次返回**。
> 后续请求要放在 `X-Session-Token` 头里。

---

## `POST /api/travel/sessions/{id}/answers`

保存作答。请求体与人格侧**完全一致**（`{ answers: [{ questionId, score }] }`），
所以复用了同一个 DTO。

⚠️ 旅行题号不能提交到人格会话（反之亦然）——后端按会话的 `scale` 过滤题库，
拿错量表的题号会返回 400「题目不存在」。

---

## `POST /api/travel/sessions/{id}/submit`

提交并计分，产出 8 维旅行画像。**和人格侧的 submit 一样是"计分 + 读取"两步合一。**

> **分数只有 0 / 25 / 50 / 75 / 100 五档**，因为每个维度只有 1 道题：
> 归一化公式 `(rawSum - itemCount) / (itemCount × 4) × 100` 在 `itemCount = 1` 时
> 化简成 `(score - 1) × 25`，五档等距。

**200 OK**

```json
{
  "sessionId": 28,
  "status": "SUBMITTED",
  "createdAt": "2026-09-15T03:09:45Z",
  "submittedAt": "2026-09-15T03:10:12Z",
  "scale": "TRAVEL",
  "dimensions": [
    { "key": "NATURE", "name": "自然风光", "score": 100.00 },
    { "key": "CULTURE", "name": "人文历史", "score": 75.00 },
    { "key": "FOOD", "name": "美食探索", "score": 50.00 },
    { "key": "PHOTOGRAPHY", "name": "摄影出片", "score": 100.00 },
    { "key": "HIDDEN_GEMS", "name": "小众独特", "score": 100.00 },
    { "key": "CROWD_TOLERANCE", "name": "人群耐受", "score": 0.00 },
    { "key": "WALKING", "name": "步行意愿", "score": 25.00 },
    { "key": "PLANNING", "name": "提前规划", "score": 50.00 }
  ]
}
```

> ⚠️ 维度只有 `key` / `name` / `score` 三个字段，**没有人格画像那套
> `level` / `levelLabel` / `description`**。理由见 `TravelProfileResponse` 的注释：
> 8 维 × 3 档 = 24 段文案，而五档分数本身已经够直白了。
>
> ⚠️ `PLANNING` 不参与地点排序——它描述的是"你怎么安排行程"，
> 不是"你想要什么样的地方"。这会影响将来的行程生成粒度。

**错误**

| 码 | 场景 |
|---|---|
| `404` | 会话不存在，或没有权限（两者返回一样的消息） |
| `409` | 已经提交过的会话 / 拿人格会话调这个端点 |
| `400` | 还有题目没作答 |

---

## `GET /api/travel/sessions/{id}/profile`

查询已生成的旅行画像。响应和 submit 完全一致。

> `GET` 必须幂等无副作用：只读，**不触发计分**。没提交过返回 404。

---

## `POST /api/travel/sessions/{id}/interpret`

把用户的一句自然语言解析成可验证的状态操作。AI **不直接返回一套新的 Chip**；前端必须把
`operations` 依次交给同一个 reducer，和快捷标签、Chip 删除共用一套状态变更规则。

**请求体**

```json
{ "text": "最好安静一点，一个小时以内" }
```

**200 OK（节选）**

```json
{
  "summary": "想找安静一点、1 小时内能完成的地方",
  "usable": true,
  "operations": [
    { "op": "ADD_PREFERENCE", "value": "QUIET", "values": null, "key": null },
    { "op": "SET_CONSTRAINT", "value": 60, "values": null, "key": "durationMinutes" }
  ],
  "unrecognized": []
}
```

支持的操作：

| `op` | 参数 | 含义 |
|---|---|---|
| `ADD_INTENT` / `REMOVE_INTENT` | `value` | 增加或删除一个核心活动；目前核心活动是 `HUNGRY`、`WANT_WALK` |
| `REPLACE_INTENTS` | `values` | 用户明确改变计划时，按顺序替换核心活动 |
| `ADD_PREFERENCE` / `REMOVE_PREFERENCE` | `value` | 增加或删除偏好，例如 `QUIET` |
| `SET_CONSTRAINT` | `key` + `value` | 更新同类限制；`durationMinutes`、`maxDistanceMeters`、`budgetMax` 各自独立替换 |
| `MERGE_BIASES` | `values` | 合并旅行维度倾向 |
| `CLEAR_TRAVEL_INTENT` | — | 清空本次意图、偏好和限制；**不清 GPS、城市、天气、当前时间或长期画像** |

无法确定用户是否改变主意时，解析器优先保留已有条件，不生成破坏性的删除或替换操作。
响应仍保留 `states`、`biases`、`remainingMinutes` 等兼容字段；新前端应以 `operations` 为准。

---

## `POST /api/travel/sessions/{id}/recommendations`

给这次会话推荐 Top 3 地点。

> **为什么是 POST 而不是 GET？** 这个操作**有副作用**——每次调用都会在
> `recommendations` 表里新写一批记录。用户反馈要挂到推荐行上，
> 而"接受率是否随使用次数提升"这个核心指标也需要历史数据。
> GET 按 HTTP 语义必须是幂等可缓存的，这里显然不满足。

**请求体**

```json
{
  "latitude": 30.2420,
  "longitude": 120.1400,
  "remainingMinutes": 300,
  "maxDistanceKm": 10
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `latitude` | number | ✅ | — | 纬度，-90 ~ 90 |
| `longitude` | number | ✅ | — | 经度，-180 ~ 180 |
| `remainingMinutes` | int | | `240` | 今天还剩多少分钟可玩。15 ~ 1440 |
| `maxDistanceKm` | number | | `10` | 候选地点最大半径。0.5 ~ 50 |
| `excludeSeen` | boolean | | `false` | 是否排除本会话已推荐过、且没被点过 👍 的地点。**"换一批"必须传 `true`** |
| `maxTicketPrice` | int | | 不限 | 门票价格上限（元）。**0 表示"只能去免费的"，和不传是两回事** |
| `states` | string[] | | `[]` | 此刻的状态，见下 |
| `autoInfer` | boolean | | `false` | **让系统自己推断处境**，而不是等用户填。见下 |

### 自动推断（`autoInfer`）

为 `true` 时，服务端会按当前时间推断处境，并把结果回传在 `appliedContext` 里。

**现在只推一条规则**：饭点（11:30–13:00 / 17:30–19:30）推断「想吃饭」。

> ⚠️ **别的都推不了，因为没数据。** 理想情况下这里应该结合定位、天气、步数、移动状态，
> 但那些一个都没有——天气没接、真实 POI 没有、手机的移动状态要 App 才拿得到。
> **宁可少推，也绝不装作能推**：猜错了比不猜更糟，因为用户会莫名其妙。
>
> 特别是"天黑了推室内"这种看起来很自然的规则——地点的类别是
> `NATURE` / `MUSEUM` / `FOOD`，**根本没有"室内/户外"这个字段**，硬推只能是瞎猜。

**推断必须可见、可撤销。** 每个响应的 `appliedContext` 都会说明这次按什么算的：

```json
"appliedContext": {
  "now": "12:30:00",
  "remainingMinutes": 240,
  "maxDistanceKm": 10.0,
  "maxTicketPrice": null,
  "states": [{ "key": "HUNGRY", "label": "想吃饭了" }],
  "inferredStates": [{ "key": "HUNGRY", "label": "想吃饭了" }]
}
```

| 字段 | 说明 |
|---|---|
| `states` | 最终生效的状态（用户说的 + 系统推断的） |
| `inferredStates` | **其中哪些是系统自己猜的**。前端要把这几个单独标出来，并允许一键否定 |

**用户说的优先，系统只在他没说的时候补**：饭点推出来「想吃饭」、
而用户又明确点了「我有点累了」时，两个状态会同时生效，而不是让系统用猜的盖掉用户的话。

> **时间走的是注入的 `Clock`**（见 `ClockConfig`），不是 `LocalTime.now()`。
> 否则这个功能没法测——同一条测试中午跑绿、下午跑红——也没法演示。

### 此刻的状态（`states`）

**长期偏好和当前状态是两回事**（计划书第七节）。用户说"我累了"不代表他从此不喜欢走路，
所以状态**不会被存进画像**，只作用于这一次推荐。

| 取值 | 含义 | 对推荐的影响 |
|---|---|---|
| `TIRED` | 有点累了 | **惩罚"费腿"属性**——越费腿的地方折扣越大（最高压到约 24%） |
| `HUNGRY` | 想吃饭了 | 奖励"美食"属性，附近合适的馆子会冒到前面 |
| `WANT_WALK` | 想散散步 | 奖励"费腿"属性，和 `TIRED` 正好相反 |

可以同时传多个：`"states": ["TIRED", "WANT_WALK"]` 两者会互相抵消。

> ⚠️ **状态作用于「地点属性」，不是「用户偏好」**——这是个踩过的坑。
> 最初把"我累了"实现成"把步行意愿从 100 降到 60"，结果排序完全没变：
> 兴趣分是归一化的加权平均，把某个维度的权重调小，分子分母同时缩小，比值不变。
>
> 而且语义上就错了：**"我累了"不是"我没那么在乎走路了"，而是"费腿的地方要变差"。**
> 前者是关于「你」的，后者是关于「地点」的。
>
> 现在是一层乘性系数（和距离系数、质量系数并列），**恒 ≤ 1**——
> 这条不变量必须保住，否则 score 会超过 1、撞上数据库的 `CHECK` 约束。

> **为什么定位必填？** 引擎在没有定位时也能排序（距离因素失效），
> 但那排的是"兴趣匹配 + 质量"，和"你附近此刻最值得去哪"是两回事。
> 与其返回一个看起来正常、实际没考虑距离的结果，不如直接要求给定位。
>
> 剩余时长则相反，它有个合理的默认值（4 小时），而且用户经常说不清。

**200 OK**

```json
{
  "sessionId": 28,
  "batchNo": 1,
  "generatedAt": "2026-09-15T03:10:30Z",
  "places": [
    {
      "rank": 1,
      "recommendationId": 37,
      "placeId": 1,
      "name": "西湖·苏堤",
      "category": "NATURE",
      "description": "西湖最经典的一段，两侧都是水面和柳树",
      "scorePercent": 62,
      "scoreBreakdown": {
        "interest": 0.628,
        "distance": 1.0,
        "quality": 0.988,
        "state": 1.0,
        "finalScore": 0.620
      },
      "distanceKm": 0.00,
      "ticketPrice": 0,
      "suggestedMinutes": 120,
      "openFrom": null,
      "openTo": null,
      "reasons": [
        { "dimensionKey": "NATURE", "dimensionLabel": "自然风光", "userPreference": 100, "placeValue": 95 },
        { "dimensionKey": "PHOTOGRAPHY", "dimensionLabel": "摄影出片", "userPreference": 100, "placeValue": 90 },
        { "dimensionKey": "CULTURE", "dimensionLabel": "人文历史", "userPreference": 75, "placeValue": 80 }
      ]
    }
  ]
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `batchNo` | 这是该会话的第几批推荐，从 1 开始 |
| `recommendationId` | 这条推荐记录的 ID。**点 👍/👎 时要连它一起带上** |
| `scorePercent` | 0~100 的整数。四个因子相乘，每个都 ≤ 1 |
| `scoreBreakdown` | **这个分数是怎么来的**——回答「为什么是它」。见下 |
| `distanceKm` | 距离用户的公里数 |
| `openFrom` / `openTo` | `"HH:mm"`。**两个都是 `null` 表示全天开放**（公园、街区） |
| `reasons` | 推荐依据，按贡献从大到小，最多 3 条。**是算出来的，不是 AI 编的** |

### 为什么是它（`scoreBreakdown`）

```
兴趣匹配 57%  ×  距离 76%  ×  质量 97%  ×  此刻状态 68%  =  28.3%
```

**只给一个百分数是回答不了「为什么是它」的。** 同样是 62%，可能是
「兴趣很合但有点远」，也可能是「兴趣一般但就在楼下」——对用户的决策含义完全相反。

真实例子：西湖·苏堤的兴趣（64%）、距离（100%）、质量（99%）**三项都比楼外楼高**，
却排在第二——因为用户说了"我累了"，而它要走 70 分的路，**状态分只有 44%**。
没有拆解的话，用户只会觉得这个排序坏了。

> **四项是相乘的，不是相加**——这一点要在前端画出来（乘号不能省）。
> 乘法意味着任何一项掉到 0 整个结果就是 0，这正是"太远的地方不该被推荐"
> 的算法依据。加权求和会让"兴趣极高"补偿掉"距离极远"，推出一个你根本去不了的地方。
>
> 没定位时 `distance` 恒为 1.0，没状态时 `state` 恒为 1.0（乘法单位元）。
> 引擎里有一条不变量测试盯着 **四因子相乘必须精确等于 `finalScore`**——
> 拆解和实际打分对不上，比不给拆解更糟。

> ⚠️ **`places` 可能是空数组。** 定位附近 10 公里内没有"正在营业 + 停留时长装得进
> 剩余时间"的地点时，这是**正常结果不是错误**。前端要专门处理这个情况，
> 而不是当成请求失败。
>
> ⚠️ **演示数据只有杭州的 59 个景点。** 用真实定位（不在杭州）几乎必然返回空列表。
> 这不是 bug，是"模拟数据只有一个城市"的必然结果——真实 POI 接入是计划书的 Phase 3。

**错误**

| 码 | 场景 |
|---|---|
| `400` | 定位没传、经纬度越界、`remainingMinutes` 超范围 |
| `404` | 会话不存在 / 没权限 / **还没提交（没有画像就没法推荐）** |

> **推荐记录是 append-only 的**：重新推荐是新开一批（`batchNo + 1`），
> **不会删掉旧的那批**。因为 `recommendation_feedback` 外键挂在推荐行上，
> 删推荐会级联删掉用户反馈——而反馈是整个项目里最该攒下来的数据。

---

## `POST /api/travel/sessions/{id}/recommendations/{recommendationId}/feedback`

对某一条推荐点 👍 / 👎。

> **为什么要传 `recommendationId` 而不是 `placeId`**：反馈挂在"某一次推荐的某一条"上，
> 不是挂在地点上——同一个地点在不同批次里是不同的推荐，反馈要能区分开。

**请求体**

```json
{ "reaction": "DISLIKE" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `reaction` | `LIKE` \| `DISLIKE` | ✅ | 喜欢 / 不喜欢 |

**200 OK**

```json
{
  "recommendationId": 37,
  "reaction": "DISLIKE",
  "adjustments": [
    { "key": "NATURE", "name": "自然风光", "questionnaireScore": 50, "effectiveScore": 40 }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `adjustments` | 这次反馈让画像发生了哪些变化。**没被影响到的维度不出现** |
| `questionnaireScore` | 问卷算出来的原始分 |
| `effectiveScore` | 叠加反馈修正后、推荐实际使用的分数 |

> **为什么要返回调整结果**：画像是"下次推荐才用到"的东西，用户点完当场看不到任何变化，
> 很容易以为按钮是坏的。把调整前后的分数给出来，前端就能显示成「自然风光 50 → 40」。

**错误**

| 码 | 场景 |
|---|---|
| `400` | `reaction` 不是 `LIKE` / `DISLIKE` |
| `404` | 推荐不存在 / 不属于这个会话 / 没带会话令牌 |

> 重复提交同一条推荐是**合法**的：用户改主意（👎 → 👍）走的是 UPDATE，
> 不会留下两条记录，否则"接受率"这类统计会被重复数据污染。

### 反馈的作用范围：跨会话累积，但"换一批"不跨

| | 作用范围 | 为什么 |
|---|---|---|
| **👍/👎 的修正** | **该用户的全部旅行会话** | 才叫"用得越多越准"。只算当前会话的话，用户点一次"重新测一次"就把积累全清零了 |
| **"换一批"的排除** | **仅当前会话** | 它的语义是"这批我看过了，给我新的"。跨会话排除的话，隔天来点一次会把历史上所有看过的地方全排掉 |

⚠️ **匿名用户没有跨会话记忆**——没有稳定的用户身份，"上次"就无从谈起。
匿名会话的反馈只在那一个会话内生效。这和"历史记录只有登录用户才有"是同一个边界。

修正最多取**最近 20 条**反馈。为什么不全部算：修正量有 ±40 的封顶，
一旦撞顶就再也动不了了——用户改了口味，系统却因为几百条旧反馈而锁死。

### 反馈是怎么影响后续推荐的

**问卷画像永远不被改动。** 每次推荐时，服务端拿问卷画像叠加这一会话收到的全部反馈，
实时算出「有效画像」喂给引擎。这样：

- 问卷结果始终可追溯——"用户答了 8 道题得出的画像"和"被反馈修正过的画像"不会混成一份
- 用户改主意天然正确——换个符号重算就行，不需要"撤销"逻辑

**规则**：每条 👍/👎 让某个维度 **±10 分**，同方向累计封顶 **±40 分**，
最终夹在 0~100 之间。选 ±10 是因为旅行画像只能取 0/25/50/75/100 五档（一档 25），
±10 约半档——一次能看出方向，但不会一次翻盘；封顶则保证"连点几次"不会把整份问卷覆盖掉。

> ⚠️ **归因是启发式的，有已知局限。**
> 一条反馈针对的是**一个地点**，但地点有 7 个属性维度。系统只能猜：
> "这次推荐里贡献最大的维度（就是 `reasons[0]`）是哪个，就调哪个"。
>
> 后果：如果一个地方因为"人太多"被否掉，但它的主导维度是「自然风光」，
> 那用户对自然风光的偏好会被误降。要真正准确，得让用户说出原因
> （比如长按 👎 弹出维度选择），那是更重的交互，不在当前阶段。

---

## `POST /api/travel/sessions/{id}/feedback-events`

针对当前推荐（或当前会话）说一句自然语言。服务端会先校验会话令牌，
再校验 `recommendationId` 必须属于这个会话，最后在事务外调用模型解析。
因此推荐归属错误、权限错误和非法令牌都统一返回 `404`，不会泄露资源是否存在。

成功解析后，服务端会在同一个短写事务中保存 V13 反馈事件，并同步可选的 👍/👎；
任何解析、鉴权或上游失败都不会留下半条事件。事件只保存原话和经过白名单校验的结构化结果，
不保存 API key、Prompt 或模型原始响应。

请求需要会话令牌和 CSRF 令牌。只有当服务端没有配置可用的 AI 凭据时，才需要额外提供访客自己的 AI 凭据请求头；服务端已配置凭据时可省略这两个请求头：

```http
X-Session-Token: <创建会话时返回的 accessToken>
X-AI-Provider: DEEPSEEK
X-AI-Key: <访客自己的 key>
```

`X-AI-Key` 只在本次请求中使用，不会落库、缓存或写入日志；`X-AI-Provider` 只能是
服务端白名单中的厂商名，不能传 `base-url`。未配置服务端 AI 且未提供可用 key 时返回 `501`。

**请求体**

```json
{
  "text": "这家太远了，想找安静一点的",
  "recommendationId": 37,
  "query": {
    "intents": ["HUNGRY"],
    "preferences": ["QUIET"],
    "durationMinutes": 60,
    "maxDistanceMeters": 5000,
    "budgetMax": 100,
    "biases": {}
  },
  "latitude": 30.25,
  "longitude": 120.16
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | ✅ | 1~200 个字符的原话 |
| `recommendationId` | long | | 针对某条推荐；省略表示只调整会话条件 |
| `query` | object | ✅ | 当前前端查询快照，不会被服务端直接替换 |
| `latitude` / `longitude` | number | ✅ | 当前定位，分别限制在 -90~90 / -180~180 |

**200 OK**

```json
{
  "eventId": 55,
  "kind": "MIXED",
  "reaction": "LIKE",
  "operations": [
    { "op": "SET_CONSTRAINT", "key": "maxDistanceMeters", "value": 3000 }
  ],
  "unrecognized": [],
  "summary": "我把范围收近一些",
  "shouldRecommend": true,
  "excludeSeen": false
}
```

`operations` 是受控协议，不是可直接执行的命令。前端必须把它交给现有 travel query
reducer 做运行时校验和合并，不能自行替换整份 `query`。`excludeSeen=true` 表示用户要求
“换一批”，即使没有其它 operation 也应追加推荐。

| 状态码 | 场景 |
|---|---|
| `400` | 文本为空/超过 200 字、请求体超过 8192 字节、查询快照或坐标不合法、AI 厂商名不在白名单 |
| `415` | 请求体不是 `application/json`（兼容 `application/*+json`） |
| `404` | 会话不存在、未带/非法/越权令牌、推荐记录不属于该会话 |
| `501` | 没有可用的服务端或访客 AI 凭据 |
| `502` | 上游调用失败、返回坏 JSON 或无法解析的反馈；此时不会落反馈事件 |

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

---

## 阶段 5 第一批：明确行为与餐饮来源

所有新增写入继续要求 CSRF。匿名旅行会话携带 `X-Session-Token`；有归属的会话必须由其用户登录访问。客户端不能传入 `userId`。失败的身份及伪造来源统一返回 404。

### 记录明确行为

`POST /api/travel/sessions/{sessionId}/behavior-events`

餐饮导航示例（ID 为说明用占位，不代表实际可用来源）：

```json
{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "schemaVersion": 1,
  "targetType": "RESTAURANT",
  "provider": "AMAP",
  "targetId": "实际返回的poiId",
  "action": "NAVIGATION_REQUESTED",
  "occurredAt": "2026-10-09T04:00:00Z",
  "searchSessionId": "22222222-2222-4222-8222-222222222222",
  "resultSetId": "33333333-3333-4333-8333-333333333333"
}
```

- 首次 201：`{"eventId":"…","duplicate":false}`；相同内容重试 200、`duplicate:true`；同 ID 不同内容 409。
- 单请求最多 16 KiB，未知字段拒绝。首次发生时间允许过去 7 天至未来 5 分钟；接收时间由服务端 Clock 决定。
- `PLACE/LOCAL` 的单目标必须关联该会话的 `recommendationId` 和本地 `targetId`；`RESTAURANT/AMAP` 必须关联该会话真实结果的搜索 ID、结果页 ID 和 POI ID。
- `NEXT_BATCH_REQUESTED` 是列表动作，`targetId=null`；可含最多三项 `visibleSources`。空景点列表只关联授权会话，不捏造推荐批次。
- `EXCLUDE` 仅用于餐饮本次搜索排除。`HOTEL/ACTIVITY` 只预留枚举，当前请求拒绝。
- `REACTION_SET/CLEAR` 不允许通过通用端点上报，只能由景点反馈业务事务写入。
- 导航请求不表示到达；换批不表示不喜欢全部地点；排除不表示长期不喜欢。没有曝光、停留、到访记忆或自动画像学习。

### 餐饮分页来源

原餐饮搜索接口路径和参数保留，请求追加可选 UUID `searchSessionId`。新客户端首次从 `page=1` 开始；同位置、坐标系、半径和用户/会话的分页共用 ID。响应追加 UUID `resultSetId`，每次真实返回页独立，空页也有来源。

每张卡片保留自己的 `{searchSessionId,resultSetId,page}`。混合消费两页候选时不得统一改为最后页来源。

相同搜索 ID 改条件返回 409 `RESTAURANT_CONTEXT_CHANGED`；30 分钟过期、后端重启或 256 个上下文容量淘汰后，旧分页返回 410 `RESTAURANT_CONTEXT_EXPIRED`，用户可重新搜索。未满 7 天的历史来源仍可验证动作。旧客户端不传 ID 仍可搜索，但不保证跨页稳定。

### 景点反馈渐进升级

原 POST/DELETE 反馈接口追加两个可选头：`X-Behavior-Event-Id`、`X-Behavior-Occurred-At`，必须一起提供。事件与反馈业务同事务提交或回滚；已有事件重试不重放反馈，所以 SET→CLEAR→重试旧 SET 不会恢复点赞。

旧客户端无头时由服务端生成身份，不保证客户端重试幂等。无头且匿名的旧接口保留原令牌兼容规则；新头和登录请求严格检查归属。推荐评分与原偏好调整算法不变。

### 留存、隐私及失败边界

- 行为默认保留 90 天，结果快照 7 天。页删除仅级联其条目，不删除行为；事件保留必要 ID，来源清理后旧事件仍可幂等重试，新事件拒绝。90 天事件本身到期清理后不再承诺原 ID 幂等。
- 每日北京时间 03:20 分块清理，默认每块 500、最多十块；停机或大量积压可能延迟物理清理，并非精确到秒的删除 SLA。
- 配置前缀 `app.behavior-retention`：`event-days`（默认 90）、`snapshot-days`（默认 7，至少 7）、`batch-size`（默认 500，1–1000）、`cleanup-enabled`、`cleanup-cron`。
- 内部 `BehaviorRetentionService.deleteNewBehaviorDataForUser(userId)` 要求可信 `ROLE_INTERNAL_DATA_MAINTAINER`；只删除本人新增三表数据，不删除旧反馈/画像。没有公共删除 API/UI。正式上线前必须补齐用户自助删除及全账户删除政策。
- 新表不保存用户搜索中心、GPS 轨迹、Key、Token、模型原文；快照仅保留必要 POI 信息及目的地坐标。旧业务数据的留存范围不因此自动改变。
- 普通行为发送为内存单飞队列：含在途最多 100 条，额外重试 3 次（1/2/4 秒），单次 10 秒超时，动作 120 秒过期。网络/5xx 重试复用事件身份，4xx 停止。切账号/会话销毁队列；刷新或关闭页面可丢失未确认事件，不支持离线可靠投递。
- 普通记录失败不阻断导航、换批、排除。反馈请求十秒超时后提供同身份手动重试，确认前阻止该目标新按钮反馈，其他操作不受阻断。新自然语言反馈由下节服务端版本控制，不能复用旧按钮动作身份。

## 2026-10-11：反馈并发版本兼容升级

本节仅补充反馈并发控制，不改变景点评分、餐饮搜索或现有反馈含义。

- 按钮反馈仍使用原 POST/DELETE 路径及事件身份头。新增可选 `X-Feedback-Version`（非负服务端版本）。同一动作重试必须复用全部身份与版本；过期新操作返回 409 / `STALE_FEEDBACK_VERSION`；同事件不同内容返回 409 / `BEHAVIOR_EVENT_CONFLICT`。
- POST 响应新增 `feedbackState: { reaction: "LIKE" | "DISLIKE" | null, version: number }`，返回当前已确认状态，不把旧重试的意图当成当前反应。
- DELETE 无新协商头时保持 204；请求 `X-Feedback-State: 1` 时返回 200 及上述状态对象。撤销重试不得再次删除后续新反应。
- 自然语言反馈请求可传 `expectedFeedbackVersion`。未传时，服务端在模型调用前读取版本；模型结束后的写事务校验是否仍是该版本。响应新增 `feedbackState`，无推荐目标时为 null。
- 客户端仅采用不低于已确认版本的反应快照。版本不是客户端时间戳，不参与推荐计算。旧客户端无版本请求保持兼容，但不提供未确认旧操作的版本约束。

## 2026-10-18：地名搜索（仅用于餐饮搜索中心）

`POST /api/travel/sessions/{sessionId}/locations/search`

通过现有 Cookie / `X-Session-Token` 校验旅行会话归属；已归属其他账号的会话不能凭旧令牌访问。POST 仍需 `X-XSRF-TOKEN`。不需要 AI Key，AI 关闭时可用。

请求：`{"keyword":"杭州西湖"}`。关键词必填、最多 80 字。服务端调用固定的高德 `/v5/place/text`，仅取第一页、最多 10 个候选，不使用逆地理编码替代关键词搜索。

响应示意（不是运行时填充的默认数据）：

```json
{
  "candidates": [{
    "poiId": "由高德返回的真实POI标识",
    "name": "由高德返回的地点名称",
    "address": "由高德返回的地址或null",
    "province": "省份或null",
    "city": "城市或null",
    "district": "行政区或null",
    "latitude": 30.24,
    "longitude": 120.14,
    "coordinateSystem": "GCJ02",
    "source": "AMAP_POI"
  }]
}
```

- 缺少名称、无效/缺失坐标的 POI 不成为候选；可选文本和 POI ID 缺失时为 null。有效空结果返回 `candidates: []`。
- 权限、额度、网络和格式故障保留现有 `AMAP_PERMISSION_DENIED` / `AMAP_QUOTA_EXCEEDED` / `AMAP_NETWORK_ERROR` / `AMAP_SEARCH_FAILED` 分类；未配置服务为 503 `AMAP_NOT_CONFIGURED`。这些错误不能伪装成空候选。
- Key 仅由后端配置读取，不放在响应、前端或上游错误消息里。新增链路不记录关键词或用户坐标。
- 用户确认前仅保存选择草稿；确认后在当前页面内存保留候选的名称、地址、行政区、完整精度坐标、来源和坐标系。刷新后需要重新选择，不持久化搜索中心。
- 选中 GCJ02 中心直接交给现有餐饮接口，禁止重复转换；设备 GPS 仍为 WGS84，由既有后端转换。原测试/手动 UNKNOWN 坐标仍不得直接用于餐饮。
- 新中心与设备位置独立，不能传入历史景点推荐或自然语言景点反馈的距离计算。进入景点时仍使用原位置，历史数据覆盖范围不因此扩大。
- 中心变化沿用现有餐饮上下文失效机制：清空候选、已展示、排除和页码，重新生成搜索 ID；旧请求不能回填。账号/会话切换或取消选择使未确认搜索作废。
- 本接口不新增表、不写入 places、不修改 Flyway 迁移，不生成曝光或到访事件。

## 2026-10-20：可信推荐解释（本地修复，尚未部署）

`POST /api/travel/sessions/{sessionId}/recommendations/reasons?regenerate=false&batchNo=1`

- 新增可选 `batchNo`：必须为正整数，仅查询当前已认证用户会话的指定批次；不存在返回404。省略时兼容旧客户端，取该会话最新批次。前端现在明确发送当前展示的批次号。
- 保留原认证、会话归属、CSRF、凭据开关、限频、并发和持久化预算保护。AI/BYOK均关闭或无可用凭据时仍保持原501契约。
- 对外响应字段不变：`sessionId / batchNo / provider / cached / reasons`，每个理由仍包含 `recommendationId / rank / reason`。
- 模型仅返回内部 `selections: [{rank, evidenceIds}]`，不能返回自由文本；每项最多三个ID，且必须属于该会话、批次、推荐行及当前依据内容指纹。非法ID、未知字段、异常格式、超时或上游失败只使用本批次确定性解释，不自动再调用模型。
- 理由由服务端依据目录渲染；类型为当前静态地点标注、本批次临时偏好、本批次条件、已保存评分。实时客流/排队/营业/价格/天气舒适程度/导航路线等没有可靠依据，不建立可选ID。
- 当前数据没有历史有效画像与细分匹配快照，因此不读取最新反馈重算旧批次，也不声称恢复了当时细分偏好。静态属性明确写“当前地点资料”，热闹标签明确不代表实时人流。
- 原 `recommendations.reason` 文本列存储内部v1缓存封套：版本、批次内容指纹、名次、依据ID及是否由模型选择。对外永远重新校验并渲染，不透传封套；不新增迁移。
- 旧自由文本、错误版本、跨批次或损坏缓存会在请求时本地替换为受控缓存，不因修复缓存追加付费调用。合法受控缓存继续复用。
- 全部采用本地降级时，`provider` 为 `local:evidence-v1`；存在模型有效选择时保持原模型来源格式。`cached=true` 仅表示本次复用已校验缓存，不表示本次调用了模型或模型学会了偏好。
- 模型漏选的地点使用本地依据补齐。前端原有账号版本、会话和批次响应校验继续保留。
