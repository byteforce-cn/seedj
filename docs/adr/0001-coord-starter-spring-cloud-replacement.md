# ADR-0001：coord-starter 承接 Spring Cloud 替代的架构约束

| 项 | 值 |
| --- | --- |
| 状态 | Accepted（已采纳；2026-10-07 落地轮关闭 Phase 0 首批决议） |
| 日期 | 2026-10-07 |
| 决策人 | （待填写） |
| 关联 | [`starter/coord/README.md`](../../starter/coord/README.md)；[`starter/coord/ROADMAP.md`](../../starter/coord/ROADMAP.md)（分期 / 契约对齐 / 测试策略）；`bom/pom.xml`；coord-java-sdk 0.2.0 内嵌契约（`apis/contracts/STATUS.md`） |

## 背景与问题

1. **目标**：以 coord 为协调面，在 seedj 中发展 `starter-coord` 一族，对标 Spring Cloud
   的能力版图，但**不引入任何 Spring Cloud 依赖**（`org.springframework.cloud:*`）。

2. **现状（已实现）**：
   - `starter/coord` 已落地接线层：`CoordClient` + 17 个子客户端 Bean、生命周期托管
     （`destroyMethod=close`）、可选健康检查、可选启动等待（`await-connected-timeout`）、
     CCT 凭据扩展点；配套 7 类 20 例基于真实 coord 环境的 Testcontainers 集成验证。
   - 仓库基线：**Spring Boot 4.1.1 + Java 21（Maven）**，版本由 BOM 统一管理；
     当前各模块 POM 中零 spring-cloud 引用。
   - coord 上游明确不提供官方 starter（产品决策），官方 recipe 仅
     `@Bean(destroyMethod="close")` + `CoordClient.create`——语义治理责任在消费方（本仓库）。

3. **约束与关键事实**：
   - coord-java-sdk 0.2.0 的 **16 项对外契约均为 COMMITTED（承诺中）**，目标日期
     2026-10-31 ~ 2027-03-31（以 `apis/contracts/STATUS.md` 为准）；设计必须依据契约内的
     「语义承诺 / 边界声明」，不得按 Spring Cloud 同名组件的习惯语义想象。
   - 契约明示的关键语义：锁**不承诺可重入与公平**；限流（令牌桶）与熔断状态为
     **Agent 本地内存**、不跨 Agent、重启即重置；调度任务 `result` 不留档；
     Event 断线窗口**不补投**；MQ 唯一 at-least-once 承诺路径为 `Poll+Ack`
     （推送订阅为 best-effort）。
   - 纯 Spring Boot（4.1.1）**没有**运行期动态配置刷新设施：`RefreshScope` /
     `RefreshEvent` / `EnvironmentChangeEvent` / `ConfigurationPropertiesRebinder`
     均属 spring-cloud-context；Boot 的 ConfigData 注册机制为 `META-INF/spring.factories`。
   - **基线差异**：仓库现状（Spring Boot 4.1.1 / Java 21，见 `bom/pom.xml`）与既有
     口头基线（Java 25 / Boot 3.x）不一致，影响所有模块 POM、Testcontainers 镜像与
     SDK 兼容性——列为 Phase 0 **首个决议项**（见 ROADMAP §0.1 R1）。

4. **问题**：对「实现 `DiscoveryClient`、发布 `RefreshEvent`、保留 Spring Cloud
   LoadBalancer / Gateway 等」的提案评估后确认，该路线与「零 Spring Cloud 依赖」硬约束
   直接冲突，且对动态刷新、熔断能力的判断存在事实性错误。需要以 ADR 固化修正后的
   方向、边界与分期。

## 决策

1. **定位**：`starter-coord` 一族定位为 **coord 的 Spring 接入层（协调平面 → 接入平面）**，
   不做 Spring Cloud 能力的一对一翻译；承接协调面能力（注册发现、配置、锁、ID、选举、
   缓存、功能开关；限流按「按实例」语义），不复制整个 Spring Cloud 生态。

2. **零 Spring Cloud 依赖为硬约束**：主件及后续模块不得依赖 `org.springframework.cloud:*`；
   **不得以 Spring Cloud 接口作为实现面**——`DiscoveryClient` / `ServiceInstance`
   （spring-cloud-commons）、`RefreshScope` / `RefreshEvent`（spring-cloud-context）、
   `ServiceInstanceListSupplier`（spring-cloud-loadbalancer）、OpenFeign、
   Spring Cloud Gateway 均不在 native 路径上。

3. **互操作双轨**：迁移期如确需与仍运行在 Spring Cloud 上的应用互操作，只允许投放
   **独立、`optional` / `provided`、不进主依赖链**的 bridge 构件
   （如 `starter-coord-springcloud-bridge`）；bridge 的投放与否、形态与时点提升为
   **Phase 0 首批决议项**——先于试点方案（不投放则试点必须按调用图闭包选择，
   见 ROADMAP §0.1 R2）。

4. **动态配置分两段交付**：
   - 启动期：`ConfigDataLocationResolver` / `ConfigDataLoader`，经
     `META-INF/spring.factories` 注册，支持 `coord:` 前缀的配置导入（`spring.config.import`）；
   - 运行期：**自研刷新机制**（watch → 可变 PropertySource → rebind），不发布、不依赖
     Spring Cloud 的 `RefreshEvent`；**v1 刷新深度已确认（R3，2026-10-07）**：为
     `@ConfigurationProperties` 级 rebind；bean 级重建属自研 refresh scope 范畴，
     另立 ADR 评估；实现要点（rebind 失败回滚、依赖 Bean 重建顺序、`@DependsOn` 链、
     可变 PropertySource 优先级位置）见 ROADMAP 分期主线（Phase 1.5）。
   - **v1 明确不支持 `@Value` 注入字段的动态刷新**（纯 Boot 无该通路，字段仅首次解析
     取值）；动态配置一律使用 `@ConfigurationProperties`，迁移指引为 Phase 1.5 交付物。

5. **能力边界（做 / 自建 / 不重造 / 不做）**：
   - 做：注册与发现的**自动注册/注销**（实例身份、metadata schema、TTL、优雅注销）
     及消费端 API；配置；锁；ID；选举；缓存；功能开关；
   - 自建：客户端负载均衡与声明式 HTTP 客户端（基于 Spring Framework
     `RestClient` / `WebClient` + `@HttpExchange`；`@HttpExchange` 代理工厂直接使用
     框架内置的 `HttpServiceProxyFactory`，不自研声明式客户端替代品；不使用 Feign
     概念或接口）；
   - 实例来源与健康口径：registry 契约（v1.1）只承诺注册 / 心跳 / TTL 与
     Discover / Watch，`ServiceInstance` **不含健康标记**——LB 的实例筛选、健康剔除与
     agent 不可达时的缓存降级策略在 Phase 0 定稿（ROADMAP §0.4 与约定 C6），不得按
     Spring Cloud LoadBalancer 的习惯语义想象；
   - 不重造：客户端熔断 / 超时 / 降级维持 Resilience4j（非 Spring Cloud 组件）；
     coord 熔断器仅作状态协调与观测；
   - 不做（不在 starter 承诺面）：API 网关（平台/运维层另行规划）；
     链路追踪维持 Micrometer Tracing + OTLP，不引入 Spring Cloud。

6. **语义以契约为准**：
   - 锁：**不实现** `java.util.concurrent.locks.Lock`、不模拟可重入；提供专用 API
     （获取 / 续约 / 释放 / 失锁回调）+ 看门狗续约，暴露 `(holder_id, lease_id)`
     持有者凭据；fencing 语义强度（如 `lease_id` 单调性）以契约为准，落地前与上游核实；
   - 限流 / 熔断：明确「按实例（Agent 本地）」语义，不承诺替代集群级限流；
     配额 / 阈值的规则来源与下发方式必须先定义（契约当前无规则下发面）；
   - 调度 / 事件 / MQ：按契约边界设计（`result` 不留档、不补投、`Poll+Ack`
     为承诺路径），文档与 API 不得承诺契约之外的语义。

7. **分期与前置**：分期主线、Phase 0 决议 / 约定清单、契约对齐（目标日期）与测试策略
   统一维护于 [`starter/coord/ROADMAP.md`](../../starter/coord/ROADMAP.md)，本 ADR 不复制
   （避免滚动内容导致 ADR 频繁修订）。本 ADR 固化三条原则：
   - 约定（实例身份与 metadata schema、配置 key 布局与删除语义、锁租约 / 持有者规范、
     限流配额来源、fail-fast / 降级行为矩阵、LB 实例来源与缓存、测试策略）未固化，
     不得进入对应阶段的封装实现；**基线关闭、bridge 投放、v1 刷新深度为 Phase 0
     首批决议项**（见 ROADMAP §0.1）；
   - 每阶段复用并扩展现有 Testcontainers 集成套件（含契约语义护栏与异常路径用例）；
   - 模块切分按「第三方依赖隔离与启动阶段注册边界」（如 `starter-coord-config`、
     `starter-coord-registry`、`starter-coord-client`、`starter-coord-observability`），
     不按「连接数」切分。

8. **立项叙事**：以「收敛双套协调面的运维成本、自主可控」为纲；
   不以「Spring Cloud 将退场」作为技术论据。

## 备选方案与驳回理由

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| A：以 Spring Cloud 接口实现替换（`DiscoveryClient`、`RefreshEvent`、`ServiceInstanceListSupplier`、保留 SCG） | 驳回 | 与「零 Spring Cloud 依赖」硬约束直接冲突；本质是「把 coord 接到 SC 背面」的适配路线，与收敛治理面的目标相反 |
| B：Spring Cloud 能力一对一翻译（每个 SC 组件对应一个模块） | 驳回 | coord 与 SC 架构底色不同（Agent 本地协调 vs 客户端自治组件），翻译式设计会把错误语义固化 |
| C：维持现状、不做扩展 | 驳回 | 不满足注册 / 配置 / 原语等实际治理诉求，无法收敛双套协调面 |
| D：自研全量客户端熔断器 | 驳回 | 客户端弹性已有成熟非 SC 方案（Resilience4j），重造成本高、收益低 |

## 后果

- **正面**：依赖零 Spring Cloud；语义与 coord 契约对齐；复用既有接线层与真环境集成套件；
  迁移与治理叙事清晰。
- **可验证收益项**（评审口径；数量以试点基线对账为准）：
  - 组件面：SC 侧协调组件与告警规则下线（试点前后清单对账）；
  - 排障面：跨套件排查收敛为单一 agent + starter 观测面（故障演练验证）；
  - 一致性：双注册 / 双写风险消除（试点验收项）；
  - 治理面：CCT 凭据与 TTL 生命周期单点化（配置清单对账）。
- **负面 / 成本**：运行期动态刷新需自研（最大不确定项；实现要点：rebind 失败回滚、
  依赖 Bean 重建顺序、`@DependsOn` 链、可变 PropertySource 优先级位置，清单见 ROADMAP
  分期主线 Phase 1.5）；负载均衡与声明式 HTTP 客户端需自研；bridge 投放前，
  与仍运行在 Spring Cloud 上的应用无法互操作。
- **风险**：
  1. **迁移双平面**：混合期 coord 侧应用无法解析仅注册在 Eureka/Nacos 的服务——
     试点须按调用图闭包选择，或迁移期双注册 / bridge，须在试点方案中显式解决；
     bridge 投放决策已提升为 Phase 0 首批决议项（先于试点方案）；
  2. **契约交付节奏**：16 项契约目标日期均在 2026-10 之后（最近 2026-10-31：
     registry / idgen，最远 2027-03-31：workflow / scheduler），阶段排期需与 agent
     实际交付版本对齐（`STATUS.md`）；阶段 × 契约对齐表见 ROADMAP 契约对齐；
  3. **语义预期差**：限流 / 熔断「按实例」与 Sentinel 集群语义、锁「不可重入」与
     `ReentrantLock` 使用习惯、**`@Value` 不参与动态刷新**与 Spring Cloud 习惯的
     预期差，需通过文档与 API 设计显式传达；
  4. **Agent 单点**：每机一个 agent，所有能力依赖其可用性——fail-fast / 降级策略
     需按能力分别定义（现有健康检查与启动等待是基础；行为矩阵为 Phase 0 产物，
     见 ROADMAP §0.3）。

## 决议结果（Phase 0 首批决议，2026-10-07 关闭）

| # | 决议项 | 结论 |
| --- | --- | --- |
| R1 | 基线关闭 | **以仓库现状为对外基线：Spring Boot 4.1.1 + Java 21（Maven）。** 口头基线（Java 25 / Boot 3.x）不再作为目标；后续如需升级基线，另立 ADR 评估（影响面：全模块 POM、Testcontainers 镜像、SDK 兼容矩阵） |
| R2 | bridge 构件 | **v1 不投放 `starter-coord-springcloud-bridge`。** 试点方案按调用图闭包选择实例范围；当试点确因与 Spring Cloud 应用互操作受阻时，重新立项评估 bridge（形态与时点届时另议） |
| R3 | 刷新 v1 深度 | **确认 `@ConfigurationProperties` 级 rebind 为 v1 深度**（已同步 §决策 4）；bean 级重建不进入 v1，另立 ADR |

Phase 0 约定 C1–C7 已定稿，见 [`starter/coord/ROADMAP.md`](../../starter/coord/ROADMAP.md) §0.2
（含 fail-fast / 降级矩阵 §0.3、LB 实例来源与缓存 C6、测试策略）。

仍开放：各约定的**维护者与评审 / 变更流程归属**（现行约定：由 starter/coord 维护者随
SDK 版本升级滚动修订，变更走常规 PR 评审；如需组织级会签再行补充）。

## 参考

- [`starter/coord/README.md`](../../starter/coord/README.md)：已实现 / 未实现清单、
  集成验证与上游边界说明；
- [`starter/coord/ROADMAP.md`](../../starter/coord/ROADMAP.md)：分期主线、Phase 0 决议与
  约定、契约对齐（含目标日期）、测试策略；
- coord-java-sdk 0.2.0 内嵌契约：`lock` / `ratelimiter` / `circuitbreaker` /
  `registry` / `config` / `event` / `mq` / `scheduler` / `election` / `idgen` 等
  `*.proto` 的「语义承诺 / 边界声明」，各契约头部标注「状态 + 目标日期」；
  状态表 `apis/contracts/STATUS.md`；
- Spring Boot 4.1.1 实证：ConfigData 注册机制为 `META-INF/spring.factories`；
  Boot / Framework 不含 Spring Cloud 动态刷新设施；
- 上游 `docs/production/ops/boundaries.md`（边界声明体系，如 B-SE-3）。

## 修订记录

| 日期 | 修订 |
| --- | --- |
| 2026-10-07 | 初稿（基于同日「业务架构师提案评估」结论修订） |
| 2026-10-07 | 按首轮评审意见修订（评审轮；决策结论未变）：基线关闭 / bridge 投放 / v1 刷新深度提升为 Phase 0 首批决议项；补 `@Value` 不支持动态刷新的声明与迁移口径、LB 实例来源与健康口径、`HttpServiceProxyFactory` 说明、可验证收益项；分期 / 契约对齐 / 测试策略拆分至 [`starter/coord/ROADMAP.md`](../../starter/coord/ROADMAP.md) |
| 2026-10-07 | 全部落地轮：状态 Proposed → Accepted；Phase 0 首批决议关闭（R1 基线 = 仓库现状 Spring Boot 4.1.1 / Java 21；R2 v1 不投放 bridge；R3 v1 刷新深度 = `@ConfigurationProperties` 级 rebind，§决策 4 同步）；C1–C7 约定定稿（ROADMAP §0.2） |
| 2026-10-07 | 落地实施：`starter-coord-config` / `-registry` / `-client` / `-observability` 四模块与 Phase 2 封装（`CoordLockService` 看门狗续约 / 失锁回调；`CoordRateLimiter` + `@CoordRateLimit` AOP）交付；集成套件迁至 `starter-coord-it` 并扩展至 14 类 33 例（含契约语义护栏与 `@Value` 不刷新护栏）；全仓 `mvn -T1C verify` 绿、`-Pit` 真实环境集成跑绿 |
