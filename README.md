# 本地模型推理与评分网关（内存实现）

一套可独立运行的本地模型评分网关，用**内置内存后端**模拟多个推理后端与模型版本，
聚焦验证三件事：**灰度发布可观测、后端异常分级可区分、并发缓存一致不串号**。

- Java 21 + Spring Boot 3.4，无外部中间件依赖
- 内存后端打分完全确定：`score = fn(backendId, modelVersion, featureHash)`
- 每次请求打印请求内容、命中版本与判定依据（`decision` 字段 + 日志）

---

## 1. 模块结构

```
model/       请求/响应/版本 数据模型，RejectReason 稳定错误码
backend/     InferenceBackend 抽象 + InMemoryBackend（确定性打分、可注入延迟/故障）
version/     VersionRegistry：版本注册表，配置版本号 configVersion
routing/     WeightedRouter：加权灰度路由 + 实际流量统计；RouteDecision（判定依据）
circuit/     CircuitBreaker：CLOSED / OPEN / HALF_OPEN 熔断状态机
cache/       ScoringCache：TTL + configVersion 失效 + single-flight 去重
service/     ScoringService：校验 → 路由 → 熔断 → 缓存/计算 主流程
web/         ScoreController（评分/批量/管理/观测）+ GlobalExceptionHandler
config/      GatewayProperties（application.properties 绑定）+ GatewayConfig（装配）
```

---

## 2. 路由与灰度规则

### 2.1 请求模型

```json
{ "modelName": "fraud", "modelVersion": "v1", "featureHash": "f-001", "features": {} }
```

- `modelVersion` **非空（精确指定）**：必须在注册表中精确命中，
  绝不静默回退默认版本，任何不满足条件都以可区分原因拒绝：

  | 情况 | 对外原因码 |
  |---|---|
  | 版本未注册 | `VERSION_NOT_REGISTERED` |
  | 版本已停用 | `VERSION_DISABLED` |
  | 权重为 0（不承接流量，视为不兼容） | `VERSION_INCOMPATIBLE` |

- `modelVersion` **为空（灰度路由）**：只在该模型 `enabled=true` 且 `weight>0`
  的候选版本中，按权重做加权随机选点；全部权重为 0 或无启用版本时返回
  `NO_CANDIDATE_VERSION`。

### 2.2 加权选点

候选版本按版本号排序后做累计权重区间落点（`[0, totalWeight)`）：
- 权重即流量比例，权重为 0 不承接流量；
- 路由器累计每个版本的**实际命中次数**，可通过 `GET /api/stats`
  的 `traffic` 字段观测真实分配，例如
  `fraud|v1=3165(79.1%), fraud|v2=835(20.9%), total=4000`；
- 提供确定性选点（pinned point）入口，测试中可精确复现灰度结果。

### 2.3 运行时灰度调整

```
POST /api/admin/weight?model=fraud&version=v1&weight=0
POST /api/admin/enabled?model=fraud&version=v2&enabled=false
```

任何权重 / 启用状态变更都会使注册表 `configVersion` 递增并清空缓存
（缓存条目本身也带 configVersion 守卫，双保险），因此**模型版本升级或权重调整后，
旧结果一定不会再被读到**。

### 2.4 默认配置

`src/main/resources/application.properties`：

```properties
gateway.backend-timeout-ms=200
gateway.failure-threshold=3
gateway.open-state-ms=2000
gateway.cache-ttl-ms=60000
# 内置后端
gateway.backends[0].id=backend-a   # latency/failure 可配
gateway.backends[1].id=backend-b
# fraud 模型：v1(backend-a,权重80) / v2(backend-b,权重20)
gateway.versions[0]...weight=80
gateway.versions[1]...weight=20
```

---

## 3. 降级与熔断策略

### 3.1 异常分级（不同且稳定的对外结果）

| 触发方式 | 对外原因码 | 是否计入后端健康度 |
|---|---|---|
| 请求体/字段非法（modelName、featureHash 缺失，JSON 破损） | `INVALID_REQUEST`（HTTP 400） | 否 |
| 后端判定参数非法（注入 `failure=illegal`） | `BACKEND_ILLEGAL_ARGUMENT` | 否（调用方问题） |
| 后端响应超过 `gateway.backend-timeout-ms`（注入延迟或 `failure=timeout`） | `BACKEND_TIMEOUT` | 是 |
| 后端内部错误（注入 `failure=internal`） | `BACKEND_INTERNAL_ERROR` | 是 |
| 熔断器打开期间的未命中请求 | `CIRCUIT_OPEN` | — |

超时实现：后端调用跑在虚拟线程上，网关侧 `future.get(timeoutMs)` 到时即返回
`BACKEND_TIMEOUT`，不等待后端。

### 3.2 熔断状态机（每后端一个，互不影响）

```
CLOSED ──连续失败达到 failureThreshold──▶ OPEN ──openStateMs 冷却──▶ HALF_OPEN
  ▲                                        │                        │
  └──────────── 试探成功 ──────────────────┴──── 试探失败 ──▶ 重新 OPEN
```

- `OPEN` 期间不再调用后端，直接返回 `CIRCUIT_OPEN`；
- `OPEN` 期间**已经完成的结果不被改写**：
  熔断检查位于缓存加载器内部，缓存命中时直接返回原始不可变结果（降级），
  只有缓存未命中、需要真正调用后端时才被熔断拦截；
- 冷却结束后只放一个试探请求进入 `HALF_OPEN`：成功 → `CLOSED` 恢复承载流量，
  失败 → 重新 `OPEN` 并重新计时。

### 3.3 可观测性

`GET /api/stats` 返回：

```json
{
  "configVersion": 5,
  "cacheSize": 3,
  "cacheHits": 12,
  "traffic": "fraud|v1=...%, fraud|v2=...%, total=N",
  "circuitBreakers": { "backend-a": "CLOSED(failures=0)", "backend-b": "OPEN(failures=3)" }
}
```

---

## 4. 带缓存的批量评分

- 缓存键：`modelName|modelVersion|featureHash`；TTL 由 `gateway.cache-ttl-ms` 控制；
- **配置版本失效**：条目写入时记录 `configVersion`，版本升级/权重调整后版本号递增，
  旧条目立即失效；
- **single-flight 去重**：同一缓存键的并发请求按 key 分段加锁，只有一个线程真正计算，
  其余线程共享同一个 `CompletableFuture`，杜绝并发重复计算；
- **只缓存成功结果**：计算异常立即移除占位 Future，失败不污染缓存；
- 批量接口 `POST /api/score/batch` 并发执行，批内相同请求自动去重，
  响应按请求位置一一对应，不同请求不会串号；
- 返回结果中的 `cacheHit` 标识本次是否命中；命中时生成新的不可变响应对象，
  缓存内的原始结果永不被改写。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/score` | 单条评分 |
| POST | `/api/score/batch` | 批量评分（JSON 数组） |
| GET | `/api/stats` | 灰度流量 / 熔断 / 缓存观测 |
| POST | `/api/admin/weight` | 调整灰度权重（清缓存） |
| POST | `/api/admin/enabled` | 版本上下线（清缓存） |
| POST | `/api/cache/invalidate` | 手动清空缓存 |

成功响应示例：

```json
{
  "success": true,
  "modelName": "fraud", "modelVersion": "v1", "backendId": "backend-a",
  "score": 0.9915, "reason": null, "cacheHit": false, "elapsedMs": 7,
  "decision": "gray-route point=75/100 picked=v1(weight=80, backend=backend-a) | backend=backend-a computed"
}
```

失败响应示例（业务失败为 HTTP 200 + `success=false` + 可区分 `reason`；
请求非法为 HTTP 400）：

```json
{ "success": false, "modelVersion": "v9", "reason": "VERSION_NOT_REGISTERED",
  "message": "version not registered: v9", "decision": "pinned version lookup failed" }
```

---

## 6. 验证方法

### 6.1 运行测试

```bash
mvn test
```

21 个测试全部通过，分三类场景（测试日志会打印请求、命中版本与判定依据）：

| 测试类 | 覆盖内容 |
|---|---|
| `GrayRoutingTest` (7) | 精确版本路由；未注册/停用/零权重三种可区分拒绝；权重 80/20 下 4000 次实际流量落在 75%~85%；确定性选点；权重 100/0→0/100 切换后流量切换且旧缓存失效 |
| `BackendFailureTest` (4) | illegal/timeout/internal 映射不同原因码；慢后端超时累计到阈值后熔断、熔断后不再调用后端；熔断期间新请求 `CIRCUIT_OPEN`、已完成缓存结果不变且可读、冷却+恢复后重新承载流量；非法请求不计入健康度 |
| `ConcurrencyCacheTest` (4) | 32 线程并发相同请求只计算 1 次、31 次命中、结果完全一致；10 条含重复批量请求 v1/v2 各只计算 2 次且按位置对应不串号；TTL 内重复请求不重复计算；版本升级后缓存失效 |
| `ApiIntegrationTest` (5) | HTTP 层端到端：成功评分、400 请求非法、未注册版本可区分拒绝、stats 观测、管理接口权重生效 |

### 6.2 手工冒烟

```bash
mvn spring-boot:run

# 灰度评分（modelVersion 留空）
curl -s -X POST localhost:8080/api/score -H 'Content-Type: application/json' \
  -d '{"modelName":"fraud","featureHash":"demo-1","features":{}}'

# 精确指定不存在的版本 -> VERSION_NOT_REGISTERED，不回退
curl -s -X POST localhost:8080/api/score -H 'Content-Type: application/json' \
  -d '{"modelName":"fraud","modelVersion":"vX","featureHash":"demo-2","features":{}}'

# 观测实际灰度流量与熔断状态
curl -s localhost:8080/api/stats

# 灰度全量切到 v2（权重调整后缓存自动失效）
curl -s -X POST 'localhost:8080/api/admin/weight?model=fraud&version=v1&weight=0'
curl -s -X POST 'localhost:8080/api/admin/weight?model=fraud&version=v2&weight=100'
```
