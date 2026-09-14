const DIMENSIONS = ['开放性', '外向性', '责任心', '宜人性', '情绪稳定性']

export function HomeScreen({ onStart }: { onStart: () => void }) {
  return (
    <div className="card">
      <h1>用 20 道题，看看你现在的样子</h1>
      <p className="lede">
        答完 20 道题，系统会算出你在 5 个人格维度上的分数，并给出一份解读。大约需要 3 分钟。
      </p>

      <div className="dims">
        {DIMENSIONS.map((name) => (
          // key 用维度名而不是数组下标：这个列表是静态的、不会重排，
          // 两者都行。但如果将来维度从接口动态获取，用下标做 key
          // 会导致增删时 React 复用错误的 DOM 节点。
          <span className="dim-chip" key={name}>
            {name}
          </span>
        ))}
      </div>

      <button className="btn btn-primary" type="button" onClick={onStart}>
        开始测试
      </button>

      <p className="note">
        本测试定位为自我探索与娱乐参考，不是心理诊断，也不代表固定不变的人格结论。结果仅供你自己参考。
      </p>
    </div>
  )
}
