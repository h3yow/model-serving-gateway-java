# Model Serving Gateway（本地模型推理与评分网关）

一个可内置运行的内存版模型评分网关，用确定性的内存实现模拟**多个推理后端**与**多个模型版本**，
用于验证灰度发布、后端异常分级处理、熔断降级以及并发缓存下的结果一致性与可解释性。

- Java 21 + Spring Boot 3.4，无外部依赖（无需真实模型服务 / Redis / 数据库）。
- 所有后端评分是 `(版本, 特征)` 的**确定性函数**：同一版本的不同后端对同一输入给出完全相同的分数，
  因此灰度切换、后端降级、熔断恢复都不会改变结果数值。

---

## 1. 架构总览

```
HTTP(/score,/score/batch)
        │
        ▼
   ScoringService  ── 校验请求合法性
        │
        ▼
    CanaryRouter   ── 显式版本钉住 / 版本线灰度加权 / 未注册拒绝（记录流量计数）
        │
        ▼
  版本兼容性校验   ── 特征维度必须匹配该版本
        │
        ▼
    ScoringCache   ── TTL + 配置纪元失效 + 键级并发去重
        │  (未命中)
        ▼
 熔断/降级编排      ── CircuitBreaker 逐个尝试后端，超时/内部错误降级，非法请求即失败
        │
        ▼
 InMemoryInferenceBackend（确定性评分，可注入 TIMEOUT / INTERNAL_ERROR / INVALID_REQUEST）
```

核心包：

| 包 | 职责 |
|---|---|
| `gateway.model` | 请求/结果模型、稳定错误码 `GatewayErrorCode`、判定依据 `DecisionReason` |
| `gateway.routing` | 版本规格、灰度路由器、配置纪元 |
| `gateway.backend` | 推理后端抽象、内存后端、后端注册表、失败模式 |
| `gateway.resilience` | 每后端熔断器（CLOSED/OPEN/HALF_OPEN）与注册表 |
| `gateway.cache` | TTL 缓存、纪元即时失效、并发去重 |
| `gateway.service` | 评分编排（校验→路由→兼容→缓存→熔断降级→调用） |
| `gateway.web` | 评分接口与管理接口 |
| `gateway.config` | 基于 `application.properties` 的装配 |

---

## 2. 路由与灰度规则

### 2.1 两类目标

- **具体版本**：如 `ml-score-v1` / `ml-score-v2`。
- **版本线（family）**：如 `ml-score`，灰度在同一版本线内进行。

### 2.2 路由判定（见 `CanaryRouter`）

1. 请求字段是**已注册的具体版本** → 直接钉住路由到该版本。
   - 即使该版本当前灰度权重为 **0**，显式请求也照常服务，判定依据标记为
     `ZERO_WEIGHT_EXPLICIT`（区别于灰度随机分配）。
2. 请求字段是**已注册的版本线** → 按该版本线配置的权重**加权随机**分配到具体版本。
   - 所有权重均为 0 时返回 `NO_ENABLED_CANARY_VERSION`，**不**回退到任何默认版本。
3. **未注册**的版本/版本线 → 返回稳定错误码 `VERSION_NOT_REGISTERED`，绝不静默回退默认版本。
4. 版本已注册但**特征维度不匹配** → 返回 `VERSION_INCOMPATIBLE`（与“未注册”明确区分）。
5. 请求体非法（空版本、空特征、NaN/Infinity）→ 返回 `INVALID_REQUEST`。

### 2.3 权重可配置 & 流量可观测

权重在 `application.properties` 中按版本线配置（0..100 的相对比例）：

```properties
gateway.weights.ml-score.ml-score-v1=100
gateway.weights.ml-score.ml-score-v2=0
```

运行期可通过管理接口动态调整，调整后**立即生效并推进配置纪元**（进而使缓存失效）：

```bash
curl -X POST http://localhost:8080/admin/families/ml-score/weights \
  -H 'Content-Type: application/json' \
  -d '{"ml-score-v1":90,"ml-score-v2":10}'
```

实际流量分配可观测（记录每个版本线内每个版本的真实命中次数）：

```bash
curl http://localhost:8080/admin/traffic
# {"epoch":1,"weights":{...},"traffic":{"ml-score":{"ml-score-v1":180,"ml-score-v2":20}}}
```

权重是概率，实际计数会在样本量增大时收敛到配置比例；单测用 100/0、0/100 等边界权重做确定性断言。

---

## 3. 后端异常的分级处理、降级与熔断

### 3.1 三类异常 → 三个稳定错误码（不混淆）

| 后端情况 | 错误码 `GatewayErrorCode` | 是否计入熔断 | 是否尝试降级 |
|---|---|---|---|
| 请求非法（特征张量非法） | `INVALID_REQUEST` | 否 | 否，立即失败 |
| 处理超时 | `BACKEND_TIMEOUT` | 是 | 是 |
| 内部错误 | `BACKEND_INTERNAL_ERROR` | 是 | 是 |
| 熔断打开且无其它健康后端 | `ALL_BACKENDS_UNAVAILABLE` | — | — |

内存后端可通过管理接口注入失败模式以稳定复现：

```bash
curl -X POST "http://localhost:8080/admin/backends/cpu-v1-1/failure?mode=TIMEOUT"
# mode 取值：NONE / TIMEOUT / INTERNAL_ERROR / INVALID_REQUEST
```

### 3.2 降级策略

一次评分在目标版本的后端列表上**逐个尝试**：

- 熔断处于 OPEN 的后端直接跳过（判定依据记录 `CIRCUIT_OPEN_SKIPPED`）；
- 某个后端超时/内部错误 → 记录该后端熔断失败，**自动尝试同版本的下一个健康后端**，
  成功后判定依据标记为 `FALLBACK_HEALTHY_BACKEND`，并在其中保留完整尝试链
  （如 `attempts=[cpu-v1-1:BACKEND_INTERNAL_ERROR:CB_STATE=OPEN]`），保证可解释；
- 降级只在**同一版本线内的目标版本**之间进行，不会因为故障偷偷改变模型版本；
- 全部后端都不可用 → 返回最后一次后端错误码（超时与内部错误仍可区分）或
  `ALL_BACKENDS_UNAVAILABLE`。

### 3.3 自愈熔断（`gateway.resilience`）

每个后端一个独立熔断器，状态机三态：

```
                连续后端侧失败达到阈值
   CLOSED ───────────────────────────────► OPEN（隔离：本跳直接跳过，流量走其它健康后端）
     ▲                                       │
     │ 试探成功：恢复承载，失败计数/退避清零    │ 冷却到点后，首个真实请求原子抢到唯一试探权
     │                                       ▼
     └─────────────────────────────── HALF_OPEN（试探中：同一时刻最多 1 个试探请求）
                                             │ 试探失败：重新 OPEN，下一轮冷却逐级加长（有上限）
                                             └──────────────────────────────► OPEN
```

要点：

- **只计后端侧失败**：超时 / 内部错误计入连续失败；非法请求（请求自身问题）不计入，不熔断；
- **冷却到点自动试探（由真实流量惰性驱动，无需后台线程）**：OPEN 冷却到期后，第一个到达该后端的
  请求在临界区内原子地把状态切到 HALF_OPEN 并占用唯一试探名额；试探成功 → CLOSED 正常承载、
  **失败计数与退避轮次清零**；试探失败 → 重新 OPEN 并重新计时。因此不存在“冷却结束却怎么也恢复不了”；
- **并发唯一试探**：HALF_OPEN 期间同一后端同一时刻最多放 1 个试探请求（CAS 占位 + finally 幂等释放，
  意外异常也不会泄漏名额），其余并发请求一律跳过该后端、继续走其它健康后端，不会被试探阻塞；
- **退避逐级加长且有上限**：试探反复失败会拉长冷却间隔，避免反复试探把本来就不稳的后端彻底打垮；
- **结果一眼可辨**：试探成功恢复承载的这一跳，`decisionReason` 标记为 `RECOVERED_AFTER_PROBE`；
  被隔离跳过的每一跳在 `attempts` 中标记为 `后端:ISOLATED_SKIPPED`。

#### 3.3.1 退避与上限配置（`application.properties`）

| 配置项 | 默认 | 含义 |
|---|---|---|
| `gateway.circuit.failure-threshold` | 3 | 连续后端侧失败达到此次数后隔离（OPEN） |
| `gateway.circuit.initial-cooldown-ms` | 5000 | **首次**隔离冷却时长（毫秒），到期放一个试探 |
| `gateway.circuit.backoff-multiplier` | 2.0 | 每次试探失败后下一轮冷却的乘数（&ge;1）：默认 5s→10s→20s→40s… |
| `gateway.circuit.max-cooldown-ms` | 60000 | 冷却时长**上限**（毫秒），退避到此封顶不再增长 |
| `gateway.circuit.timeline-capacity` | 20 | 每后端状态时间线保留条数，写满覆盖最旧 |

冷却公式：第 `n` 次隔离（`n` 从 0 起）冷却 =
`min(initialCooldownMs × multiplier^n, maxCooldownMs)`，含 long 溢出保护。

#### 3.3.2 只读观测与保证（`GET /admin/backends/health`）

所有观测接口（`/admin/circuits`、`/admin/backends/health`、`/admin/traffic`、`/admin/cache`）
都是**严格只读**的，连着查、并发查、高频查都满足：

1. **不推进/打断恢复流程**：OPEN→HALF_OPEN 的迁移只能由真实请求的 `acquire()` 触发；
   只读快照在冷却到期后仍返回 OPEN，且不会占用试探名额，后续首个真实请求照样能试探成功；
2. **不改变之后请求的判定结果**：观测不产生任何状态写入；
3. **不计入失败统计或真实流量统计**：失败计数与 `/admin/traffic` 流量计数只随真实请求变化；
4. **同一时刻反复查结果一致**：健康快照与时间线在同一把锁内复制出来，与状态迁移互斥，
   快照是独立不可变副本，后续状态变化不影响已返回的快照。

每个后端返回的健康对象字段：

| 字段 | 含义 |
|---|---|
| `backendId` | 后端 id |
| `state` | 当前阶段：`CLOSED`（正常）/ `OPEN`（隔离）/ `HALF_OPEN`（试探中） |
| `failureCount` | 最近连续后端侧失败数；试探成功恢复后清零 |
| `backoffAttempt` | 已隔离轮次：0=首次隔离；每试探失败一次 +1；恢复后清零 |
| `openedAtMs` | 本次隔离开始时间（毫秒，测试可注入手动时钟；未隔离为 0） |
| `cooldownMs` | 本次隔离生效的冷却时长（退避后逐级加长、封顶） |
| `remainingCooldownMs` | 隔离还剩多久（毫秒）；非 OPEN 恒为 0；冷却到期为 0 |
| `probeInFlight` | 是否已有一个试探请求正在执行中 |
| `timeline` | 最近关键状态变化列表（见下） |

`timeline` 每条事件字段：`seq`（全局单调递增序号，从 1 开始）、`timestampMs`（发生时间）、
`from`/`to`（变化前/后阶段）、`reason`（原因）、`detail`（可读细节）。`reason` 取值：

| reason | 含义 |
|---|---|
| `FAILURE_THRESHOLD_REACHED` | 连续失败到阈值，CLOSED→OPEN（detail 含阈值与首次冷却） |
| `COOLDOWN_EXPIRED_PROBE` | 冷却到期抢到试探权，OPEN→HALF_OPEN（detail 含本轮冷却/轮次） |
| `PROBE_SUCCEEDED` | 试探成功恢复承载，HALF_OPEN→CLOSED（失败计数清零） |
| `PROBE_FAILED` | 试探失败重新隔离，HALF_OPEN→OPEN（detail 含下一轮冷却） |
| `MANUAL_RESET` | 管理接口/测试手动复位到 CLOSED |

时间线为有界环形缓冲：只保留最近 `timeline-capacity` 条，写满覆盖最旧；序号单调，
快照按时间正序；并发迁移与并发查询下不丢记录、不重复、不错乱。

```bash
curl http://localhost:8080/admin/circuits          # 简版：{"states":{"cpu-v1-1":"OPEN",...}}
curl http://localhost:8080/admin/backends/health   # 详版：阶段/剩余隔离/试探/时间线
curl -X POST http://localhost:8080/admin/reset     # 复位熔断 + 清缓存 + 解除失败注入（测试用）
```

### 3.4 已完成结果不被改写

服务对每条请求返回独立的不可变 `ScoreResult`。后续请求触发的降级/熔断只影响后续请求，
**不会回头修改任何已经返回的结果**；批量接口逐条处理，已写入结果列表的条目同样不会被改写。

---

## 4. 带缓存的批量评分

### 4.1 缓存键与有效期

- 缓存键 = `命中版本 + 规范化特征向量`；TTL 由 `gateway.cache.ttl-ms` 配置（默认 60s）。
- TTL 内相同请求直接复用，**不重复计算**；仅成功结果入缓存，失败结果不污染后续请求。
- 命中时结果会**重新绑定当前 requestId**，因此不同请求复用同一分数也不会“结果串号”。

### 4.2 配置变更即失效

- 版本升级（注册新版本）或灰度权重调整都会推进**配置纪元（ConfigEpoch）**；
- 纪元推进通过监听器**立即整体清空缓存**（不是惰性），保证新版本/新权重后不会读到旧结果。

### 4.3 并发去重

- 每个缓存键一把锁 + in-flight future：并发相同请求只有**一个线程真正调用后端计算一次**，
  其余线程等待并复用该结果，判定依据标记为 `CONCURRENT_DEDUP`；
- 双检 + 键级锁避免竞态；每个请求拿到绑定自己 requestId 的结果，保证不串号。

### 4.4 批量接口

`POST /score/batch`：结果与请求顺序一一对应，返回 `cacheHits` / `cacheComputed` / `cacheEpoch`。

---

## 5. HTTP 接口

### 评分

| 方法 & 路径 | 说明 |
|---|---|
| `POST /score` | 单条评分，体：`{requestId, requestedVersion, features:[...]}` |
| `POST /score/batch` | 批量评分，体：`{requests:[ ...同上... ]}` |

成功与失败都以 HTTP 200 + 结构化 `ScoreResult` 返回，通过 `success` 与稳定 `errorCode` 区分：

```json
{
  "requestId": "r1",
  "success": true,
  "result": 0.8321,
  "servedVersion": "ml-score-v1",
  "backendId": "cpu-v1-1",
  "fromCache": false,
  "decisionReason": "CANARY_ROUTE(family=ml-score,pinned=false,weight=100)",
  "errorCode": null,
  "errorMessage": null
}
```

### 管理

| 方法 & 路径 | 说明 |
|---|---|
| `POST /admin/families/{family}/weights` | 调整灰度权重（推进纪元、失效缓存） |
| `POST /admin/versions` | 注册/升级新版本并挂内存后端（推进纪元） |
| `GET /admin/traffic` | 观测实际灰度流量、生效权重、纪元 |
| `GET /admin/circuits` | 只读观测各后端熔断阶段 |
| `GET /admin/backends/health` | 只读观测各后端自愈详情（阶段/剩余隔离/试探/时间线） |
| `GET /admin/cache` / `DELETE /admin/cache` | 查看缓存 / 手动清空 |
| `POST /admin/backends/{id}/failure?mode=` | 注入失败模式 |
| `POST /admin/reset` | 复位（测试用） |

---

## 6. 验证方法

### 6.1 运行全部测试

```bash
mvn test
```

测试覆盖五类要求场景（共 27 个用例，均为确定性、无网络依赖的内存测试 + HTTP 集成测试）：

- **灰度切换** `CanaryRoutingTest`：默认 100/0 流量、切到 0/100、显式钉住零权重版本、
  未注册拒绝（不回退）、维度不兼容拒绝、权重调整后缓存失效；
- **后端异常** `BackendResilienceTest`：超时/内部错误/非法请求映射为不同稳定错误码、
  单后端故障降级、非法请求不熔断、熔断 OPEN → 冷却 HALF_OPEN → 恢复 CLOSED、
  已完成结果在后续熔断后保持不变；
- **并发重复请求** `ConcurrentCacheTest`：TTL 内只算一次、32 并发同键只实际计算一次且不串号、
  权重变更/版本升级失效缓存、批量顺序稳定且去重计数正确；
- **自愈恢复** `SelfHealingRecoveryTest`（手动时钟驱动，确定性无 sleep 抖动）：
  冷却到点自动试探并恢复承载/失败计数清零、试探失败冷却按 1s→2s→4s 逐级加长且受上限封顶、
  16 并发下被隔离后端同一时刻只接到 1 个试探请求、其余 15 个请求全部走健康后端；
- **只读观测** `ReadOnlyObservationTest`：冷却到期后高频/并发只读查询不推进恢复、不占试探名额、
  不计流量/失败统计、首个真实请求仍能试探恢复；并发读快照完全一致；时间线有界覆盖最旧、
  序号单调、并发追加 400 条不丢不错；
- **端到端** `GatewayHttpIntegrationTest`：经真实 HTTP 验证以上链路与管理/观测接口。

### 6.2 查看单测日志中的“请求 / 命中版本 / 判定依据”

单测均通过 SLF4J 打印关键日志，例如：

```
[score] request received requestId=r0 requestedVersion=ml-score features=[1.0, 2.0, 3.0]
[score] request resolved requestId=r0 success=true servedVersion=ml-score-v1 backendId=cpu-v1-1
        fromCache=false decisionReason=CANARY_ROUTE(family=ml-score,pinned=false,weight=100) ...
[降级] request=fb-1 命中版本=v1 后端=cpu-v1-2 判定依据=FALLBACK_HEALTHY_BACKEND(...,attempts=[cpu-v1-1:BACKEND_INTERNAL_ERROR:CB_STATE=OPEN]) 分数=...
[并发去重] 线程数=32 实际计算=1 并发合并命中=31 缓存命中=0 后端总调用=1 统一分数=...
```

控制台默认 INFO 级别即可看到；也可只运行某个场景类：

```bash
mvn test -Dtest=CanaryRoutingTest
mvn test -Dtest=BackendResilienceTest
mvn test -Dtest=ConcurrentCacheTest
mvn test -Dtest=SelfHealingRecoveryTest   # 自愈：自动恢复 / 逐级退避 / 并发唯一试探
mvn test -Dtest=ReadOnlyObservationTest   # 只读观测 / 时间线有界
```

### 6.3 手工联调

```bash
mvn spring-boot:run

# 1) 默认走 v1
curl -s -X POST localhost:8080/score -H 'Content-Type: application/json' \
  -d '{"requestId":"a","requestedVersion":"ml-score","features":[1,2,3]}'

# 2) 灰度切 10% 到 v2，再观测流量
curl -s -X POST localhost:8080/admin/families/ml-score/weights \
  -H 'Content-Type: application/json' -d '{"ml-score-v1":90,"ml-score-v2":10}'
curl -s localhost:8080/admin/traffic

# 3) 注入内部错误触发隔离，观察健康详情与时间线
curl -s -X POST "localhost:8080/admin/backends/cpu-v1-1/failure?mode=INTERNAL_ERROR"
for i in 1 2 3 4; do
  curl -s -X POST localhost:8080/score -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"f-$i\",\"requestedVersion\":\"ml-score-v1\",\"features\":[$i,$i,$i]}"
done
curl -s localhost:8080/admin/backends/health   # state=OPEN, remainingCooldownMs, timeline

# 4) 冷却 5s（默认 initial-cooldown-ms）后解除故障，下一个请求即试探并恢复
sleep 5
curl -s -X POST "localhost:8080/admin/backends/cpu-v1-1/failure?mode=NONE"
curl -s -X POST localhost:8080/score -H 'Content-Type: application/json' \
  -d '{"requestId":"recover","requestedVersion":"ml-score-v1","features":[9,9,9]}'
# decisionReason=RECOVERED_AFTER_PROBE，/admin/backends/health 中状态回到 CLOSED、failureCount=0
```

---

## 7. 关键一致性保证小结

1. **结果一致**：评分只取决于版本与特征，灰度/降级/熔断不改变数值。
2. **失败可区分**：未注册、不兼容、非法、超时、内部错误、全不可用分别对应不同稳定错误码。
3. **不静默回退**：未注册或零权重显式请求都有明确行为，绝不偷偷落到默认版本。
4. **变更即失效**：版本升级/权重调整推进纪元并立即清空缓存。
5. **并发只算一次且不串号**：键级锁 + in-flight 去重 + 结果按 requestId 重绑定。
6. **过程可解释**：每个结果都带 `decisionReason`，记录路由权重、降级尝试链、熔断跳过、缓存来源；
   恢复承载标 `RECOVERED_AFTER_PROBE`、隔离跳过标 `ISOLATED_SKIPPED`。
7. **自愈必然发生**：冷却到点后由首个真实请求原子试探，名额在 finally 中幂等释放，
   不会卡死在 HALF_OPEN；试探失败逐级退避（有上限），既不放弃不稳后端也不反复打垮它。
8. **观测严格只读**：健康/流量/缓存查询不推进恢复、不占用试探名额、不计失败与流量统计，
   同一时刻反复查看到一致快照；状态时间线有界覆盖、并发不丢不错。
