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
| `gateway.resilience` | 每后端自愈熔断器（CLOSED/OPEN/HALF_OPEN、逐级退避、唯一试探）、健康快照与有界状态时间线 |
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

### 3.3 隔离、自动恢复与退避（`CircuitBreaker`）

- 连续失败次数达到阈值 `gateway.circuit.failure-threshold`（默认 3）→ **OPEN（隔离）**；
- OPEN 期间快速失败，流量被降级到其它健康后端，尝试链里记录 `CIRCUIT_OPEN_SKIPPED`；
- OPEN 冷却结束后**不需要人工干预**：下一个到达该后端的**真实请求**会原子地抢到唯一试探权，
  熔断器进入 **HALF_OPEN（试探）**，状态时间线记录 `RECOVERY_PROBE_GRANTED`；
- **同一后端同一时刻最多一个试探请求**：试探执行期间到达的其它真实请求一律被拒绝并继续走
  健康后端，尝试链里记录 `RECOVERY_PROBE_IN_FLIGHT_SKIPPED`，不会因为反复试探把虚弱后端打挂；
- 试探**成功** → **CLOSED（恢复）**，该后端重新正常承载流量，结果的 `decisionReason`
  标记为 `RECOVERY_PROBE_SUCCEEDED`（与正常路由/隔离跳过明确可区分），退避计数复位；
- 试探**失败** → 重新 OPEN，且下一次冷却时间逐级加长：

  ```
  nextBackoff = min(currentBackoff * gateway.circuit.backoff-multiplier,
                    gateway.circuit.max-backoff-ms)
  ```

  默认首次冷却 `open-duration-ms=5000ms`、乘数 `2.0`（5s→10s→20s→40s→60s…）、
  上限 `max-backoff-ms=60000ms`；乘数配成 `1.0` 即恒定冷却不退避。
- 请求非法不参与失败统计，避免脏请求打挂熔断器；
- 已返回的 `ScoreResult` 不可变，恢复链路任何后续动作都不会改写历史结果；
  恢复后新请求稳定重新落回该后端（评分只取决于版本与特征，数值不变）。

#### 自愈相关配置

```properties
gateway.circuit.failure-threshold=3      # 连续失败几次后隔离
gateway.circuit.open-duration-ms=5000    # 首次冷却（毫秒）
gateway.circuit.backoff-multiplier=2.0   # 试探失败后冷却退避乘数
gateway.circuit.max-backoff-ms=60000     # 退避上限（毫秒）
gateway.circuit.timeline-capacity=20     # 每后端状态时间线保留条数上限
```

#### 无副作用的安全观测（只读保证）

所有状态/流量/恢复进度查询（`GET /admin/circuits`、
`GET /admin/backends/{id}/health`、`GET /admin/traffic`、`GET /admin/cache`）都是**纯读**：

- 连续查、并发查、高频查都**不会推进也不会打断**恢复流程：冷却结束后若没有真实流量，
  熔断器权威状态仍是 `OPEN`，观测里只把阶段报告为 `ISOLATED_COOLDOWN_DUE`（冷却已到、
  待真实请求触发试探），绝不替它发起试探；
- 查询不改变之后任何请求的判定结果，也不写入状态时间线；
- 查询本身不计入后端失败统计，也不计入灰度真实流量统计（`/admin/traffic` 的计数
  只由 `/score` 真实请求增加）。

#### 状态时间线字段含义

每个后端保留最近 `gateway.circuit.timeline-capacity` 条关键状态变化
（有界环形缓冲，写满覆盖最旧，不会无限增长；追加 O(1)，不拖慢评分主链路）。
`GET /admin/backends/{backendId}/health` 与 `GET /admin/circuits` 返回：

| 字段 | 含义 |
|---|---|
| `state` | 熔断器权威状态：`CLOSED` / `OPEN` / `HALF_OPEN`（只读查询不会改变它） |
| `phase` | 面向运维的阶段：`NORMAL`（正常承载）/ `ISOLATED`（隔离冷却中）/ `ISOLATED_COOLDOWN_DUE`（冷却已到，等真实请求试探）/ `PROBING`（试探请求执行中） |
| `failureCount` | 当前连续后端侧失败计数（成功后归零） |
| `probeAttempt` | 第几次恢复试探（1=首次试探；正常承载阶段为 0） |
| `currentBackoffMs` | 当前隔离冷却时长（已含逐级退避） |
| `remainingIsolationMs` | 隔离剩余毫秒；冷却已到但试探未触发时为 0 |
| `probeEligible` | 冷却是否已结束，下一个真实请求将拿到唯一试探权 |
| `probeInFlight` | 当前是否已有一个试探请求正在执行 |
| `timeline` | 最近的状态变化列表（旧→新） |

`timeline` 每条记录字段：

| 字段 | 含义 |
|---|---|
| `timestampMs` | 变化发生时间（epoch 毫秒） |
| `from` / `to` | 变化前 / 变化后的阶段 |
| `reason` | 变化原因：`FAILURE_THRESHOLD_REACHED`（达到阈值被隔离）/ `RECOVERY_PROBE_GRANTED`（真实请求拿到试探权）/ `RECOVERY_PROBE_SUCCEEDED`（试探成功恢复承载）/ `RECOVERY_PROBE_FAILED`（试探失败重新隔离并退避）/ `MANUAL_RESET` |
| `failureCount` | 发生时连续失败次数 |
| `backoffMs` | 变化后生效的冷却时长（试探失败时即为退避后的新值） |
| `probeAttempt` | 第几次恢复试探 |

观测与复位：

```bash
curl http://localhost:8080/admin/circuits
# {"states":{"cpu-v1-1":"OPEN",...},"backends":{"cpu-v1-1":{"phase":"ISOLATED",
#  "remainingIsolationMs":4821,"currentBackoffMs":5000,"probeAttempt":0,
#  "timeline":[{"timestampMs":...,"from":"CLOSED","to":"OPEN",
#  "reason":"FAILURE_THRESHOLD_REACHED","failureCount":3,"backoffMs":5000,"probeAttempt":0}]}}}

curl http://localhost:8080/admin/backends/cpu-v1-1/health   # 单后端完整恢复进度+时间线
curl -X POST http://localhost:8080/admin/reset  # 复位熔断 + 清缓存 + 解除失败注入（测试用）
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
| `GET /admin/traffic` | 只读观测实际灰度流量、生效权重、纪元（查询本身不计入流量） |
| `GET /admin/circuits` | 只读观测各后端权威状态 + 完整自愈健康视图与状态时间线 |
| `GET /admin/backends/{id}/health` | 只读观测单个后端阶段、剩余隔离时长、试探进度与时间线 |
| `GET /admin/cache` / `DELETE /admin/cache` | 查看缓存（只读）/ 手动清空 |
| `POST /admin/backends/{id}/failure?mode=` | 注入失败模式 |
| `POST /admin/reset` | 复位（测试用） |

---

## 6. 验证方法

### 6.1 运行全部测试

```bash
mvn test
```

测试覆盖四类要求场景（共 27 个用例，均为确定性、无网络依赖的内存测试 + HTTP 集成测试）：

- **灰度切换** `CanaryRoutingTest`：默认 100/0 流量、切到 0/100、显式钉住零权重版本、
  未注册拒绝（不回退）、维度不兼容拒绝、权重调整后缓存失效；
- **后端异常** `BackendResilienceTest`：超时/内部错误/非法请求映射为不同稳定错误码、
  单后端故障降级、非法请求不熔断、熔断 OPEN → 冷却 HALF_OPEN → 恢复 CLOSED、
  已完成结果在后续熔断后保持不变；
- **并发重复请求** `ConcurrentCacheTest`：TTL 内只算一次、32 并发同键只实际计算一次且不串号、
  权重变更/版本升级失效缓存、批量顺序稳定且去重计数正确；
- **自愈与安全观测** `SelfHealingTest`：冷却结束后真实请求自动试探并恢复稳定承载、
  试探失败后冷却按乘数逐级退避且受上限钳制、恢复后退避复位、
  并发 32 准入只有 1 个试探且 16 个真实请求全部被健康后端承接（`RECOVERY_PROBE_IN_FLIGHT_SKIPPED`）、
  冷却窗口内高频并发只读查询不推进/不打断恢复且不写时间线不计流量、
  状态时间线有界（写满覆盖最旧）、有序、并发迁移与并发查询下不丢不乱；
- **端到端** `GatewayHttpIntegrationTest`：经真实 HTTP 验证以上链路、管理/观测接口，
  以及熔断健康视图字段与只读查询无副作用。

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
mvn test -Dtest=SelfHealingTest
mvn test -Dtest=GatewayHttpIntegrationTest
```

自愈/观测场景日志中可以看到每条用例的业务场景与每次状态迁移，例如：

```
[状态迁移] backend=cpu-v1-1 CLOSED->OPEN reason=FAILURE_THRESHOLD_REACHED failures=3 backoff=5000ms
[自动恢复-冷却已到] backend=cpu-v1-1 phase=ISOLATED_COOLDOWN_DUE state=OPEN remaining=0ms
[状态迁移] backend=cpu-v1-1 OPEN->HALF_OPEN reason=RECOVERY_PROBE_GRANTED attempt=1 backoff=5000ms
[状态迁移] backend=cpu-v1-1 HALF_OPEN->CLOSED reason=RECOVERY_PROBE_SUCCEEDED attempt=1
[状态迁移] backend=cpu-v1-1 HALF_OPEN->OPEN reason=RECOVERY_PROBE_FAILED attempt=2 nextBackoff=10000ms
[退避上限] 试探#3 失败 下次冷却=400ms (被上限钳制)
[并发单试探] 32 个并发准入：新增试探=0 试探进行中跳过=32
[只读观测] 冷却窗口内 N 次并发只读查询，状态仍=OPEN/COOLDOWN_DUE，时间线未被观测改变
[时间线有界有序] 30 轮迁移+4 个并发只读线程后保留最近5条（容量5），原因序列=[...]
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

# 3) 注入超时观察降级、隔离与（冷却后）自动恢复
curl -s -X POST "localhost:8080/admin/backends/cpu-v1-1/failure?mode=TIMEOUT"
curl -s localhost:8080/admin/circuits | jq .
curl -s localhost:8080/admin/backends/cpu-v1-1/health | jq .view.phase
# 默认冷却 5s：解除故障后，5s 后的第一个真实评分请求会成为恢复试探并成功
curl -s -X POST "localhost:8080/admin/backends/cpu-v1-1/failure?mode=NONE"
# 等待 5s 后再评分，结果 decisionReason 含 RECOVERY_PROBE_SUCCEEDED；
# 期间无论刷多少次 /admin/backends/cpu-v1-1/health 都不会替它发起试探。
```

---

## 7. 关键一致性保证小结

1. **结果一致**：评分只取决于版本与特征，灰度/降级/熔断不改变数值。
2. **失败可区分**：未注册、不兼容、非法、超时、内部错误、全不可用分别对应不同稳定错误码。
3. **不静默回退**：未注册或零权重显式请求都有明确行为，绝不偷偷落到默认版本。
4. **变更即失效**：版本升级/权重调整推进纪元并立即清空缓存。
5. **并发只算一次且不串号**：键级锁 + in-flight 去重 + 结果按 requestId 重绑定。
6. **过程可解释**：每个结果都带 `decisionReason`，记录路由权重、降级尝试链、熔断跳过、
   恢复试探成功（`RECOVERY_PROBE_SUCCEEDED`）/试探进行中跳过、缓存来源。
7. **自动恢复且不压垮后端**：冷却结束由真实请求自动触发唯一试探；同一时刻同一后端最多一个
   试探在执行，其余流量走健康后端；试探失败冷却逐级退避并受上限钳制，不会无限试探或永久隔离。
8. **观测绝对只读**：状态/流量/恢复进度查询不推进、不打断恢复，不改判定，
   不计失败与真实流量统计；高频并发查询看到的可用后端集合/流量/缓存结果保持一致。
9. **状态可追溯**：每后端保留有界、有序、并发安全的状态变化时间线（时间、原因、from→to、
   失败次数、生效冷却、试探序号），写满覆盖最旧，不无限增长。
