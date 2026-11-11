# 独立餐饮搜索 API（阶段 3 分页更新）

餐饮搜索独立于 RecommendationEngine，不写入 places，不新增迁移。阶段 3 增加分页和独立前端卡片；以下阶段 2 验收记录作为历史保留。

## 请求

`POST /api/travel/sessions/{sessionId}/restaurants/search`

沿用现有会话访问控制：登录用户本人的会话，或有效 `X-Session-Token`；同时沿用 CSRF Cookie 和 `X-XSRF-TOKEN` 请求头。不能只凭 sessionId 访问。

```json
{
  "latitude": 33.735,
  "longitude": 113.3077,
  "coordinateSystem": "WGS84",
  "radius": 3000,
  "page": 1
}
```

以上为公开测试点请求示例，不是用户 GPS。坐标系必须根据实际来源填写，不能照抄示例替真实定位定性。WGS84 调用高德转换接口；GCJ02 直接搜索；UNKNOWN 返回 400，不猜测来源。转换失败不继续搜索。

radius 单位米，允许 1–50000，省略时 3000。调用方应传入用户本次距离限制；接口不会自动读取前端状态。page 可选，默认 1，范围 1–25，非法参数返回 400 和字段错误。高德查询固定 `types=050000`、`sortrule=distance`、每页最多 10 条，page 仅映射为 `page_num`。返回距离再次按半径过滤，去重并按距离升序。

响应返回实际 `currentPage`，不根据上游 count 伪造 hasMore。前端每批最多三张，候选不足请求下一页；去除已展示、已排除和同页重复 POI；整页没有新候选则提示“暂无更多”，不循环扫描全部页。失败不推进页码。位置、半径或会话变化清空餐饮分页并使旧请求失效。

## 响应

无结果是正常 HTTP 200，不伪装成网络错误：

```json
{
  "searchSessionId": "00000000-0000-0000-0000-000000000001",
  "timestamp": "2026-10-01T00:00:00Z",
  "status": "NO_RESULTS",
  "radius": 3000,
  "coordinateSystem": "GCJ02",
  "distanceType": "STRAIGHT_LINE",
  "currentPage": 1,
  "restaurants": []
}
```

上例 ID 和时间仅展示协议，实际由服务端生成。有结果时 status 为 SUCCESS，每个元素包含：

| 字段 | 说明 |
| --- | --- |
| poiId / name | 高德地点 ID / 名称，非本地 Place ID |
| type / typeCode | 上游类别及分类码 |
| address | 上游地址 |
| latitude / longitude | GCJ02 坐标 |
| distanceMeters | 高德返回的直线距离（米），非导航路线距离；不足整米向上取整 |
| averageCost | 上游人均消费；缺失为 null，不当作 0 元 |
| openingHoursText | 原始今日营业文本，缺失时尝试周营业文本；不判断正在营业 |
| telephone | 上游 business.tel |
| imageUrl | 仅保留合法 HTTPS 图片；HTTP 图片返回 null，不代理或绕过浏览器安全策略 |
| rating / tags | 仅映射上游评分和标签，缺失为 null |

缺失可选字段为 null；没有安静程度推测。缺少 ID、名称、有效坐标或距离的记录无法可靠导航/遵守半径，排除。不会为凑数量扩大半径。

## 错误

复用现有错误响应中的 `code`、`message` 字段，不返回高德原始 JSON、Key 或请求 URL。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | COORDINATE_SYSTEM_UNKNOWN | 坐标来源待确认 |
| 502 | COORDINATE_CONVERSION_FAILED | 坐标转换响应失败或无效 |
| 502 | AMAP_PERMISSION_DENIED | Key 类型、权限或有效期异常 |
| 503 | AMAP_QUOTA_EXCEEDED | 额度/频率/余额限制 |
| 502 | AMAP_NETWORK_ERROR | 连接失败或超时 |
| 502 | AMAP_SEARCH_FAILED | 其他上游失败或无效响应，不是无结果 |
| 503 | AMAP_NOT_CONFIGURED | 高德服务未启用 |
| 404 | 沿用会话错误 | 无权访问或会话不存在 |

权限、额度、网络错误即使发生在坐标转换期间，也保留其具体分类，并停止搜索。

## 运行与验证边界

后端使用现有 `AMAP_ENABLED=true` 和 Web 服务类型 `AMAP_KEY`，不从请求接收 Key。修改后需要重新构建并重启后端，正在运行的旧 Docker 镜像不会自动更新。

自动测试覆盖：转换前后搜索参数、禁止重复转换、失败停止、响应解析、缺失字段、距离边界、去重排序、错误分类、会话权限与 CSRF。HTTP 测试使用本地模拟高德响应，数据库集成测试使用真实 PostgreSQL；它们不等价于真机/真实高德在线验收。

保留 `searchSessionId`、timestamp 和 RestaurantAction（poiId、searchSessionId、userAction、timestamp）作为未来事件契约；没有事件写入端点或学习逻辑，不复用本地地点反馈接口。阶段 3 的“本次不再推荐”仅在当前餐饮搜索上下文中保留，位置变化、离开餐饮或刷新会清空。

## 本阶段文件清单与验收

以下路径相对项目根目录：

- 修改 `backend/src/main/java/com/example/personality/amap/AmapClient.java`：严格调用与安全错误分类，保留旧软失败入口。
- 调整阶段 1 的 `backend/src/main/java/com/example/personality/amap/AmapCoordinateConverter.java`：保留权限/额度/网络错误分类。
- 新增 `backend/src/main/java/com/example/personality/amap/RestaurantParser.java`。
- 新增 `backend/src/main/java/com/example/personality/service/AMapRestaurantSearchService.java`。
- 新增 `backend/src/main/java/com/example/personality/controller/RestaurantController.java`。
- 修改 `backend/src/main/java/com/example/personality/config/SecurityConfig.java`：显式声明新路径，仍保留 CSRF 和会话访问控制。
- 新增 `backend/src/main/java/com/example/personality/dto/Restaurant.java`。
- 新增 `backend/src/main/java/com/example/personality/dto/RestaurantSearchRequest.java`。
- 新增 `backend/src/main/java/com/example/personality/dto/RestaurantSearchResponse.java`。
- 新增 `backend/src/main/java/com/example/personality/dto/RestaurantAction.java`。
- 新增 `backend/src/main/java/com/example/personality/exception/AmapRequestException.java`。
- 调整 `backend/src/test/java/com/example/personality/amap/AmapCoordinateConverterTest.java`。
- 新增 `backend/src/test/java/com/example/personality/amap/AmapRequiredClientTest.java`。
- 新增 `backend/src/test/java/com/example/personality/amap/RestaurantParserTest.java`。
- 新增 `backend/src/test/java/com/example/personality/service/AMapRestaurantSearchServiceTest.java`。
- 新增 `backend/src/test/java/com/example/personality/controller/RestaurantControllerTest.java`。
- 新增 `backend/src/test/java/com/example/personality/RestaurantAccessIntegrationTest.java`。
- 新增本文件 `docs/RESTAURANT_API.md`。

2026-10-01 最终后端全量测试：361 项，失败 0、错误 0、跳过 0，BUILD SUCCESS。其中坐标/餐饮相关 31 项。测试库 Flyway V14 校验通过，没有修改迁移或清库。

既有警告：SubmitAnswersRequest 的容器 `@Valid` 用法弃用提示、旧测试的弃用 API 编译提示、Mockito 引起的 JVM 类共享提示；本阶段未进行无关重构。

当前分支 `feat/travelmind-ui-v2`，尚未提交/推送本阶段变更。工作区还保留阶段 1 的坐标契约与前端改动；本阶段没有编辑前端。这次未重新部署运行中的后端容器，也未声称已完成手机或新接口的真实高德在线联调。
