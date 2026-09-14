# 代码导读：把 Spring 挂到你已有的 Java 知识上

> 这份文档是为你写的——你 Java 语言层扎实（OOP、泛型、集合、异常、IO、JDBC + 手写事务、多线程），
> 但工程层是空白（没用过 Maven、JUnit、Git、框架，也没写过 lambda / Stream / Optional）。
>
> 下面每一节都遵循同一个套路：**先说"你原来怎么写"，再说"现在怎么写"，最后说"为什么"**。
> 如果某一节你觉得太啰嗦，直接跳过去看代码就行——文档是查的，不是读的。

---

## 0. 先建立全局地图

项目一共 44 个 Java 文件，但真正需要你理解的只有 4 层：

```
 HTTP 请求
    ↓
 ┌─────────────────────────────────────────────┐
 │ controller/   接参数、调 Service、返回结果    │  很薄，每个方法 1~2 行
 ├─────────────────────────────────────────────┤
 │ service/      业务逻辑 + 事务边界            │  ★ 逻辑都在这里
 ├─────────────────────────────────────────────┤
 │ repository/   数据库访问（不用写实现）        │  只有接口
 ├─────────────────────────────────────────────┤
 │ entity/       表结构在 Java 里的映射          │  字段 + getter
 └─────────────────────────────────────────────┘
    ↓
 PostgreSQL
```

另外几个包：

| 包 | 职责 |
|---|---|
| `domain/` | 领域对象：`Dimension`、`Level`、`ScoredItem`、`DimensionScore`。**不依赖 Spring** |
| `dto/` | API 的请求/响应对象。和 entity 分开，见第 6 节 |
| `exception/` | 自定义异常 + 全局处理器 |
| `ai/` | AI 生成的接口与桩实现（V0.2 才真正实现） |
| `config/` | 配置类（CORS） |

**看代码的建议顺序**（从你最容易理解的开始）：

1. `domain/` —— 纯 Java，你完全能读懂
2. `service/ScoringService.java` —— 纯 Java 算法 + 13 个单元测试
3. `entity/` —— 加了些注解的普通 Java 类
4. `repository/` —— 只有接口，没有实现
5. `service/TestSessionService.java` —— 事务在这里
6. `controller/` —— 最后看，很简单

---

## 1. Maven：Java 世界的 uv

你 Python 侧已经在用 `pyproject.toml` + `uv` + `.venv`。Java 这边是同一套思路换了个名字：

| Python | Java | 说明 |
|---|---|---|
| `pyproject.toml` | `pom.xml` | 声明依赖和构建配置 |
| `uv.lock` | 无对应文件 | Maven 用版本号精确锁定 |
| `.venv/` | `~/.m2/repository/` | 依赖存放位置（全局共享，不是项目内） |
| `uv sync` | `./mvnw dependency:resolve` | 下载依赖 |
| `uv run python x.py` | `./mvnw spring-boot:run` | 运行 |
| `pytest` | `./mvnw test` | 跑测试 |
| `uv` 本身 | `./mvnw` | **包装器**，自己下载正确版本的 Maven |

**关键点：你不需要单独安装 Maven。** `backend/` 目录下的 `mvnw` / `mvnw.cmd` 就是"项目自带的 uv"。

（上面表格里的命令都要在 `backend/` 目录下执行。项目是 `backend/` + `frontend/` 两个模块并列的结构，
Maven 只认 `backend/` 这一块。）
它第一次运行时会自动下载 Maven 3.9.16 到用户目录，之后直接复用。

### 你以前怎么用第三方库

```java
// java-learn/lib/mysql-connector-j-8.0.33.jar
// 手动下载 jar，放进 lib/，在 .vscode/settings.json 里配路径
// javac -cp ".;lib/*" ...
```

### 现在怎么写

```xml
<!-- pom.xml —— 只写坐标，不写文件 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

**版本号去哪了？** 因为我们继承了 `spring-boot-starter-parent`，它已经为几百个常用库
测试过并锁定了一组互相兼容的版本。这叫 BOM（Bill of Materials）——
你只写"要什么"，不写"要哪个版本"，避免了"我升了 A 结果 B 崩了"的依赖地狱。

---

## 2. 注解：不是魔法，只是元数据

你只用过 `@Override`。Spring 里注解无处不在，所以先把这件事说透。

### `@Override` 你其实已经理解了它的本质

```java
class Dog extends Animal {
    @Override
    public void speak() { ... }
}
```

`@Override` **不改变程序行为**。它只是告诉编译器："我打算覆写父类方法，
如果我拼错了方法名，请报错提醒我。"去掉它，代码照样跑。

### 所有的注解都是这个性质

注解 = **贴在代码上的标签**，本身什么都不做。
但是**有别的程序会去读这些标签，然后根据标签做事情**。

`@Override` 的读取者是 **javac 编译器**。
Spring 那些注解的读取者是 **Spring 框架**：启动时它扫描你的类，
看到 `@RestController` 就知道"这个类要接收 HTTP 请求"。

### 对照表

| 注解 | 谁读它 | 读完做什么 |
|---|---|---|
| `@Override` | javac | 检查是不是真的覆写了父类方法 |
| `@Entity` | Hibernate | 把这个类映射到数据库表 |
| `@RestController` | Spring MVC | 把这个类注册成 HTTP 请求处理器 |
| `@Service` | Spring | 把这个类注册成 Bean（可选，`@Component` 也行） |
| `@Transactional` | Spring AOP | 给方法套一层事务代理 |
| `@GetMapping` | Spring MVC | 把方法绑定到 `GET /xxx` |

### 你可以自己定义注解

```java
@Target(ElementType.METHOD)      // 只能贴在方法上
@Retention(RetentionPolicy.RUNTIME)  // 运行时还能读到（关键！）
public @interface MyLog {
}
```

`@Retention(RUNTIME)` 决定了"这个标签在运行期还在不在"。
只有在，Spring 才能通过反射读到它。如果写成 `CLASS` 或 `SOURCE`，运行时读不到，框架就无能为力。

**看懂这一节，Spring 就不再神秘了**——它只是在读标签，然后在合适的时机替你做事情。

---

## 3. 依赖注入（DI）与控制反转（IoC）

### 你以前怎么写

```java
public class StudentManager {
    private final StudentDao dao = new StudentDao();   // 自己 new
}
```

问题：`StudentManager` 和 `StudentDao` **焊死**了。想换成"从数据库读"的实现，
必须改 `StudentManager` 的源码。

### 现在怎么写

```java
@Service
public class QuestionService {
    private final QuestionRepository questionRepository;

    public QuestionService(QuestionRepository questionRepository) {   // 不 new，等人给
        this.questionRepository = questionRepository;
    }
}
```

`QuestionService` **不负责创建**自己的依赖，它只声明"我需要一个 `QuestionRepository`"。
Spring 启动时看到这个构造器，就会去容器里找（或者创建一个）符合类型的对象塞进来。

**"控制权"从你的代码反转到了 Spring 容器**——这就是 IoC（Inversion of Control）这个名字的来源。

### 为什么用构造器注入，而不是 `@Autowired` 注解字段

```java
// ❌ 不推荐
@Autowired
private QuestionRepository repo;

// ✅ 本项目统一用这个
private final QuestionRepository repo;
public QuestionService(QuestionRepository repo) { this.repo = repo; }
```

三个理由：

1. **字段可以是 `final`** —— 对象一旦创建依赖就不可变，天然线程安全。
   字段注入做不到（`final` 字段没法靠反射赋值）。
2. **依赖关系一眼可见** —— 构造器有 5 个参数，说明这个类干了 5 件事，该拆了。
   字段注入会把这个问题藏起来。
3. **好测试** —— 单元测试直接 `new QuestionService(mockRepo)`，
   不需要启动 Spring 容器。这正是 `ScoringServiceTest` 跑起来只要 0.12 秒的原因。

> Spring 4.3 之后，一个类如果只有一个构造器，`@Autowired` 可以省略，Spring 会自动用它。

---

## 4. Repository：方法名即 SQL

打开 `QuestionRepository.java`，你会看到一个**没有实现类的接口**：

```java
public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findAllByOrderBySortOrderAsc();
}
```

### 你在 `JdbcCrud.java` 里要做的事

```java
String sql = "SELECT * FROM questions ORDER BY sort_order";
PreparedStatement ps = conn.prepareStatement(sql);
ResultSet rs = ps.executeQuery();
List<Question> list = new ArrayList<>();
while (rs.next()) {
    Question q = new Question();
    q.setId(rs.getLong("id"));
    q.setContent(rs.getString("content"));
    // ... 还有 5 个字段要手工映射
    list.add(q);
}
```

### 现在

```java
List<Question> questions = questionRepository.findAllByOrderBySortOrderAsc();
```

**一行。没有 SQL，没有结果集映射，没有 try-with-resources。**

### 这魔力是怎么来的

继承 `JpaRepository` 白送你 18 个方法（`save` / `findById` / `findAll` / `deleteById` / `count` ...）。

而 `findAllByOrderBySortOrderAsc()` 这种叫**查询方法**，Spring Data 会
**解析方法名的英文语法**来生成 SQL：

```
findAllBy  OrderBy  SortOrder  Asc
   ↓          ↓         ↓        ↓
SELECT *    ORDER BY  sort_order  ASC
```

更多例子：

| 方法名 | 生成的 SQL |
|---|---|
| `findBySessionId(Long id)` | `WHERE session_id = ?` |
| `countBySessionId(Long id)` | `SELECT count(*) WHERE session_id = ?` |
| `existsBySessionId(Long id)` | `SELECT ... WHERE session_id = ? LIMIT 1` |
| `findByContentContaining(String s)` | `WHERE content LIKE '%s%'` |

### ⚠️ 两个必踩的坑

**坑 1：方法名拼错不会编译报错，只在启动时炸。**

```java
List<Question> findAllByOrderBySortOrdrAsc();   // 少了个 e
```

编译完全通过。但启动时报：

```
PropertyReferenceException: No property 'sortOrdr' found for type 'Question'
```

**看到这个异常，第一反应就是去检查 Repository 的方法名拼写。**

**坑 2：这里写的是 Java 字段名（驼峰），不是数据库列名（下划线）。**

```java
findBySessionId(...)    // ✅ sessionId，不是 session_id
```

Spring Data 会自动做驼峰 → 下划线的转换。

---

## 5. 事务：`@Transactional` ↔ `setAutoCommit(false)`

这是对你最重要的一个映射。

### 你在 `lesson6/TransferDemo.java` 里写的

```java
Connection conn = DriverManager.getConnection(URL, USER, PWD);
conn.setAutoCommit(false);              // ← 开启事务
try {
    updateBalance(conn, from, -amount);  // 第一条 UPDATE
    updateBalance(conn, to, +amount);    // 第二条 UPDATE
    conn.commit();                       // ← 都成功才提交
} catch (SQLException e) {
    conn.rollback();                     // ← 任何一步失败，全部撤销
}
```

### 现在

```java
@Transactional
public PersonalityProfile submit(Long sessionId) {
    // 方法体 = setAutoCommit(false) 到 commit() 之间的所有代码
    // 一行 try-catch 都不用写
}
```

Spring 用 **AOP 动态代理**把这个方法整个包起来：进方法前开事务，正常返回就提交，
抛 `RuntimeException` 就回滚。

### ⚠️ 坑 1：默认只在 RuntimeException 时回滚（**和你手写 JDBC 的习惯相反**）

你写 `catch (SQLException e) { rollback(); }` 时，**任何异常都会回滚**，
因为是你手动调的。

但 Spring 的默认规则是：

| 抛出的异常 | 是否回滚 |
|---|---|
| `RuntimeException` 及其子类（非受检） | ✅ 回滚 |
| `Error` | ✅ 回滚 |
| `Exception` 及子类（**受检**，如 `IOException`） | ❌ **不回滚，照常提交** |

Spring 这么设计，是因为它假设受检异常是"可预期的业务分支"，不是"出错了"。

**所以本项目所有自定义异常都继承 `RuntimeException`**（见 `BusinessException`）。
如果你哪天写了个 `class MyException extends Exception`，然后从 `@Transactional` 方法里抛出来，
就会看到"**明明抛异常了，数据却存进去了**"这种极难排查的现象。

> 真需要让受检异常也回滚，得写 `@Transactional(rollbackFor = Exception.class)`。
> 但更好的做法是别让业务异常是受检的。

### ⚠️ 坑 2：自调用会绕过事务

```java
@Service
public class Foo {
    public void a() {
        this.b();      // ❌ 走的是原始对象，不是代理 —— b 上的 @Transactional 完全不生效
    }

    @Transactional
    public void b() { ... }
}
```

因为 `@Transactional` 是靠**代理对象**生效的。外部调用 `foo.b()` 时，调的是代理；
但类内部 `this.b()` 绕过了代理，直接调原始对象。**而且不报任何错。**

正确做法是把 `b()` 拆到另一个 Bean 里，或者注入自身代理。

### ⚠️ 坑 3：事务里绝不能调外部 HTTP 接口

事务没结束时，数据库连接一直被占着。默认连接池是 10 个连接，
如果每个请求在大模型 API 上卡 10 秒，10 个并发请求就把池耗尽了，
之后连"取题目"这种根本不需要数据库长连接的操作也会排队。

**这就是 `AiReportService` 刻意不加 `@Transactional` 的原因**——它把流程拆成
「短事务读 → 事务外调 AI → 短事务写」。

---

## 6. Entity vs DTO：为什么要有两个长得一样的类

```java
// entity/Question.java —— 映射数据库表
@Entity
@Table(name = "questions")
public class Question {
    private Long id;
    private String content;
    private Dimension dimension;
    private boolean reverseScored;   // ← 这个字段
    // ...
}

// dto/QuestionResponse.java —— 返回给前端
public record QuestionResponse(
        Long id, String content, String dimension, String dimensionLabel, int sortOrder
        // 注意：没有 reverseScored
) { }
```

**为什么不直接用 entity 当响应？** 两个理由：

**① 安全**：`reverseScored` 一旦暴露，用户打开浏览器 F12 就能看到哪些题是反向的，
然后有针对性地答题来操纵结果。更严重的场景是密码哈希、内部标记、软删除标记被泄露。

**② 契约稳定**：数据库表结构会因为内部原因变化（加索引列、改字段名、拆分表），
但 API 契约不能随便变——已经发布的客户端还在用。中间隔一层 DTO，
两边的演进就互相独立了。

---

## 7. 三个你还没用过的 Java 特性

### 7.1 `record`：一行写完一个数据类

```java
public record ScoredItem(Dimension dimension, boolean reverseScored, int rawScore) { }
```

这一行等价于：

```java
public final class ScoredItem {
    private final Dimension dimension;
    private final boolean reverseScored;
    private final int rawScore;

    public ScoredItem(Dimension d, boolean r, int s) { ... }
    public Dimension dimension() { return dimension; }   // 注意：是 dimension() 不是 getDimension()
    public boolean reverseScored() { ... }
    public int rawScore() { ... }
    public boolean equals(Object o) { ... }
    public int hashCode() { ... }
    public String toString() { ... }
}
```

全部由编译器生成，且**不可变**。适合做"纯数据载体"——DTO、领域值对象。

### 7.2 `Optional`：用类型系统消灭 NPE

```java
// 以前
TestSession session = sessionRepository.findById(id);   // 可能返回 null
session.getStatus();                                     // 💥 忘了判空就是 NPE

// 现在
TestSession session = sessionRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("会话不存在：" + id));
```

`findById` 返回 `Optional<TestSession>`。你**没法直接拿到** `TestSession`，
必须先调用 `orElseThrow(...)` 或 `orElse(...)` 之一。
编译器逼着你处理"查不到"这种情况。

### 7.3 lambda：把"一段代码"当成参数传

JUnit 的 `assertThrows` 需要你传"一段会抛异常的代码"：

```java
assertThrows(InvalidAnswersException.class, () -> service.score(items));
//                                          ↑ 这就是 lambda
```

`() -> service.score(items)` 的意思是"一个没有参数、执行 `service.score(items)` 的函数"。

在 `orElseThrow(() -> new ResourceNotFoundException(...))` 里，
lambda 的价值更明显：**它只在真的为空时才执行**。
如果用 `orElse(new ResourceNotFoundException(...))`（没有 lambda），
那不管查没查到，异常对象都会被创建出来——白白浪费。

> 你的 `ScoringService` 和 `TestSessionService` 里，所有循环都是普通的 `for`，
> 没有用 Stream API。这是刻意的：Stream 的链式调用对新手可读性差，
> 而这部分逻辑你要能一眼看懂。等你能轻松读懂这些代码了，再去学 Stream 不迟。

---

## 8. 一次请求的完整旅程

以 `POST /api/test-sessions/1/submit` 为例：

```
① 浏览器/curl 发请求
        ↓
② Tomcat（内嵌的 Web 服务器）接收，转成 HttpServletRequest
        ↓
③ Spring MVC 根据路径找到 TestSessionController.submit()
        ↓
④ @PathVariable 把 URL 里的 "1" 转成 Long 1，绑定到参数
        ↓
⑤ 调用 testSessionService.submit(1L)
        ↓
⑥ 【事务代理拦截】开启事务（相当于 setAutoCommit(false)）
        ↓
⑦ requireSession(1) → sessionRepository.findById(1)
   └→ Spring Data 生成的代理执行 SELECT * FROM test_sessions WHERE id = 1
        ↓
⑧ 校验状态、校验答完 → 失败则抛 ConflictException / InvalidAnswersException
        ↓
⑨ 把 Answer + Question 转成 List<ScoredItem>
        ↓
⑩ scoringService.score(items)  ← 纯 Java 计算，没有数据库参与
        ↓
⑪ profileRepository.save(profile) → INSERT INTO personality_profiles ...
⑫ session.markSubmitted() → JPA 脏检查发现对象变了 → UPDATE test_sessions ...
        ↓
⑬ 【事务代理】方法正常返回 → COMMIT（相当于 conn.commit()）
        ↓
⑭ ProfileQueryService.buildResult(1) 读回结果组装成 DTO
        ↓
⑮ Jackson 把 record 序列化成 JSON
        ↓
⑯ 返回 200 + JSON
```

**任何一步抛 `RuntimeException`，⑥→⑬ 之间所有数据库改动全部回滚。**

---

## 9. AI 模块：四个值得单独讲的设计

接入大模型看着只是"发个 HTTP 请求"，但真正容易出问题的地方都不在请求本身。

### 9.1 提示词也是代码，也应该被测试

`AiPromptBuilder` 是个**纯逻辑类**——不联网、不碰数据库、不碰 Spring 容器。
它只有两个方法：`systemPrompt()` 返回一段常量，`userPrompt(result)` 把 5 个维度拼成文本。

**为什么值得为它单独建一个类？**

如果提示词直接写在 `generateReport()` 里，你想测试它就得连 HTTP 一起 mock，
成本高到没人会写测试。而提示词恰恰是**最该被测试盯住**的东西——
它是一大段文本，改动时很容易顺手删掉某一行约束（比如"不做心理疾病诊断"），
而**删掉之后代码照样能跑通、接口照样返回 200**，只是输出的内容越界了。

`AiPromptBuilderTest` 的 6 个测试就是在守这条线：
它不判断"提示词写得好不好"（那要靠人看输出），只验证
**"那些必须存在的约束有没有被误删"**。

> 这是本项目第二次用同一个手法：`ScoringService`（算法纯逻辑）和
> `AiPromptBuilder`（提示词纯逻辑）。**凡是"输入数据 → 输出结果"、
> 不依赖外部世界的逻辑，都该切成能直接 `new` 出来测的类。**

### 9.2 事务里绝不能调外部 HTTP 接口

这是本项目最重要的性能教训，`AiReportService` 的类注释里写得很详细，
这里只说结论：

```java
// ❌ 错误做法：整个方法包在一个事务里
@Transactional
public AiReportResponse generate(Long sessionId) {
    var profile = loadProfile(sessionId);      // 短
    var content = callLlmApi(profile);         // ← 3~30 秒！连接一直被占着
    saveReport(content);
}

// ✅ 正确做法：读（短事务）→ 调 AI（事务外）→ 写（短事务）
public AiReportResponse generate(Long sessionId, boolean regenerate) {
    var profile = profileQueryService.loadProfile(sessionId);   // 只读事务，立刻结束
    var result  = profileQueryService.buildResult(sessionId);   // 只读事务，立刻结束
    var content = aiReportGenerator.generateReport(result);     // 事务外，慢就慢
    return save(...);                                            // save 自带事务
}
```

**为什么错误做法会雪崩**：事务没提交时数据库连接一直被占着。默认连接池 10 个，
只要 10 个用户同时点"生成 AI 解读"，连接池就空了，
之后**所有请求**——包括根本不需要 AI 的取题目、查结果——全部排队等待。
代码逻辑完全正确，压测一上来就挂。

> 第 ③ 步的事务从哪来？`aiReportRepository.save()` 自带——
> Spring Data 的 `SimpleJpaRepository` 在 `save` 方法上标了 `@Transactional`。
> 从非事务方法调用它，Spring 会自动开一个新事务。这也顺便规避了
> 第 5 节讲的"自调用陷阱"。

### 9.3 条件装配：用一个配置项的正反两面

项目里有两个 `AiReportGenerator` 实现，但容器里任何时候都**只能有一个**：

```java
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class DeepSeekAiReportGenerator implements AiReportGenerator { ... }

@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
public class StubAiReportGenerator implements AiReportGenerator { ... }
```

如果两个都被装配，Spring 注入时会报
`NoUniqueBeanDefinitionException: expected single matching bean but found 2`。

**为什么不用 `@ConditionalOnMissingBean`？** 它依赖 Bean 的注册顺序，
在 `@Component` 上行为不稳定（在 `@Configuration` 类里才可靠）。
用同一个属性的正反值来判断，结果是确定的，不依赖扫描顺序。
`matchIfMissing = true` 则保证**配置项完全不写时**也走桩实现。

### 9.4 永远不要相信外部输入——哪怕来自你自己调的 API

前端渲染 AI 返回的文本时，用的是 `textContent` 而不是 `innerHTML`：

```js
// ✅ 安全
const p = document.createElement("p");
p.textContent = para;

// ❌ 危险：模型输出是不可信内容
p.innerHTML = para;   // 如果模型被诱导输出 <script>...</script>，就会执行
```

大模型的输出**不是**你的代码，它是根据用户输入生成的文本。
只要提示词里能塞进用户可控的内容（哪怕是通过答题数据间接影响），
就存在注入的可能。把它当 HTML 插入，就是一个 XSS 入口。

这条原则在服务端也成立：`GlobalExceptionHandler` 里，
未预期异常返回给用户的永远是**写死的通用文案**，绝不是 `ex.getMessage()`——
因为异常的 message 里可能包含 SQL 语句、文件路径、内网主机名。

---

## 10. Spring Security：认证是怎么工作的

### 10.1 加了这个依赖后，第一件事是"放开"而不是"加强"

Spring Security 的默认行为是 **所有请求都需要认证**。不写配置类的话，
连 `GET /api/health` 都会返回 401。

所以 `SecurityConfig` 里的 `authorizeHttpRequests` 不是在"加强安全"，
而是在**把该放开的放开**。每一条 `permitAll()` 都是一个需要你确认过的决定：

```java
.requestMatchers("/api/auth/register", "/api/auth/login", ...).permitAll()
.requestMatchers("/api/health", "/api/questions").permitAll()
.requestMatchers(HttpMethod.POST, "/api/test-sessions").permitAll()
.requestMatchers("/api/me/**").authenticated()    // ← 只有"我的"数据需要登录
.anyRequest().permitAll()                         // ← 静态资源等
```

> **规则是从上往下匹配、先匹配到的生效**，所以 `.anyRequest()` 必须放最后。

### 10.2 认证异常发生在 Controller 之前

这是引入 Spring Security 后最容易困惑的一点：

```
请求 → 【过滤器链】→ DispatcherServlet → Controller → Service
        ↑
   认证/授权在这里失败，抛出的异常
   根本走不到 GlobalExceptionHandler
```

过滤器链里抛出的 `AuthenticationException` / `AccessDeniedException`
**不会**被 `@RestControllerAdvice` 捕获。必须在 `SecurityConfig` 里单独配置：

```java
.exceptionHandling(ex -> ex
    .authenticationEntryPoint((req, res, e) -> writeJson(res, 401, "请先登录", ...))
    .accessDeniedHandler((req, res, e) -> writeJson(res, 403, "没有权限", ...)))
```

**默认行为是 302 重定向到 `/login` 页面**——对浏览器页面合理，对 fetch 调用的
API 就是灾难：前端拿到一个 200 的 HTML 登录页，完全不知道发生了什么。

而 Controller 里抛出的 `BadCredentialsException`（登录密码错误）**能**被
`GlobalExceptionHandler` 捕获，因为它是在 DispatcherServlet 之后抛的。
**同一个框架，两种异常，两种处理位置**——搞不清这点会浪费很多时间。

### 10.3 为什么选 Session 而不是 JWT

| | Session Cookie（本项目） | JWT |
|---|---|---|
| 注销 | **服务端删会话，真失效** | 签发后无法撤回，要额外维护黑名单 |
| 令牌存放 | HttpOnly Cookie，**JS 读不到，XSS 偷不走** | 存 localStorage 则 XSS 可窃取；存 Cookie 又回到 Session 方案 |
| 过期刷新 | 滑动续期，框架内置 | 需要 refresh token 机制 |
| 水平扩展 | 需要共享会话存储（Redis） | 天然无状态 |

本项目单体部署，水平扩展的代价为零；而"注销是真的注销"和
"令牌偷不走"是实打实的收益。**真要扩展时，把会话挪到 Redis 即可，
业务代码一行不用改。**

### 10.4 CSRF：Cookie 认证必须开

**攻击场景**：你登录了银行网站，Cookie 里存着会话。然后你访问了一个恶意网站，
那个网站上的表单悄悄向银行接口发了一个 POST——**浏览器会自动附上你的会话 Cookie**，
服务端以为这是你本人的操作。

Session Cookie 方案下这是真实威胁，所以必须开 CSRF 防护。

**SPA 下的配置有个坑**：Spring Security 6 默认用 XOR 掩码令牌，
但掩码后的值和写进 Cookie 的原始值对不上，导致所有 POST 都 403。
解法是 `SpaCsrfTokenRequestHandler`（见 `config/` 包），按请求来源选择处理方式。

前端侧只需要做一件事：**每个写操作带上 `X-XSRF-TOKEN` 头**。
`api.ts` 里封装了一次，所有接口自动受益。

### 10.5 手动登录为什么有 4 步

```java
// 1. 校验凭证
Authentication auth = authenticationManager.authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated(username, password));

// 2. 换新的会话 ID（防会话固定攻击）
sessionAuthenticationStrategy.onAuthentication(auth, request, response);

// 3. 建立安全上下文
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(auth);
SecurityContextHolder.setContext(context);

// 4. 持久化到会话（否则下个请求就"忘了"你登录过）
securityContextRepository.saveContext(context, request, response);
```

这四步等价于 `formLogin` 过滤器内部做的事。我们没走表单登录，
所以要自己写——**第 2 步最容易被漏掉**，因为漏了之后功能完全正常，只是不安全：

> 攻击者先拿到一个会话 ID（诱导你点击带 `?JSESSIONID=xxx` 的链接），
> 骗你用这个会话登录。登录成功后服务端把这个会话标记为"已认证"——
> 而攻击者早就知道这个 ID，于是他直接就能以你的身份操作。

### 10.6 四个容易被忽略的安全细节

**① 存密码只能用哈希，且必须是慢哈希**

BCrypt/MD5/SHA256 的区别不在"能不能反推"，而在**速度**。
MD5 一秒能算几十亿次，用户密码空间小，暴力破解很快。
BCrypt 故意慢（约 100ms/次），并自带随机盐——同一个密码每次哈希结果都不同。

**② 登录失败不能泄露"用户是否存在"**

```java
// ❌ 攻击者能借此枚举出所有用户名
if (userNotFound) return "该用户不存在";
if (wrongPassword) return "密码错误";

// ✅ 统一消息 + 统一下耗时
return "用户名或密码错误";
```
耗时也要一致——`AppUserDetailsService` 抛 `UsernameNotFoundException` 后，
Spring Security 会执行一次**假的密码比对**来抹平时序差异。

**③ 用户身份只能来自服务端会话，绝不能来自请求参数**

```java
// ❌ 客户端传什么就信什么，改一下 id 就能冒用别人
Long userId = request.userId();

// ✅ 从认证上下文里取
Long userId = principal.getId();
```

**④ 用 `/me` 而不是 `/users/{id}`**

`/api/users/{id}/sessions` 这种路径，服务端一旦忘了校验
"路径里的 id 是不是当前用户"，改个 URL 就能看别人的数据。
这叫 **IDOR**（不安全的直接对象引用），是 OWASP Top 10 常客。
**用 `/me` 从 URL 里彻底消除了这个参数，也就消除了整类漏洞——能不给的参数就不要给。**

### 10.7 认证 ≠ 授权：一个真实修过的越权漏洞

**认证（Authentication）**回答「你是谁」，**授权（Authorization）**回答「你能做什么」。
Spring Security 把前者做得很好——配上 `@EnableWebSecurity`，
未登录的请求根本进不来。但**它不会替你判断"这个资源是不是你的"**。

本项目就踩了这个坑，而且是实测确认过的：

```
① 匿名 GET  /api/test-sessions/24/result   → 200，读到了别人的画像
② 匿名 POST /api/test-sessions/22/answers  → 200，写进了别人未提交的会话
```

**根因**：为了让「不登录也能做测试」，这些端点设成了 `permitAll`，
并隐含假设「知道 sessionId 就等于拥有它」。但 `sessionId` 是
**数据库自增的连续整数**——从 1 数到 N 就拿到了全站数据。

这叫 **IDOR**（不安全的直接对象引用），OWASP Top 10 的 A01 类，
是 Web 应用最常见的漏洞之一。

**修法：能力式访问控制（capability-based access control）**

给每个会话发一个随机 UUID 作为令牌，**持有令牌 = 拥有访问权**：

```java
// 两条放行规则，满足其一即可
if (token != null && session.matchesToken(token)) return session;          // 匿名用户凭令牌
if (principal != null && Objects.equals(session.getUserId(), principal.getId()))
    return session;                                                        // 登录用户凭身份
throw notFound(sessionId);                                                 // 其余一律拒绝
```

为什么这个方案对：**id 能不能猜到不重要，令牌猜不到就行。**
128 位随机 UUID 的暴力枚举空间是 2¹²⁸，而自增整数的"枚举空间"就是数据条数。

**三个容易漏掉的细节**：

1. **拒绝时返回 404 而不是 403。** 403 等于告诉攻击者
   「这个资源存在，只是你没权限」——那依然能用来枚举哪些 id 是有效的。
   GitHub 对私有仓库也返回 404，是同一个道理。

2. **令牌格式非法也要返回 404，不能是 400。** 如果格式错给 400、
   格式对但不存在给 404，攻击者就能靠状态码差异区分"格式对不对"。
   所以 `SessionAccessGuard.parseToken()` 把非法输入统一转成 null。

3. **`user_id` 为 null 的匿名会话，不等于"谁都能看"。**
   判断"是不是本人"时必须先确认 `session.getUserId() != null`，
   否则 `null.equals(...)` 这种写法会让任何登录用户都能访问所有匿名会话。

**这条经验可以推广**：任何「客户端传 id，服务端据此取数据」的地方，
都要问一句——**这个 id 猜得到吗？我校验它属于调用方了吗？**
凡是路径里出现资源 id 的接口，都是 IDOR 的候选点。

> 本项目还用了一个更省事的做法来规避同类问题：
> 「我的数据」接口用 `/api/me/...` 而不是 `/api/users/{id}/...`。
> **URL 里根本没有 id 参数，也就没有"改个 id 试试"的可能。**

### 10.8 不要给实体直接返回

`UserResponse` 里**没有** `passwordHash`：

```java
public record UserResponse(Long id, String username, Instant createdAt) {
    public static UserResponse from(User user) { ... }   // ← 手工挑选字段
}
```

如果图省事直接序列化 `User` 实体，密码哈希就会出现在 JSON 里，
用户打开 F12 就能看到，而哈希是可以离线暴力破解的。

这类事故的典型成因是："先直接返回实体吧，反正就一个字段不一样，回头再改。"
——那个"回头"通常不会到来。

---

## 11. 启动失败速查表

新手 90% 的启动失败是下面这几种，对着查：

| 报错关键词 | 原因 | 怎么修 |
|---|---|---|
| `No property 'xxx' found` | Repository 方法名拼错 | 检查方法名，字段名要和 entity 一致 |
| `No qualifying bean of type` | 类不在 `com.example.personality` 包下，扫不到 | 移到正确的包，或加 `@Component` |
| `Schema-validation: missing table` | 实体和数据库表对不上 | 检查 Flyway 脚本是否执行成功 |
| `Connection refused` / `FATAL: password authentication failed` | 数据库连不上或密码错 | 检查 `application.yml` 和 `DB_PASSWORD` 环境变量 |
| `Port 8080 was already in use` | 端口被占 | 关掉占用进程，或改 `server.port` |
| `Table 'xxx' already exists` | Flyway 和 Hibernate 抢表结构 | 确认 `ddl-auto: validate`（不是 update） |
| `Found non-empty schema but no schema history table` | 手动建过表，Flyway 不认 | 删库重建，或设 `baseline-on-migrate: true` |

### 引入 Spring Security 之后的（第 10 节相关）

| 报错 / 现象 | 原因 | 怎么修 |
|---|---|---|
| **所有请求都 401**，包括 `/api/health` | 没写 `SecurityFilterChain`，走的是默认"全部要认证" | 在 `authorizeHttpRequests` 里 `permitAll()` |
| **所有 POST 都 403**，GET 正常 | 缺 CSRF 令牌 | 前端带上 `X-XSRF-TOKEN` 头；SPA 还要配 `SpaCsrfTokenRequestHandler` |
| 未登录访问 API 返回 **302 跳转**或一页 HTML | 没配 `authenticationEntryPoint` | 见 10.2 节，要返回 JSON |
| 登录成功但下一个请求仍是匿名 | 忘了 `securityContextRepository.saveContext(...)` | 见 10.5 节第 4 步 |
| `expected single matching bean but found 2` | 同一接口有两个实现，都满足装配条件 | 用 `@ConditionalOnProperty` 的正反条件互斥 |
| `NoClassDefFoundError: com/fasterxml/jackson/databind/ObjectMapper` | **Spring Boot 4 用的是 Jackson 3**，包名变成了 `tools.jackson.databind` | 改 import。注解仍在 `com.fasterxml.jackson.annotation`，不用改 |

---

## 12. 下一步学什么

按优先级：

1. **`@Query`** —— 当方法名表达不了复杂查询时，直接写 JPQL 或原生 SQL：
   ```java
   @Query("SELECT q FROM Question q WHERE q.dimension = :dim")
   List<Question> findByDimension(@Param("dim") Dimension dim);
   ```
   你会写 SQL，这条路对你来说最自然。

2. **关联映射（`@ManyToOne`）** —— 本项目刻意避开了它。等你完全理解
   懒加载、N+1、级联之后再用，不要一上来就上。

3. **`@ConfigurationProperties`** —— 把配置读成一个类型安全的类，而不是到处 `@Value`。

4. **测试进阶** —— `@DataJpaTest`（只测数据库层）、`MockMvc`（测 Controller）、
   Testcontainers（用真实数据库跑集成测试）。

5. **Actuator** —— 生产级健康检查、指标、监控端点。

---

## 附：本项目刻意"没做什么"

这些不是遗漏，是权衡后的选择。知道"为什么不做"和知道"怎么做"同样重要：

| 没做 | 原因 | 什么时候该做 |
|---|---|---|
| 实体间用 `@ManyToOne` 关联 | 新手调试懒加载/N+1/循环序列化的成本远超收益 | 真需要对象图导航时 |
| Stream API | 链式调用降低可读性，你要能一眼看懂 | 熟悉之后 |
| Lombok | 隐藏了代码生成，调试时看不到 getter 从哪来 | 团队统一约定后 |
| 用户登录鉴权 | 与核心假设无关，会拖慢验证 | V0.2 |
| Docker | 本机跑通优先，不是 V0.1 的验收项 | 部署阶段 |
| Controller 层测试 | lambda 密度高、成本大、收益小 | 接口稳定后 |
| 缓存 | 20 道题、个位数用户，没有性能问题 | 有真实用户量后 |
| 流式输出（SSE） | V0.2 先跑通闭环，流式是体验优化 | 用户反馈"等太久"时 |
| 多模型路由/降级 | 单一供应商够用，加降级要处理更多分支 | 有可用性要求时 |
