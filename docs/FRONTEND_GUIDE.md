# React 版导读：从命令式 DOM 到声明式

> 你写过静态版页面（直接操作 DOM）。那份代码**能跑**，但用的是一套完全不同的思路。
> 这份文档讲清楚"为什么同一件事在 React 里要换个写法"，以及**移植时最容易踩的坑**。

---

## 0. 两个版本的关系

`frontend/`（React）和 `src/main/resources/static/index.html`（静态页）**功能完全一致**，
共用同一个后端。留着静态页是有意的：别人 clone 仓库后不装 Node 也能立刻跑起来。

对照阅读是学 React 最快的方式——同一段逻辑，两种写法摆在一起看。

---

## 1. 最根本的区别：谁来管 DOM

### 静态版：你手动改 DOM

```js
function renderQuestion() {
  document.getElementById("qText").textContent = q.content;

  const box = document.getElementById("options");
  box.innerHTML = "";                          // ← 先清空
  state.options.forEach(opt => {
    const btn = document.createElement("button");   // ← 再造
    btn.onclick = () => choose(q.id, opt.value);
    box.appendChild(btn);
  });

  document.getElementById("prevBtn").disabled = state.index === 0;  // ← 再同步状态
}
```

**你得记住"哪些 DOM 需要跟着状态变"**，漏掉一处就出现「数据变了但界面没变」。
选项变化要重建列表、按钮的 disabled 要单独设、进度条宽度要单独设——
三处同步，忘一个就是 bug。

### React：你只描述"长什么样"

```tsx
return (
  <div className="card">
    <p className="q-text">{current.content}</p>
    <div className="options">
      {options.map((opt) => (
        <button className={chosen ? 'opt chosen' : 'opt'}
                onClick={() => choose(current.id, opt.value)}>
          {opt.label}
        </button>
      ))}
    </div>
    <button disabled={index === 0} onClick={() => goTo(index - 1)}>上一题</button>
  </div>
)
```

**你没有写一行"怎么改 DOM"的代码。** 你只是说：题干是 `current.content`、
选项列表是 `options` 映射出来的、按钮在 `index === 0` 时禁用。

状态一变，React 重新执行这个函数，算出新的界面描述，然后**自己算出最小改动**去更新 DOM。

> 这叫**声明式**。你描述"是什么"，框架负责"怎么做"。
> 和 SQL 的思路其实一样——你写 `SELECT ... WHERE`，不写"先扫第 1 页再扫第 2 页"。

### 对照表

| 静态版 | React |
|---|---|
| `box.innerHTML = ''` 再逐个 `appendChild` | `items.map(item => <X />)` |
| `el.textContent = x` | `{x}` |
| `el.disabled = cond` | `disabled={cond}` |
| `el.style.width = pct + '%'` | `style={{ width: pct + '%' }}` |
| `el.onclick = fn` | `onClick={fn}` |
| `el.classList.toggle('chosen', c)` | `className={c ? 'opt chosen' : 'opt'}` |

---

## 2. 移植时最容易改错的地方：定时器

静态版修过一个 bug：**连点两次会跳两格、漏掉一题**。根因是
`setTimeout` 排了新的却从不取消。

React 版里，这个句柄**必须用 `useRef` 存，不能用 `useState`**：

```tsx
// ✅ 正确
const advanceRef = useRef<number | null>(null)

// ❌ 错误：这个值不参与界面展示，放进 state 会造成无意义的重新渲染
const [advanceTimer, setAdvanceTimer] = useState<number | null>(null)
```

`useRef` 是 React 里存放**「与渲染无关的可变值」**的地方——DOM 节点、定时器句柄、
上一次的值，都属于这一类。判断标准很简单：

> **这个值变了，界面需要跟着变吗？**
> 需要 → `useState`；不需要 → `useRef`。

另外，定时器**必须在组件卸载时清理**：

```tsx
useEffect(() => clearAdvance, [clearAdvance])
//         ^ 返回的函数就是清理函数，组件卸载时执行
```

不清理的话，定时器触发时组件已经没了，那次 `setState` 会被丢弃——
不会报错，但行为不符合预期。

---

## 3. 状态设计：能算出来的就别存

看 `TestScreen.tsx` 里这几个值：

```tsx
const answeredCount = questions.filter((q) => answers[q.id] !== undefined).length
const missing = total - answeredCount
const firstUnanswered = questions.findIndex((q) => answers[q.id] === undefined)
```

它们是**算出来的**，不是 `useState` 存起来的。

**新手最常见的错误**是给它们每个都建一个 state：

```tsx
const [answeredCount, setAnsweredCount] = useState(0)       // ❌
const [missing, setMissing] = useState(20)                  // ❌
```

这样你就得在**每次答题时手动更新这三个值**，漏掉一个就出现
「答题卡显示已答 6 题，但顶部显示已答 5 题」这类不同步的 bug，
而且极难排查——因为两处数据都"看起来是对的"。

> **原则：能从现有 state 和 props 算出来的，就不要再用 state 存。**
> 唯一的例外是计算成本极高（比如要遍历几万条数据），那时才用
> `useMemo` 缓存计算结果——但仍然是"算出来"的，不是"存起来"的。

---

## 4. 用联合类型表达状态，而不是一堆 boolean

`App.tsx` 里的屏幕状态：

```tsx
type Screen = 'home' | 'loading' | 'test' | 'result' | 'error'   // ✅
```

而不是：

```tsx
const [isLoading, setIsLoading] = useState(false)   // ❌
const [hasError, setHasError] = useState(false)
const [showResult, setShowResult] = useState(false)
```

三个 boolean 能表示 2³ = **8 种组合**，其中大部分是无意义的：
「加载中」+「有错误」+「要展示结果」同时为 true 是什么意思？

联合类型只允许 5 种合法状态，**编译器帮你排除掉 3 种不可能的情况**。

同样的手法用在 `AiPanel.tsx`：

```tsx
type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'done'; report: AiReportResponse }   // ← 只有这个状态携带数据
  | { kind: 'error'; message: string }           // ← 只有这个状态携带错误信息

const [state, setState] = useState<State>({ kind: 'idle' })
```

关键在最后两行：**每个状态各自携带它需要的数据**。
你不可能写出「loading 状态下访问 report」——因为那个状态下
`state` 上根本没有 `report` 字段，TypeScript 直接不让过。

这跟 Java 的对象建模思路是一致的：与其用一个字段很多、
大部分时候是 null 的类，不如用一组各自只带必要字段的子类型。

---

## 5. 三个 React 特有的坑

### 5.1 列表的 `key` 不是可有可无的

```tsx
{questions.map((q, i) => <button key={q.id}>{i + 1}</button>)}
```

`key` 是 React 判断"这一项还是不是原来那一项"的依据。**用数组下标做 key
在列表会增删或重排时会导致错误复用**——比如删掉第 2 项，React 会以为
是第 2 项的内容变了，于是把第 3 项的状态套在第 2 项上。

本项目的答题卡用 `key={q.id}`（稳定唯一），AI 解读的段落用 `key={i}`
（纯展示、不重排、不增删，下标是安全的）。

### 5.2 `useEffect` 的依赖数组

```tsx
useEffect(() => {
  document.addEventListener('keydown', onKeyDown)
  return () => document.removeEventListener('keydown', onKeyDown)   // ← 必须有
}, [choose, goTo, index, options, questions])
```

**少了返回的清理函数，每次 `index` 变化都会叠加一个监听器**——
按一次键会触发 N 次选择，而且越用越卡。

### 5.3 不要在渲染函数里改状态

```tsx
function Bad() {
  const [n, setN] = useState(0)
  setN(n + 1)     // ❌ 渲染时改状态 → 无限循环
  return <div>{n}</div>
}
```

渲染函数必须是**纯的**：同样的 props 和 state 进去，永远返回同样的界面描述。
所有改状态的操作都要放在事件处理函数或 `useEffect` 里。

> 开发模式下 `<StrictMode>` 会**故意把每个组件渲染两次**，就是为了
> 暴露这类不纯的写法。第一次看到 `console.log` 打印两次时别慌，这是正常的。

---

## 6. 下一步学什么

按优先级：

1. **`useMemo` / `useCallback`** —— 本项目已经用了 `useCallback`，
   但只在"传给子组件的回调"上。不要滥用：**绝大多数情况下 React 已经够快，
   过早优化只会让代码更难读。**
2. **自定义 Hook** —— 把"取数据 + 加载态 + 错误态"抽成 `useApi()`。
   本项目有 3 处重复的 `useState` 三元组，够格抽了。
3. **Context** —— 当层级超过 3 层还在传 props 时再用。本项目只用 1~2 层，
   用 props 是对的。
4. **Vitest + Testing Library** —— 给 `TestScreen` 的连点逻辑写个回归测试，
   把静态版踩过的坑永久钉死。
5. **路由（react-router）** —— 现在用 `?result=N` 手写解析就够。
   等页面涨到 6+ 个、需要嵌套路由和前进后退时再引入。

---

## 附：本项目前端刻意"没做什么"

| 没做 | 原因 |
|---|---|
| 状态管理库（Redux / Zustand） | 状态总共就 5 个字段，`useState` 完全够用 |
| 路由库 | 只有 3 个屏 + 1 个查询参数，状态机更简单 |
| UI 组件库（Ant Design / MUI） | 设计已经定稿成 CSS 令牌，引入组件库反而要覆盖它的默认样式 |
| 图表库（Recharts / ECharts） | 横向条形图手写只要 40 行，且能精确控制 4px 圆角、发丝网格这些设计规格 |
| CSS-in-JS（styled-components） | 颜色变量走 CSS 自定义属性，深浅切换只需翻转一个属性 |
| 数据请求库（TanStack Query） | 6 个接口、无缓存需求、无轮询，`fetch` 封装一层足够 |
