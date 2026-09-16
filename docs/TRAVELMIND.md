> **来源**：`C:\Users\dingjiahao\Downloads\TravelMind.docx`
>
> 本文件由该 docx 自动转换而来（2026-09-16），**内容未做增删改**。
> 原 docx 仍是权威版本；这里只是为了让纯文本可搜索、可版本管理。
> ⚠️ 原 docx 只存在于 Downloads，仓库里这份是唯一副本。

---

# TravelMind AI 个性化旅行助手

完整项目计划书 · CS 大学生从 0 到 MVP 落地路线

## 一、项目定位

一句话定位：不是告诉你“哪里有旅游景点”，而是回答：“对于现在的你，此时此刻，什么最值得做？”

TravelMind 是一个 AI 个性化实时旅行决策助手，通过旅行偏好画像、实时位置、时间、天气、周边环境、当地信息、地点评价和用户反馈，为用户动态推荐当前最适合的旅行体验。

## 二、项目愿景

让每个人都拥有一个真正懂自己的 AI 旅行伙伴。

未来用户不需要花大量时间搜索攻略和规划路线，只需要表达自己的目标或到达目的地，系统就能结合用户长期偏好和当前状态，持续提供个性化建议。

## 三、核心产品理念

传统旅游产品：搜索 → 看攻略 → 比较 → 判断 → 规划 → 行动。

TravelMind：认识用户 → 感知环境 → 理解当前状态 → 筛选候选体验 → AI 做出推荐 → 用户选择 → 获得反馈 → 更新用户画像 → 下一次推荐更加准确。

核心不是旅游信息库，而是“用户状态理解 + 旅行决策”。

## 四、目标用户

第一阶段聚焦：

- 18～35 岁的自由行用户
- 第一次到陌生城市的人
- 喜欢探索但不喜欢花大量时间做攻略的人
- 喜欢当地文化、美食、小众体验的用户
- 愿意使用 AI 帮助自己做旅行决策的人

## 五、核心使用场景

1. 刚到陌生城市：用户到达后，系统结合时间、天气、位置和画像推荐当前最适合的活动。
2. 用户改变想法：用户说“不想走太远”，系统重新筛选附近体验。
3. 天气变化：原定户外计划受到天气影响，系统动态重新规划。
4. 用户疲劳：用户反馈“有点累”，系统降低步行距离和行程密度。
5. 探索当地文化：系统不只推荐热门景点，还根据用户偏好推荐更有当地特色的体验。

## 六、核心功能

1. 旅行偏好测试：8～10 道场景题，建立初始旅行画像。
2. 动态用户画像：记录美食、人文、自然、摄影、热门景点、小众体验、步行接受度、拥挤接受度、预算、计划偏好等。
3. 实时状态：结合位置、时间、天气、已去地点和用户主动反馈推测当前状态。
4. 地点信息：地点类别、营业时间、价格、文化标签、距离、预计停留时间等。
5. AI 综合评价：在合法授权和数据使用规则允许的前提下，对评价进行摘要、优缺点提取、推荐时间和适合人群分析。
6. Top 3 推荐：不向用户堆砌大量结果，而是给出当前最值得做的 3 个选择。
7. 用户反馈：支持喜欢、不喜欢、换一个，以及“我累了”“我饿了”等自然语言反馈。
8. 动态重新规划：天气、时间、位置、用户状态变化后重新计算推荐。
9. 导航：调用现有地图服务执行路线，不在第一版自建地图。
10. 后期 AI Agent：让 AI 根据用户目标自主调用天气、地点、地图等工具。

## 七、产品原则

- 测试只是初始猜测，真实行为和反馈更重要。
- 长期偏好与当前状态必须分开。
- AI 不能把推测当成事实，应使用“我猜你可能……”并允许用户纠正。
- AI 负责理解、总结和解释；传统算法负责距离、时间、营业状态和数值排序。
- 第一版不做超级 App，不重复建设地图、酒店、网约车等成熟基础设施。
- 核心始终围绕一个问题：“现在的你，最值得做什么？”

## 八、产品使用方式

阶段 1：移动端 Web + AI 对话。用户通过链接或二维码进入，不需要下载 App。

阶段 2：手机 App。增加 GPS、推送、长期用户画像和实时状态能力。

阶段 3：AI Agent + 可穿戴设备。未来用户甚至可以通过耳机等设备与旅行助手互动。

建议 MVP 从移动端 Web 开始，因为用户进入门槛低，更适合验证需求。

## 九、MVP 范围

必须实现：

- 用户旅行偏好测试
- 用户画像
- 用户定位
- 当前时间
- 天气
- 附近地点
- AI 推荐
- 推荐理由
- 用户反馈
- 导航

第一版暂时不做：

- 自建地图
- 酒店预订
- 网约车平台
- 机票
- 门票
- 社交社区
- 全球城市覆盖
- 复杂会员体系
- 自己训练大模型

## 十、核心技术架构

前端：TypeScript + React + Next.js

后端：初期可使用 Next.js API，后期可引入 Python + FastAPI

数据库：PostgreSQL

向量检索：PostgreSQL + pgvector

AI：主流 LLM API

外部服务：地图、地理编码、天气、地点、交通和酒店等 API，根据目标市场选择合法、稳定的数据供应商。

系统逻辑：

User → Web/Mobile → Backend → User Profile + Location + Weather → Recommendation Engine → Algorithm + AI → Top 3 → User Feedback → User Profile

## 十一、数据库初步设计

users

user_profiles

trips

trip_preferences

places

place_sources

weather_records

recommendations

recommendation_feedback

user_locations

旅行画像可以使用数值化特征，例如：

Food、Culture、Nature、Photography、Walking、Crowd tolerance、Popular places、Hidden gems、Planning、Spontaneity 等。

这些数值不是永久标签，而是会根据用户行为和反馈动态更新。

## 十二、推荐系统

候选地点首先由规则和算法筛选，再交给 AI 做理解和解释。

推荐分数可以综合：

- 用户兴趣
- 当前状态
- 天气
- 时间
- 距离
- 营业状态
- 地点质量
- 当地特色
- 历史反馈

最终输出 Top 3，而不是 Top 100。

## 十三、AI 与 Agent 设计

普通 AI 阶段：用户提问 → AI 理解 → 推荐和解释。

Agent 阶段：用户表达目标 → AI 判断需要哪些信息 → 调用工具 → 获取天气/位置/地点等数据 → 筛选和排序 → 给出建议 → 根据用户反馈继续调整。

Agent 不应成为所有逻辑的替代品，而应该作为协调多个工具和决策步骤的智能层。

## 十四、开发阶段与时间规划

Phase 0：产品设计，约 1 周

完成产品定位、用户画像、用户流程、MVP、UI 原型。

Phase 1：Python Prototype，约 2～3 周

不使用 AI，建立模拟地点数据和 Top 3 推荐算法。

Phase 2：Web MVP，约 3～5 周

学习 HTML、CSS、JavaScript、TypeScript、React、Next.js。

Phase 3：真实 API，约 2～4 周

接入 GPS、天气、地图和地点数据。

Phase 4：AI，约 3～5 周

学习 LLM API、Prompt、Structured Output、Tool Calling。

Phase 5：数据库，约 2～4 周

学习 SQL、PostgreSQL、ORM 和数据库设计。

Phase 6：个性化，约 3～5 周

建立测试画像、行为画像和 Feedback 系统。

Phase 7：RAG，约 3～4 周

学习 Embedding、Vector Search、RAG。

Phase 8：AI Agent，约 3～5 周

实现 AI 自动调用天气、地点和地图工具。

Phase 9：部署，约 2～3 周

学习 Docker、Linux、Cloud、CI/CD、HTTPS、Monitoring。

以上时间以每周约 10～15 小时为参考，实际进度取决于个人基础。

## 十五、CS 学习路线

Level 1：Python

基础语法、OOP、数据结构、异常、文件、JSON、async。

Level 2：软件工程

Git、GitHub、Linux、命令行、Debugging、Testing。

Level 3：Web

HTML、CSS、JavaScript、TypeScript、React、Next.js。

Level 4：Backend

HTTP、REST、API、Authentication、Authorization、FastAPI。

Level 5：Database

SQL、PostgreSQL、Index、Transaction、Database Design。

Level 6：AI

LLM、Prompt、Structured Output、Tool Calling、Embedding、RAG、Agent。

Level 7：推荐系统

Ranking、Scoring、Personalization、Context-aware Recommendation；后期再学习 Collaborative Filtering。

Level 8：系统设计

Architecture、Caching、Queue、Rate Limit、Logging、Monitoring、Scalability。

## 十六、学习方法

采用 Project Driven Learning，而不是“全部学完再开发”。

学习 REST API → 立即接天气 API。

学习 React → 立即制作旅行偏好测试。

学习 PostgreSQL → 立即保存用户画像。

学习 LLM → 立即实现 AI 推荐。

学习 RAG → 立即加入当地文化资料。

学习 Agent → 立即实现工具调用。

AI 编程助手可以使用，但必须理解生成代码的基本原理，能够解释代码、定位问题并维护系统。

## 十七、项目管理

建议使用 GitHub 管理项目。

目录可以设计为：

```
travelmind/
├── frontend/
├── backend/
├── ai/
├── recommendation/
├── database/
├── docs/
└── tests/
```

每个功能采用 Issue → Branch → Code → Test → Pull Request → Merge 的流程，即使一个人开发，也按照真实软件工程方式管理。

## 十八、测试与质量

需要测试：

- 功能是否正常
- API 失败时是否有降级方案
- AI 返回错误格式时如何处理
- 没有合适地点时如何处理
- 用户拒绝推荐时如何处理
- 没网络时如何处理
- API Key 是否泄露
- 用户是否可以删除自己的位置和画像
- AI 推荐是否存在明显事实错误

涉及第三方评价和地点数据时，必须遵守数据来源平台的 API、授权、版权和使用条款。

## 十九、产品核心指标

第一阶段不要过度关注下载量，应重点观察：

1. 推荐点击率
2. 推荐接受率
3. 用户满意度（如 👍 / 👎）
4. 次日或下一次旅行的重复使用率
5. 随使用次数增加，推荐接受率是否提升

核心验证问题：

“用户是否觉得 AI 比自己打开多个 App 搜索更方便？”

## 二十、商业模式

产品价值得到验证后，可以考虑：

- 酒店、交通、门票等合法 Affiliate/合作分成
- Premium AI 旅行助手
- 旅行会员
- 酒店、机场、旅游机构等 B2B 服务

商业模式不应先于用户价值验证。

## 二十一、主要风险与应对

1. 用户不需要：用 MVP 快速验证。
2. 推荐不准确：规则/算法 + AI，而不是完全依赖 AI。
3. 数据来源困难：优先合法 API、授权数据和许可明确的数据。
4. 巨头复制：不要把护城河建立在“AI 聊天”上，而应积累用户旅行偏好、行为反馈和个性化推荐能力。
5. 功能过多：始终围绕“现在的你，最值得做什么？”

## 二十二、第一年目标

第 1 阶段：独立做出 Python 推荐系统。

第 2 阶段：做成 Web。

第 3 阶段：接入真实 API。

第 4 阶段：让 AI 参与决策。

第 5 阶段：让系统记住用户。

第 6 阶段：让真实用户使用。

第 7 阶段：验证用户是否愿意第二次使用。

最终目标不是“功能很多”，而是证明一个核心假设：

用户在陌生地方时，愿意把“下一步做什么”的一部分决策交给 TravelMind。

## 二十三、最终产品愿景

用户到达陌生城市，不再需要在地图、攻略、天气、餐厅和交通等多个工具之间反复切换。

他只需要打开 TravelMind 或告诉 AI：“我到了。”

系统结合：

旅行偏好 + 实时环境 + 当地信息 + 用户历史行为 + 当前反馈

回答：

“对于现在的你，此时此刻，最值得做什么？”

最终，TravelMind 从一个旅游 App 进化成个人旅行 AI Agent。

## 二十四、第一阶段执行清单

现在不要学习 Agent、RAG 或复杂 AI 框架。

第一阶段只完成：

1. 学会 Git/GitHub 基础
2. 用 Python 建立 20～50 个模拟旅游地点
3. 设计 8～10 道旅行偏好测试题
4. 用 Python 写出第一个推荐算法
5. 根据一个人的偏好输出 Top 3 推荐

完成后得到 TravelMind V0，再进入 Web、API、AI、数据库、个性化、RAG 和 Agent 阶段。

## 项目路线总览

产品原型 → Python → 推荐算法 V0 → React / Next.js → Web MVP → 地图 + 天气 API → LLM → AI Recommendation → PostgreSQL → Personalization → RAG → Agent → Dynamic Planning → Deployment → Real Users → Product Iteration
