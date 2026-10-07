# starter-coord 路线图（ROADMAP）

> 本文件是 `starter-coord` 一族的**滚动计划**：分期主线、Phase 0 决议与约定、契约对齐、
> 测试策略。分期编号（Phase 0 / 1 / 1.5 / 2 / 3 / 4）与
> [seedj ADR-0001](../../docs/adr/0001-coord-starter-spring-cloud-replacement.md) 对齐。
> 架构约束与决策（定位、零 Spring Cloud 硬约束、能力边界、语义基线）见 seedj ADR-0001；
> 模块现状（已实现 / 未实现）见 [README.md](./README.md)。
>
> 契约目标日期以 coord-java-sdk 内嵌契约头部声明为准（状态表：`apis/contracts/STATUS.md`），
> 随 SDK 版本升级滚动修订。

## Phase 0（先行）：决议与约定固化 — 已完成（2026-10-07）

先行阶段不产出运行时代码，只产出决议与约定，作为后续阶段的验收前置。
**约定未固化，不得进入对应阶段的封装实现**（seedj ADR-0001 §决策 7）。
本节所列决议与约定均已关闭，作为各阶段实现依据；修订走常规 PR 评审并更新本节。

### 0.1 首批决议项 — 已关闭（2026-10-07）

| # | 决议项 | 结论 |
| --- | --- | --- |
| R1 | 基线关闭 | 以仓库现状为对外基线：**Spring Boot 4.1.1 + Java 21（Maven）**；口头基线（Java 25 / Boot 3.x）不再作为目标；升级基线另立 ADR |
| R2 | bridge 投放 | **v1 不投放** `starter-coord-springcloud-bridge`；试点按调用图闭包选择实例范围；互操作刚需出现时重新立项 |
| R3 | 运行期刷新 v1 深度 | **`@ConfigurationProperties` 级 rebind**（destroy + initialize，对齐业界 rebind 语义）；bean 级重建不进入 v1（另立 ADR） |

> 决议同步落于 [seedj ADR-0001](../../docs/adr/0001-coord-starter-spring-cloud-replacement.md)
> 「决议结果」节。顺序要求（R1 最先、R2 先于试点方案编写）已随关闭自然满足。

### 0.2 约定清单 — 已定稿（2026-10-07）

#### C1 实例身份与 metadata schema（Phase 1 注册 / 发现）

- **service_name**：`byteforce.coord.registry.service-name`，默认 `spring.application.name`；
  两者皆空 → 自动注册**禁用**并 WARN（不阻塞启动）；
- **instance_id**：`byteforce.coord.registry.instance-id`，默认 `{hostname}:{port}`。
  同键重复注册 = 覆盖续约（契约幂等，集成套件实证），重启可安全复用；
- **metadata**：JSON 字符串（契约建议、服务端不解析）：

  | 键 | 必填 | 说明 |
  | --- | --- | --- |
  | `address` | 自动注册必填 | `host:port`（消费端解析 host / port） |
  | `protocol` | 否 | 默认 `http`（LB 改写 scheme 的依据） |
  | `weight` | 否 | 默认 100；v1 LB 不参与选择（预留） |
  | `version` / `zone` | 否 | 业务可用；starter 不消费 |
  | 其他 | 否 | `byteforce.coord.registry.metadata.*` 属性透传附加 |

- **TTL**：`byteforce.coord.registry.ttl`（默认 30s，秒级整数传给契约）；心跳由 SDK
  `Registration` 维护；注册视角地址默认取 `WebServerInitializedEvent` 端口 + 本机主机名，
  `byteforce.coord.registry.host` 可覆盖（NAT / 容器场景）。

#### C2 配置 key 布局与删除语义（Phase 1 配置）

- key 为扁平字符串；建议布局 `<application>.<profile?>.<业务键>`；
- **导入**：`spring.config.import=coord:<prefix>[;<prefix>]`（框架级「逗号」分隔多个导入项，
  同一项内多前缀用「分号」，如 `coord:a;b` 或 `coord:a,coord:b`）；prefix 仅作
  过滤（`list(prefix)` 语义），暴露到 Spring 的属性名 = **存储 key 原文**（不剥离 / 不重写）；
- **optional**：`optional:coord:` 时启动期 Agent 不可达 / 鉴权失败 → 跳过 + WARN；
  非 optional 一律 fail-fast（§0.3）；
- value 为纯 string；空字符串按普通值处理，**不表示删除**；
- **删除语义**（暂定，待上游核实）：Watch 事件 `new_value` 缺省 → 从属性源移除该 key；
  契约补充删除 / tombstone 面后修订本约定；
- **刷新**（R3）：watch(prefix) → 变更合并入可变属性源 → `@ConfigurationProperties` 级
  rebind → 发布 `CoordConfigRefreshedEvent`；**`@Value` 不参与刷新**（迁移指引：动态
  配置一律使用 `@ConfigurationProperties`）；刷新仅作用于 import 引入的属性源
  （v1 无 refresh-only 模式）；rebind 失败保留现值 + ERROR 日志并继续观察。

#### C3 锁租约与持有者规范（Phase 2 锁）

- 凭据 `(holder_id, lease_id)`；`holder_id` 默认
  `{spring.application.name ?: "app"}:{hostname}:{pid}`（`byteforce.coord.lock.holder-id` 可覆盖）；
- 专用 API `CoordLockService.acquire(name, ttl, waitTimeout)` → 租约对象（获取 / 续约 /
  释放 / 失锁回调）；看门狗续约周期 = `ttl/3`（下限 1s）；
- **不实现** `java.util.concurrent.locks.Lock`、不模拟可重入；续约失败或外部释放 →
  标记失锁 + `CoordLockLostListener`；默认 TTL 30s、默认等待 0；
- fencing 强度（`lease_id` 单调性）以契约为准，对外承诺前与上游核实；v1 仅透出凭据。

#### C4 限流配额来源（Phase 2 限流）

- 契约仅 `Allow(key, permits)`，**无规则下发面**：starter 不定义、不管理配额；配额由
  Agent 侧配置 / 业务管理面维护；
- starter 提供 `CoordRateLimiter`（专用 API）与 `@CoordRateLimit`（可选 AOP：按方法声明
  限流键与许可数）；语义为**按实例（Agent 本地）**，不承诺集群级限流。

#### C5 fail-fast / 降级行为矩阵（定稿）

见 §0.3。

#### C6 LB 实例来源与缓存策略（Phase 3）

- 实例来源：`Registry.discover`（线性一致）；**不含健康标记**——不做主动探活，剔除
  只基于租约活性 + 消费端失败反馈（可选熔断状态观察桥）；
- 缓存：每服务 TTL 缓存（`byteforce.coord.client.discovery.cache-ttl`，默认 30s）；
  Agent 不可达时在陈旧窗口（`max-stale`，默认 5m）内降级返回陈旧实例（WARN），
  超窗抛错；Agent 恢复后自动回源；
- 选择策略：`round-robin`（默认）/ `random`；`weight` 预留不参与（v1）；
- 服务名口径：`http://<service-name>/...` 的 host 为服务名（不含 `.`）；命中不到
  实例即抛 `CoordNoAvailableInstanceException`（不静默直连）。

#### C7 测试策略（定稿）

见「测试策略」节（T1–T4）。

### 0.3 fail-fast / 降级行为矩阵（定稿）

| 能力 | Agent 不可达 / 重启 / 网络分区时的行为 | 依据 / 备注 |
| --- | --- | --- |
| 启动期配置（`spring.config.import=coord:`，非 optional） | **fail-fast**：配置来源不可用即启动失败，禁止静默用错配置启动 | ConfigData 路径 |
| 启动期配置（`optional:coord:`，显式） | 跳过该来源 + WARN，以其余配置启动 | 显式选择，不视为静默降级 |
| 自动注册 | 可降级：不阻止启动，退避重试（1s→30s 封顶）+ WARN；实例在 TTL 内仍可被发现 | 契约租约语义；TTL 见 C1 |
| 发现消费 | 缓存降级：TTL（30s）内直读；过期后探测失败在 max-stale（5m）内返回陈旧值 + WARN；超窗抛错 | C6 |
| 运行期刷新（watch 断流） | 保留最后已知值 + WARN + 自动重订阅；不阻塞运行 | config Watch 语义 |
| 运行时原语（锁 / 限流 / ID / 选举等） | 调用即抛错——库不吞错、不模拟本地语义降级；是否按业务降级由调用方决定 | 契约语义 |
| 客户端负载均衡 | 无可用实例：抛 `CoordNoAvailableInstanceException`（不静默直连、不使用超窗缓存） | C6 |
| 事件 / MQ | 按契约传播错误：Event 断线窗口不补投；MQ 唯一 at-least-once 承诺路径为 Poll+Ack | 契约边界 |

### 0.4 LB 实例来源与健康口径（事实依据，C6 已据此定稿）

- registry 契约（v1.1）只承诺注册 / 心跳 / TTL 与 Discover / Watch：
  `ServiceInstance{instance_id, service_name, metadata}` **不含健康标记**；
  不健康实例的收敛路径是「心跳停止 → 租约过期 → 移除事件」。
- 因此 starter 侧 LB **不得按 Spring Cloud LoadBalancer 的习惯语义想象**（自带健康检查 /
  主动探活）；实例剔除策略只能基于：注册租约活性 + 消费端自身的失败反馈与重试。
- 缓存：agent 侧有本地缓存；agent 不可达时，starter 是否需要二级缓存、其 TTL 与过期行为
  在 C6 中定稿（与 §0.3「发现消费」行联动）。

## 分期主线（2026-10-07 全部落地）

模块地图（实际落地）：

| 模块 | 职责 | 状态 |
| --- | --- | --- |
| `starter-coord` | 接线层（客户端 Bean / 生命周期 / 健康检查）+ Phase 2 封装（锁 / 限流） | 已交付 |
| `starter-coord-registry` | 自动注册 / 注销；发现消费（`CoordDiscoveryClient` + TTL 缓存降级） | 已交付 |
| `starter-coord-config` | 启动期 `spring.config.import=coord:`（ConfigData resolver / loader）+ 运行期刷新 | 已交付 |
| `starter-coord-client` | 客户端负载均衡 + 声明式 HTTP（`RestClient` / `WebClient` + `@HttpExchange`）；熔断状态观察桥 | 已交付 |
| `starter-coord-observability` | `ObservabilityProvider` → Micrometer（RPC 时延 / 成败、Watch 事件、连接状态） | 已交付 |
| `starter-coord-it` | coord 集成套件（Testcontainers 真实环境；全部 `*IntegrationTest`） | 持续扩展 |

- **Phase 1**（注册 / 发现 / 配置 bootstrap）：**已落地**——registry 自动注册（实例身份 /
  metadata / TTL / 优雅注销）与发现消费（native API + 缓存）；config 启动期经
  `META-INF/spring.factories` 注册 `ConfigDataLocationResolver` / `ConfigDataLoader`，支持
  `spring.config.import=coord:`；
- **Phase 1.5**（运行期刷新）：**已落地**（R3 = `@ConfigurationProperties` 级）——
  watch → 可变 `CoordPropertySource` → rebind（destroy + initialize，对齐业界 rebind 语义）
  → 发布 `CoordConfigRefreshedEvent`；rebind 失败保留现值并记录 ERROR；**`@Value` 不参与
  刷新**（文档明示）；bean 级重建另立 ADR；
- **Phase 2**（锁 / ID / 限流）：**已落地**——`CoordLockService`（专用 API + 看门狗续约 +
  失锁回调；不实现 `Lock`、不模拟可重入）；ID 维持 SDK 直用（无封装价值；契约语义护栏
  测试覆盖）；`CoordRateLimiter` 专用 API + `@CoordRateLimit` 可选 AOP（按实例语义）；
- **Phase 3**（LB / 声明式 HTTP / 熔断观察）：**已落地**——`CoordLoadBalancer`
  （round-robin / random；TTL 缓存 + 陈旧窗口降级）；`@HttpExchange` 客户端工厂基于框架内置
  `HttpServiceProxyFactory`（RestClient 常驻；WebClient 条件启用）；熔断状态观察桥把调用
  成败上报 coord（可选开关）。客户端弹性（超时 / 降级）维持 Resilience4j 由**应用侧组合**
  （starter 提供 RestClient 定制扩展点与配方文档，不内置 Resilience4j 依赖、不重造）；
- **Phase 4**（缓存 / 选举 / 功能开关 / 工作流 / 调度）：按实际诉求封装（客户端 Bean 已可
  直用；声明式注解封装 2027-03 前不承诺，不排期）。

模块切分按「第三方依赖隔离与启动阶段注册边界」（`starter-coord-config` 承载
spring.factories 注册边界；`starter-coord-observability` 隔离 micrometer 依赖；
`starter-coord-client` 隔离 spring-webflux 可选依赖），不按「连接数」切分。

## 契约对齐（目标日期）

coord-java-sdk 0.2.0 内嵌契约头部声明（全部 COMMITTED；以 `apis/contracts/STATUS.md`
最新版为准）：

| 阶段 | 依赖契约（目标日期） | 关键路径 / 说明 | 若延迟的应对 |
| --- | --- | --- | --- |
| Phase 1 自动注册 / 发现 | registry（2026-10-31） | 目标日期最近，可先行动 | 整段顺延；试点范围按已交付能力裁剪 |
| Phase 1 配置 bootstrap | config（2026-12-31） | 与 registry 并行开发；bootstrap 不依赖 Watch | 启动期 + 运行期一并顺延；优先交付装配骨架与测试护栏 |
| Phase 1.5 运行期刷新 | config（2026-12-31） | 依赖 config Watch 语义 | 维持启动期刷新语义，不引入临时替代方案 |
| Phase 2 锁 / ID / 限流 | lock（2026-11-30）、idgen（2026-10-31）、ratelimiter（2026-12-31） | 各自独立，可按就绪先后裁剪 | 按契约可用性逐项启用（idgen 最先）；未就绪项不进试点 |
| Phase 3 负载均衡 / 声明式 HTTP | registry（复用）、circuitbreaker（2026-12-31，观察桥） | LB 依赖 Phase 1 的实例消费 | 观察桥随 circuitbreaker 日期顺延 |
| Phase 4 缓存 / 选举 / 功能开关 / 工作流 / 调度 | cache / featureflags（2026-12-31）、election（2026-11-30）、workflow / scheduler（2027-03-31） | 按实际诉求封装 | 2027-03 前不承诺；按诉求排先后 |

其余 COMMITTED 契约（event / mq / pki / policy / transit）随对应能力场景并入相应阶段。
以上为 SDK 0.2.0 快照；SDK 升级时先核对 STATUS.md 再修订本表（与 §测试策略 T3 联动）。

## 测试策略（定稿）

| # | 类别 | 内容 | 归属 |
| --- | --- | --- | --- |
| T1 | 契约语义护栏 | 对契约明文的非常规语义写显式断言（防语义漂移）：锁不可重入、限流 / 熔断为 agent 本地态（重启重置、不跨 agent）、Event 断线不补投、scheduler result 不留档、MQ 仅 Poll+Ack 为 at-least-once 承诺路径、`@Value` 不参与刷新 | 各能力模块单测 + coord-it 集成套件（逐阶段累加） |
| T2 | 异常路径 | agent 不可达 / watch 断流 / 注册冲突 / 租约丢失 / 无可用实例 / optional 配置缺失；语义分支由单测覆盖，可稳定复现的集成路径由 coord-it 覆盖 | 持续补齐（清单随套件维护） |
| T3 | 版本兼容 | starter × coord-java-sdk 版本兼容矩阵（当前基线 SDK 0.2.0）；SDK 升级触发全量回归；矩阵维护责任：starter/coord 维护者 | 持续（SDK 发版触发） |
| T4 | 套件归属 | 全部 `*IntegrationTest` 位于 `starter/coord-it`（`mvn -pl starter/coord-it -am verify -Pit`）；单元测试位于各模块（`mvn verify`，无需 Docker） | 持续 |

## 修订记录

| 日期 | 修订 |
| --- | --- | --- |
| 2026-10-07 | 初稿（自 seedj ADR-0001 拆分：分期与前置、契约对齐、测试策略；含首轮评审意见——Phase 0 首批决议项、fail-fast / 降级矩阵、LB 实例来源与缓存口径、测试策略三类） |
| 2026-10-07 | 全部落地轮：Phase 0 决议关闭（R1–R3）与 C1–C7 定稿；§0.3 矩阵定稿；分期主线标注落地状态与模块地图；测试策略增 T4（套件迁至 `starter/coord-it`） |
