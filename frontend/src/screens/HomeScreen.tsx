const DIMENSIONS = ['开放性', '外向性', '责任心', '宜人性', '情绪稳定性']

const TRAVEL_DIMENSIONS = ['自然风光', '人文历史', '美食探索', '摄影出片', '小众独特', '人群耐受', '步行意愿', '提前规划']

export function HomeScreen({
  onStart,
  onStartTravel,
}: {
  onStart: () => void
  onStartTravel: () => void
}) {
  return (
    <>
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

      {/*
        第二个入口。两个测试共用同一套"建会话 → 答题 → 提交计分"的机制，
        但画像的维度、结果页、后续动作完全不同（人格出解读，旅行出 Top 3 推荐），
        所以是并列的两个流程而不是一个流程加个开关。
      */}
      <div className="card">
        <h1>旅行偏好测试</h1>
        <p className="lede">
          8 道场景题，建立你的旅行偏好画像，然后结合你此刻的位置，
          告诉你在杭州「现在最值得去的 3 个地方」。大约 1 分钟。
        </p>

        <div className="dims">
          {TRAVEL_DIMENSIONS.map((name) => (
            <span className="dim-chip" key={name}>
              {name}
            </span>
          ))}
        </div>

        <button className="btn btn-primary" type="button" onClick={onStartTravel}>
          开始旅行测试
        </button>

        {/*
          ⚠️ 这段话必须跟着功能走。之前写的是"不接真实地图和天气"——
          那在接高德之前是对的，接了之后就成了**当面撒谎**：
          用户会在结果页看到真实地名和天气，却先被告知"没有这些"。
          自己说出去的话和产品实际做的事对不上，比没有这句话更糟。
        */}
        <p className="note">
          景点用的是 59 个杭州模拟数据；定位、天气和导航接了高德。
          推荐理由由算法算出（不是 AI 编的），AI 生成自然语言解读是下一步。
        </p>
      </div>
    </>
  )
}
