# 餐饮卡片与分页验收（2026-10-04）

## 范围

分支 `feat/travelmind-ui-v2`。独立餐饮 API 和 UI，不接入 RecommendationEngine，不修改景点推荐服务、places 或迁移，不推送远端。工作区中仍保留阶段 1/2 的未提交文件，本报告只列本轮修改。

## 本轮文件

后端路径前缀 `backend/src/`：

- `main/java/com/example/personality/dto/RestaurantSearchRequest.java`：可选 page，默认 1，范围 1–25，非法参数 400。
- `main/java/com/example/personality/dto/RestaurantSearchResponse.java`：currentPage，不提供未经可靠验证的 hasMore。
- `main/java/com/example/personality/service/AMapRestaurantSearchService.java`：page 映射 page_num，保持类型、排序、半径及坐标转换链路。
- `test/java/com/example/personality/service/AMapRestaurantSearchServiceTest.java`：默认页和第二页传递。
- `test/java/com/example/personality/controller/RestaurantControllerTest.java`：非法页码。

前端路径前缀 `frontend/src/`：

- 新增 `travel/restaurantSearch.ts`、`travel/restaurantSearch.test.ts`：独立分页状态和竞态保护。
- 新增 `components/RestaurantCard.tsx`、`components/RestaurantResults.tsx`、`components/RestaurantCard.test.tsx`：卡片、错误及缺失数据状态。
- 修改 `api.ts`：沿用同源、CSRF、会话令牌请求，不依赖 LLM Key。
- 修改 `screens/TravelResultScreen.tsx`、`screens/TravelResultScreen.feedback.test.tsx`：明确餐饮意图分流、位置调整隔离和回归测试。
- 修改 `styles.css`：仅餐饮作用域的卡片和窄屏样式。
- 文档：更新 `docs/RESTAURANT_API.md`；新增本报告。

## 分页与隔离

每批最多 3 张。缓存候选足够时不请求；不足则仅请求下一页。按 poiId 去除已展示、已排除和重复候选。没有新候选时显示暂无更多；不足三张不补假数据，不放宽距离。失败保留当前页码，重试同一页。

餐饮独立维护 page、candidates、shown、excluded、visible。位置、半径、会话或模式变化使旧请求失效；请求中重复点击不会推进多页。“本次不再推荐”只保留当前搜索上下文，刷新、离开餐饮或改变位置会清空，不调用景点反馈接口。

餐饮模式应用位置、距离及重新定位不会额外创建景点推荐批次。餐饮输入框不展示原景点回复目标。没有用户距离限制时明确使用 3 公里默认值；预算、时间和安静程度目前不参与餐饮筛选，页面明确提示。

## TDD 和验证结果

先观察分页、状态管理和卡片缺失实现的失败测试，再实现。收尾补充的测试实际捕获：餐饮模式应用条件误触发景点推荐、默认半径展示不一致、输入框残留景点回复目标。修复后重新全量执行。

| 检查 | 结果 |
| --- | --- |
| 后端 `mvnw.cmd clean test` | 363 项，失败 0、错误 0、跳过 0；BUILD SUCCESS |
| 前端 `npm test` | 12 个测试文件，91 项全部通过 |
| `npm run build` | TypeScript 检查和 Vite Production build 成功 |
| `git diff --check` | 通过 |
| Edge 360/375/390/412/430px | 无横向溢出，每批 3 卡，餐饮按钮高度最小约 45.6px |
| Edge 1440px | 无横向溢出 |
| 浏览器候选换批、本次排除 | 通过，无页面脚本错误 |

后端测试使用 PostgreSQL 测试库，Flyway 14 项校验通过，没有清库。高德为可控测试响应；Edge 布局验收使用公开模拟位置与明确标注的测试 POI，并非真实搜索结果。自动化不能证明真实手机软键盘、GPS 权限或高德在线分页已经验收。

既有警告：旧测试弃用 API、SubmitAnswersRequest 容器 @Valid 弃用、Mockito/JVM 类共享提示，以及异常路径测试预期日志。本轮未进行无关重构。

## 手机验收步骤及运行限制

1. 本轮没有替换正在运行的后端容器。先重新构建并启动包含 page/currentPage 的后端；旧实例会触发“餐饮分页响应与请求不一致”的提示。已有高德 Key 仍仅通过环境变量配置，不进入前端。
2. 前端如使用 Vite dev，刷新即可读取新代码；如使用静态预览，使用本轮 dist 并重新启动预览。保持原有 /api 代理与临时 HTTPS Tunnel。
3. 手机使用当前有效 HTTPS 地址，登录并允许 GPS，确认不是来源待确认的手动测试点。
4. 点“找点吃的”或输入“附近吃什么”，核对卡片真实名称、地址、直线距离；未知人均及图片显示正常占位。
5. 连续换批，先消费候选后请求下一页；无新 POI 时提示暂无更多。“本次不再推荐”后当前搜索不再展示该 POI。
6. 点击导航，核对高德目的地；直线距离不视为路线距离。
7. 调整位置或距离，并快速切换餐饮/景点；旧餐饮请求不得改变最新画面。断网后重试，恢复网络应继续原页。

手动/预设坐标来源未确认时拒绝高德餐饮搜索，不擅自当作 GCJ02。真实 GPS 使用 WGS84，再由后端转换。餐饮偏好排序、行为学习、Agent 均未扩展。下一步先完成真机在线分页验收，再决定是否增加餐饮偏好排序。
