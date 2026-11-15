# seedj-infra-coord / seedj-workflow-coord 设计

> 状态：Draft（待评审）
> 日期：2026-11-15
> 关联：coord [#375](https://github.com/byteforce-cn/coord/issues/375)–[#382](https://github.com/byteforce-cn/coord/issues/382)

## 1. 意图

新增两个实现模块，把 coord 0.4.2 的 Java SDK 接到 seedj 的基础设施 SPI 上：

- `seedj-infra-coord`：ID、编号、全局序列、EventBus 投递、锁、PKI、Transit、EventBus 消费。
- `seedj-workflow-coord`：`WorkflowProvider` 的 coord 适配器。

模块与 `seedj-infra-local` / `seedj-workflow-local` 同定位：只交付库与 Spring 自动配置，不启动 server、不产出可执行制品。业务代码仍然只依赖 `core` 的契约，不 import `cn.byteforce.seedj.infra.coord.*`。

## 2. 目标、非目标与反目标

### 2.1 目标

1. coord 作为 seedj 服务在**分布式部署**下的基础设施后端：一个 `CoordClient`、一套配置、按能力选择 provider。
2. 每个交付的能力**通过 `core` 的契约 TCK**；没有“半成品 provider”进入主线。
3. 多个 seedj 实例共享同一 coord 集群时，锁、ID、事件、证书、工作流语义正确。
4. 与 `seedj-infra-local` 可共存：默认仍是本地实现；coord 必须显式开启和选择。
5. coord 侧缺口有可追踪的 issue（#375–#382），seedj 侧按 issue 关闭情况分阶段交付。

### 2.2 非目标

- 不在 seedj 侧重新实现 coord 的协调原语（lease、CAS、日志、选主）。
- 不替换 `seedj-jdbc-postgresql` 的业务持久化（Outbox、命令/事件存储、IAM 存储）。
- 不把 coord 变成业务数据库；`OutboxStore` 的事务原子性依赖业务数据所在的事务，coord 没有这个上下文。
- 不把 `seedj-infra-coord` 做成 coord server 的运维工具或部署单元。
- 不在本设计里修改 coord 的 wire；coord 侧改动由 #375–#382 跟踪。

### 2.3 反目标

- **静默降级**：coord 没有的能力不能假装成功，也不能用本地内存实现悄悄兜底。缺能力时启动失败或 provider 不装配。
- **生产默认开启**：coord 是显式选择；默认仍然是 `seedj-infra-local` / PostgreSQL。
- **核心模块引入 coord 依赖**：`core` 继续零 Spring、零 coord 依赖。
- **复制一套 seedj 侧状态机**：如果 coord 的 API 不足以表达契约，先提 issue，不先写补偿层。

## 3. 约束

### 3.1 仓库约束

- Java 21、Spring Boot 4.1.1；格式由 Spotless（google-java-format）统一。
- `main` 受保护：任何改动走分支 → PR → CI 全绿 → 合并。
- 每能力三件套：契约 TCK + 装配测试 + 集成测试（SPI 宪法 R8）。
- `config/public-api.txt` 是 allowlist；新增 public 类型必须显式登记。
- `config/cognitive-load-baseline.properties` 的 `api.publicTypes` 是硬基线；新增 public 类型需要同步上调并写明理由。
- 架构门禁：`core` 不依赖 Spring/JDBC；实现模块之间不反向依赖。
- 依赖方向：`core` ← `seedj-infra-coord` ← `seedj-workflow-coord`；两个模块都不依赖 `seedj-server-*`。

### 3.2 外部约束

- coord 版本：Java SDK `0.4.2`（本机 Maven 仓库已有；当前 seedj BOM 还停在 `0.3.5`，且无人引用）。
- coord 部署：应用连本机 `coord-agent`（默认 `127.0.0.1:19527`），不直连 server。
- coord 数据面：MQ v2 / KV-T v2 需要 agent `data_plane = "v2"`，且 v2 集群要求 `multi_raft` + `multi_raft.pd`；`coord dev` 默认是 v1，不能用于 MQ v2 的集成测试。
- 生产鉴权/TLS：agent 默认拒绝非 loopback 明文；token 只从环境变量/文件读，不写入仓库。
- coord 缺口：PKI 吊销、Transit 命名密钥、MQ 消费租约、Workflow 查询/动作/判定分别由 #375–#382 跟踪。

## 4. 方案

### 4.1 方案 A：分阶段、TCK 为准（选用）

先落模块骨架、`CoordClient` 装配、错误映射和测试基座；只注册**已经能通过 seedj TCK** 的能力。其余能力等 coord issue 关闭后逐项加入。

- 首发：ID、Numbering、GlobalSequencer、EventBusTransport（发布）。
- 随后按 issue 就绪：EventConsumerStore（#377）、Lock（#379）、PKI（#375）、Transit（#376）、Workflow（#380–#382）。
- 每个阶段一个 PR，能力 bean 只在对应测试全绿后出现。

**代价**：首发模块能力少；大部分价值依赖 coord 团队。**收益**：符合 R8，没有降级语义，review 面小。

### 4.2 方案 B：全量 preview、默认关闭

先把所有适配器写完，统一挂在 `seedj.coord.preview=true` 下，默认不装配；文档标注未达标能力。

**拒绝原因**：主线里存在不满足契约的 provider，一旦有人打开 preview 就会得到静默错误；coord 的 wire 还在按 issue 演进，适配器会反复返工。

### 4.3 方案 C：seedj 侧补偿层

用 coord KV-T / 本地状态补齐 coord 没有的语义（PKI 吊销表、Transit 命名密钥、MQ delivery lease、Workflow 查询索引），让适配器先通过 TCK。

**拒绝原因**：重复实现 coord 的职责，和“让 coord 提供缺失能力”的方向冲突；补偿层会成为第二套需要运维的状态。例外是 Audit/Outbox：它们本来就不是 coord 的原生能力，见 §8.9。

### 4.4 决定

采用方案 A，并按仓库已有的“实现与 workflow 分开”惯例拆两个模块：

```text
core
 ├── seedj-infra-local
 ├── seedj-jdbc-postgresql
 ├── seedj-infra-coord ────── coord-java-sdk
 └── seedj-workflow-coord ─── seedj-infra-coord + coord-java-sdk
```

`seedj-workflow-coord` 依赖 `seedj-infra-coord` 只为了复用 `CoordClient` / 配置 / 错误映射，不复制连接。

## 5. 能力矩阵

图例：✅ 0.4.2 可直接适配；△ 需要少量映射；❌ 被 issue 阻塞。

| 能力 | seedj 契约 | coord 0.4.2 API | 判定 | 阶段 | 阻塞 |
|---|---|---|---|---|---|
| ID | `IdGenerator<String>` / `<Long>` | `IdGenClient.nextId/nextBatch`（分段、趋势递增） | ✅ | 0 | — |
| Numbering | `Numbering.next(scope[, year])` | `IdGenClient.nextId(name)` | ✅ | 0 | — |
| GlobalSequencer | `GlobalSequencer.next()` | `IdGenClient.nextId(name)`（全局单调；不依赖 v2） | ✅ | 0 | — |
| EventBus 发布 | `EventBusTransport.send/ensureTopic/topicExists` | MQ v2 `Publish` + `idempotency_key` + `headers` | ✅ | 0 | — |
| EventBus 消费 | `EventConsumerStore.poll/ack/retry/deadLetter` | MQ v2 offset ack；无投递租约/延迟重试/attempts/过期 ack 围栏 | ❌ | 1 | #377 |
| Lock | `LockProvider.tryAcquire/acquire` | `LockClient.acquire/release/renew`；TTL 只有整秒 | △ | 2 | #379 |
| PKI | `PkiProvider` 全量 | `PkiClient` 无 revoke/serial 状态/CA 导入；SDK 未暴露 SAN | ❌ | 3 | #375 |
| Transit | `TransitProvider` 全量 | 全局 envelope + purpose HMAC；无命名密钥/生命周期/非对称签名 | ❌ | 4 | #376 |
| Workflow | `WorkflowProvider` 全量（33 个 TCK） | branch/round/seat 模型；查询/动作/判定缺口 | ❌ | 5 | #380–#382 |
| Audit | `AuditStore` | 无原生 API；可由 KV-T Txn 组合 | 非目标 | — | — |
| Outbox | `OutboxStore` | 无原生 API；事务原子性不在 coord | 非目标 | — | — |
| IAM / 命令 / 事件存储 | 各自 SPI | 不是协调能力 | 非目标 | — | — |

阶段顺序按 issue 就绪情况调整；只有对应 TCK 全绿的能力才进入主线。

## 6. 模块与包

### 6.1 `seedj-infra-coord`

```text
seedj-infra-coord/
  src/main/java/cn/byteforce/seedj/infra/coord/
    CoordProperties.java              # 连接/命名空间/TLS/auth
    CoordAutoConfiguration.java       # CoordClient + 能力 bean（唯一 public 自动配置）
    CoordProviderGuard.java           # provider=coord 但 coord 未启用时启动失败
    CoordErrors.java                  # CoordException -> 各能力错误码
    id/CoordIdGenerator.java          # Long 生成器（批缓存）
    id/CoordIdAutoConfiguration.java  # 嵌套在 CoordAutoConfiguration 中
    numbering/CoordNumbering.java
    clock/CoordGlobalSequencer.java
    eventbus/CoordEventBusTransport.java
    eventbus/CoordEventBusGuard.java  # 有订阅但无 consumer store 时失败
    lock/CoordLockProvider.java
    pki/CoordPkiProvider.java
    transit/CoordTransitProvider.java
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

- 包名 `cn.byteforce.seedj.infra.coord.*`，与 `infra.local.*` 平行。
- 自动配置类 public（供 `AutoConfiguration.imports` 与门禁登记）；provider 实现类尽量 package-private，减少 public API 面。
- `CoordProperties` 负责连接级配置；各能力的小配置用嵌套 static 类，避免新增 public 类型。

### 6.2 `seedj-workflow-coord`

```text
seedj-workflow-coord/
  src/main/java/cn/byteforce/seedj/infra/coord/workflow/
    CoordWorkflowProperties.java
    CoordWorkflowAutoConfiguration.java
    CoordWorkflowProvider.java
    CoordWorkflowDefinitionMapper.java  # WorkflowDefinition -> coord YAML
    CoordWorkflowModelMapper.java       # branch/round/task -> HumanTask/WorkflowInstance
    CoordWorkflowErrors.java
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

- 依赖 `seedj-infra-coord` 的 `CoordClient`，不重复建连。
- 只在 `seedj.workflow.provider=coord` 时装配。
- 能力声明（`WorkflowCapabilities`）必须与实现一致；全量 TCK 未绿前不发布该 bean。

## 7. 装配设计

### 7.1 `CoordClient` 生命周期

- 一个应用上下文一个 `CoordClient` bean，`@Bean(destroyMethod = "close")`，`@ConditionalOnMissingBean`。
- 由 `CoordProperties` 构造 `CoordConfig`：
  - `agentHost` / `agentPort` / `requestTimeout`
  - `autoRestoreWatches` / `heartbeatThreads`
  - TLS：`useTls` + CA/client cert/key 路径
  - `authTokenSupplier`：优先 `seedj.coord.auth.token`，其次 `seedj.coord.auth.token-file`，再次环境变量 `COORD_AUTH_TOKEN`；三者都没有时返回空（仅适用于鉴权关闭的 dev/测试）
  - `allowInsecurePlaintext`：只在测试/本机 dev 显式开启
- 生产不得把 token 写进配置文件；文档只展示环境变量/secret 文件路径。
- `CoordClient` 关闭由 Spring 负责；各 provider 的 `close()` **不得**关闭共享 client，只关闭自己的执行器/缓存。

### 7.2 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `seedj.coord.enabled` | `false` | 总开关；关闭时不创建 client 与任何 coord provider |
| `seedj.coord.agent-host` | `127.0.0.1` | agent gRPC 地址 |
| `seedj.coord.agent-port` | `19527` | agent gRPC 端口 |
| `seedj.coord.request-timeout` | `5s` | 单次 RPC 超时 |
| `seedj.coord.auto-restore-watches` | `true` | coord SDK 断线恢复订阅 |
| `seedj.coord.heartbeat-threads` | SDK 默认 | 心跳线程数 |
| `seedj.coord.tls.enabled` | `false` | 是否启用 TLS |
| `seedj.coord.tls.ca-cert-path` | — | CA 证书路径 |
| `seedj.coord.tls.client-cert-path` | — | 客户端证书路径 |
| `seedj.coord.tls.client-key-path` | — | 客户端私钥路径 |
| `seedj.coord.allow-insecure-plaintext` | `false` | 仅测试/dev；非 loopback 明文必须显式允许 |
| `seedj.coord.auth.token` | — | 不推荐写配置文件；可来自环境变量 |
| `seedj.coord.auth.token-file` | — | secret 文件路径 |
| `seedj.coord.namespace` | `seedj:${spring.application.name:default}` | ID/编号/锁/工作流命名空间前缀；默认按应用隔离；不影响业务 topic 名 |
| `seedj.coord.instance-id` | `spring.application.name:host:uuid` | 锁持有者标识前缀 |
| `seedj.coord.id.name` | `application` | idgen 业务键；同一应用必须稳定 |
| `seedj.coord.id.batch-size` | `1000` | 客户端预取批大小（1–10000） |
| `seedj.coord.numbering.prefix` | `numbering` | scope 前缀 |
| `seedj.coord.clock.name` | `global-sequencer` | idgen 名称 |
| `seedj.coord.eventbus.partitions` | `4` | 新建 topic 的分区数；已存在的 topic 以实际元数据为准 |
| `seedj.coord.eventbus.producer` | `spring.application.name` | 审计用 producer 标识 |
| `seedj.coord.pki.ca-common-name` | `seedj-ca` | 首次 `initCa` 的 CN |

能力开关与选择分开，避免三套键互相打架：

- **开关**：`seedj.<cap>.enabled`（已有的键沿用，默认值不变）。
- **选择**：`seedj.<cap>.provider`；EventBus 沿用已有的 `seedj.eventbus.transport`。
- 不在 coord 模块引入第二套 `seedj.coord.<cap>.enabled`；`seedj.coord.enabled` 只做“coord 连接是否启用”的总开关。

| 能力 | 选择键 | 本地值 | coord 值 |
|---|---|---|---|
| PKI | `seedj.pki.provider` | `local`（matchIfMissing） | `coord` |
| Transit | `seedj.transit.provider` | `local`（matchIfMissing） | `coord` |
| ID | `seedj.id.provider` | `local`（matchIfMissing） | `coord` |
| EventBus | `seedj.eventbus.transport` | `file` / `memory` | `coord` |
| Lock | `seedj.lock.provider` | `memory`（matchIfMissing，仍受 `seedj.lock.memory.enabled` 约束） | `coord` |
| Workflow | `seedj.workflow.provider` | `memory`（matchIfMissing） | `coord` |
| Numbering | `seedj.numbering.provider` | —（当前没有本地 bean） | `coord` |
| GlobalSequencer | `seedj.clock.provider` | —（当前没有本地 bean） | `coord` |

选择规则：

1. `seedj.<cap>.enabled=false` 关闭能力，provider 不再生效。
2. provider 未写：本地实现按 `matchIfMissing=true` 保持现有行为；Numbering/GlobalSequencer 没有默认 bean，必须显式写 `provider=coord`。
3. provider=coord 且 `seedj.coord.enabled=false`：启动失败（`CoordProviderGuard`）。
4. provider=coord 时，本地条件不匹配，不会出现两个 bean。例如 `seedj.lock.provider=coord` + `seedj.lock.memory.enabled=true` 只会得到 coord bean。
5. 阶段 0 给 `seedj-infra-local` 的 pki/transit/id/lock 补 `provider` 条件（`havingValue="local"` / `"memory"`，`matchIfMissing=true`）；eventbus/workflow 已有选择键，不改语义。

Numbering/GlobalSequencer 的开关键为 `seedj.numbering.enabled` / `seedj.clock.enabled`（默认 `true`），但没有默认 provider；不写 `provider=coord` 就不会有 bean。

provider bean 统一命名为 `coord`（如 `@Bean("coord")`），与 `seedj.<cap>.provider` 的取值一致；这样 R3 的 `Map<String,T>` 选择可用。

### 7.3 守卫与失败模式

1. **provider=coord 但 `seedj.coord.enabled=false`**：`CoordProviderGuard` 在启动时抛 `InfrastructureException(CONFIG_ERROR)`，消息点名冲突的键。不能等到注入时才发现。
2. **`seedj.eventbus.transport=coord` 且有 `EventSubscriber`，但 consumer store 不可用**：启动失败，而不是让消费者容器静默 idle。原因：发布成功但没人消费，是最危险的“看起来正常”。
3. **TLS/鉴权配置不完整**：交给 coord SDK fail-closed；适配器把 `UNAUTHENTICATED` / `PERMISSION_DENIED` 映射为 `CONFIG_ERROR`，不重试。
4. **coord agent 不可达**：`seedj.coord.enabled=true` 表示 coord 是必需依赖，不做“启动降级”。
   - EventBus topic capability 在启动期调用 `ensureTopic`/`topicExists`；agent 不可达时抛 `TRANSPORT_UNAVAILABLE`，required capability 失败 → 启动失败（`NOT_READY`）。
   - ID/Lock/PKI/Transit 的 provider 构造不做网络调用，首次业务调用时才失败；这是“启动期能力”与“惰性能力”的差别，文档要写清。
   - 部署顺序：先保证 coord agent 可达，再滚动 seedj 服务；写进 runbook。

### 7.4 错误映射

统一用 `CoordErrors`，原则：**只读 `CoordException.getErrorCode()`，不解析 message**。

| coord `ErrorCode` | seedj 映射 | 分类 | 可重试 |
|---|---|---|---|
| `INVALID_ARGUMENT` | 能力自己的 `INVALID_*` | `CLIENT_ERROR` | 否 |
| `NOT_FOUND` | `NOT_FOUND` / `UNKNOWN` | `CLIENT_ERROR` | 否 |
| `ALREADY_EXISTS` | 冲突类错误（如 `KEY_ALREADY_EXISTS`） | `CLIENT_ERROR` | 否 |
| `UNAUTHENTICATED` / `PERMISSION_DENIED` | `PROVIDER_UNAVAILABLE` 的配置变体 | `CONFIG_ERROR` | 否 |
| `UNAVAILABLE` / `DEADLINE_EXCEEDED` / `AGENT_UNAVAILABLE` | `PROVIDER_UNAVAILABLE` / `TRANSPORT_UNAVAILABLE` / `SEND_FAILED` | `BACKEND_ERROR` | 是 |
| `NOT_LEADER` | 由 SDK 重路由；适配器仍按 `BACKEND_ERROR` 重试 | `BACKEND_ERROR` | 是 |
| `RESOURCE_EXHAUSTED` / `QUOTA_EXCEEDED` / `MESSAGE_TOO_LARGE` | `LIMIT_EXCEEDED` / `SEND_FAILED` | `LIMIT_EXCEEDED` | 否 |
| `TXN_CAS_FAILED` / `TXN_CONFLICT` / `REBALANCE_IN_PROGRESS` / `GROUP_EPOCH_STALE` | 可重试后端错误 | `BACKEND_ERROR` | 是 |
| 其他（含 `INTERNAL`） | `INTERNAL_ERROR` | `BACKEND_ERROR` | 否 |

保留 `CoordException` 作为 cause；日志带 coord code，但不把 coord 类型暴露到 seedj 的 API。

### 7.5 可观测性

- v1 提供可选的 Micrometer 桥：`@ConditionalOnClass(MeterRegistry)` 时注册 `ObservabilityProvider`，记录 `seedj.coord.rpc`（标签 `operation` / `success`）与连接状态变化；没有 Micrometer 时使用 SDK 默认 Noop。
- 适配器不新增一套能力指标前缀；EventBus/PKI/Transit 的指标仍由各自 starter/适配器负责。
- 追踪：`EventMessage.headers` 已透传 `traceparent`；coord gRPC 调用不额外注入 trace，避免与 `TracePropagation` 重复。

## 8. 能力适配

### 8.1 ID

- 生产 bean 是 `IdGenerator<String>`（与 `seedj-infra-local` 的默认 bean 类型一致）；TCK 需要 `IdGenerator<Long>`，由测试直接实例化内部生成器，不额外暴露 Long bean。
- 字符串转换在 coord 模块内用 package-private 适配器完成；**不依赖** `seedj-infra-local`（实现模块之间零依赖）。
- 实现：`CoordIdGenerator`（package-private）内部用 `IdGenClient.nextBatch(name, batchSize)` 预取一段，`AtomicLong` 发号；用尽时同步补批。批缓存让 20k 次 TCK 调用不必打 20k 次 RPC，同时保持单生成器严格递增。
- 关闭：清空缓存，`id()` 抛 `IdException(IdErrorCode.PROVIDER_CLOSED)`；`close()` 幂等。
- 名称：`seedj.coord.namespace + ":" + seedj.coord.id.name`；同一应用的所有实例必须用同一个 name。
- 多实例：每个实例拿不同的批次；ID 不重复，允许有空洞。
- 错误：RPC 失败按 §7.4 映射为 `IdErrorCode.STATE_UNAVAILABLE`（可重试）或 `PROVIDER_CLOSED`。

### 8.2 Numbering

- 选择：`seedj.numbering.provider=coord`；未写时没有 `Numbering` bean（当前没有本地默认 bean）。
- `next(scope)` → `idgen.nextId(prefix + ":" + scope)`；`next(scope, year)` 沿用契约默认实现（拼 `_year`）。
- scope 校验沿用本地实现的正则 `^[a-z0-9_]+$`，非法时抛 `IllegalArgumentException`（契约没有专用异常）。
- 编号可能因为预取/实例崩溃出现空洞；契约只要求严格递增，不要求连续。文档要在 `spi.md` 写明这一点。

### 8.3 GlobalSequencer

- 选择：`seedj.clock.provider=coord`；未写时没有 `GlobalSequencer` bean。
- `next()` → `idgen.nextId(namespace + ":" + clock.name)`。
- 不用 TSO：TSO 需要 agent `data_plane=v2`；idgen 在 v1/v2 都可用，且满足“全局单调”的契约。若未来需要时间戳语义，再单独增加 TSO 实现。
- 不提供 `ClockProvider` 的完整实现：物理时钟仍是 `Clocks.system()`；coord 只做全局序列锚点。

### 8.4 EventBus 发布

- `send(EventMessage)` → `mqV2().publish(...)`：
  - `topic` 原样（业务 topic，不加 namespace）。
  - `partition`：`hash(partitionKey)`；`partitionKey` 为空时用 `hash(eventId)`。**不能 round-robin**：coord 的去重键是 `(topic, partition, idempotency_key)`，同一 eventId 的重试必须落在同一分区。
  - `payload`：`EventMessage.payload()` 原样。
  - `idempotencyKey`：`eventId`；coord 只在 topic 保留期（默认 7d）内去重，超出后同一 eventId 会再次追加，消费者仍须按 eventId 幂等。
  - `headers`：`eventType`、`eventVersion`、`occurredAt`、`eventId` + 业务 headers（traceparent 等）。
  - `producer`：配置的应用名。
- `ensureTopic(topic)`：`describeTopic`；`TOPIC_NOT_FOUND` 时 `createTopic(topic, partitions)`；`TOPIC_EXISTS` 视为成功。`createTopic` 的 `requestId` 用确定性值（如 `seedj:create:<topic>`）以便重试幂等。
- `topicExists`：`describeTopic` 成功即存在；`TOPIC_NOT_FOUND` 返回 false。
- 元数据缓存：topic → `(epoch, partitions)`；`TOPIC_EPOCH_STALE` 时刷新一次并重试，仍失败则抛 `SEND_FAILED`。
- 新建 topic 使用 coord 的默认保留期（7d）与配置的分区数；v1 不额外设置 retention。
- 异步：`send` 在专用虚拟线程执行器上调用同步 SDK，返回 `CompletionStage`；`close()` 关闭执行器并置 closed，之后 send 以 `PROVIDER_CLOSED` 异常完成。
- **不关闭共享 `CoordClient`**。

### 8.5 EventBus 消费（阶段 1，#377）

- 等 coord 提供投递级租约/延迟重试/attempts/过期 ack 围栏后，实现 `EventConsumerStore`：
  - `poll(PollRequest)` → coord poll（topic 集合、group、limit、lease）
  - `ack(ReceivedEvent)` → coord ack(deliveryId)；过期抛 `LEASE_LOST`
  - `retry(event, delay, failure)` → coord nack(deliveryId, retryAfter, failure)
  - `deadLetter(event, failure)` → coord deadLetter(deliveryId, failure)
  - `deadLetters(topic, group, limit)` → coord DLQ 查询（带 group/failure 归因）
- 在该 issue 关闭前不提供 bean；`CoordEventBusGuard` 按 §7.3 失败。
- 消费组默认值仍由 `EventConsumerContainer` 从 `EventSubscription.group()` 解析；coord 侧只当 group 名使用。

### 8.6 Lock（阶段 2，#379）

- `tryAcquire(key, ttl)`：
  - 校验 key 非空白、ttl > 0；非法抛 `LockException(INVALID_KEY/INVALID_TTL)`。
  - `holderId = instanceId + ":" + UUID`；每个 lease 独立，避免误释放。
  - `LockClient.acquire(lockKey, holderId, ttl)`；`acquired=false` 返回 `Optional.empty()`。
  - `lockKey = namespace + ":" + key`。
- `LockLease.close()` → `release(lockKey, holderId, leaseId)`；幂等；租约已过期导致的 `PERMISSION_DENIED`/`NOT_FOUND` 视为已释放，不抛。
- provider close 幂等；关闭后 `tryAcquire` 抛 `PROVIDER_CLOSED`。
- TTL 精度：等 #379 的 `ttl_ms`；在此之前不加该 bean（150ms TCK 无法通过，见 issue）。

### 8.7 PKI（阶段 3，#375）

- `issue(CertificateRequest)` → coord `issueCert(CN, ttl, dnsNames, ipAddresses)`：
  - CN 使用 seedj subject 解析出的 CN（或 alias）；SAN 直接传。
  - `IssuedCertificate.chain = [leaf, CA]`；私钥从 PEM 解析（PKCS#8）。
  - `keyPair`、`usage/EKU` 在 coord 支持前显式失败（`INVALID_REQUEST`，消息说明不支持），不静默忽略。
- `signingIdentity(alias)`：`getCertByCN(alias)`；不存在则 `issueCert(alias, ttl)`；已吊销时 coord 必须返回错误（#375），适配器映射为 `PkiException(REVOKED)`。
- `rotateSigningIdentity`：`rotateCert(alias, ttl, SAN)`；旧 serial 的 `status` 在 #375 后为 `REVOKED`。
- `revoke` / `status`：等 #375 的 `RevokeCert` / `GetCertBySerial`。
- `caCertificate`：解析 `getCaCert` PEM；`sslContext(identity)` 在客户端构建 KeyManager/TrustManager（与 `BcPkiProvider` 同样的 JDK API，不依赖 BouncyCastle）。
- CA 初始化：`initCa(caCommonName)` 幂等，在首次 issue/signingIdentity/caCertificate 调用时惰性执行，构造期不访问网络。
- CA 导入：等 #375；在此之前 coord 自建 CA。

### 8.8 Transit（阶段 4，#376）

- **前置决策**：coord 是否要做命名密钥/KMS。若采用 issue 里的方案 B（只保留全局 envelope + purpose HMAC），本能力不提供 `TransitProvider` bean，文档标注 unsupported；seedj 继续用本地/其他 KMS。
- 若采用方案 A：
  - `TransitKeyAdmin` → coord key lifecycle（Create/Read/Rotate/Configure/Delete/Export）。
  - `TransitCrypto` → 按 `keyName`/`version` 加密/解密/rewrap/批量/dataKey。
  - `TransitSigner` / `TransitMac` → 非对称签名/验签、HMAC/CMAC。
  - context 绑定、版本历史、删除幂等按 seedj TCK。
- 不支持的算法/操作必须在 `supports(type, op)` 返回 false，并在调用时抛 `TransitException(UNSUPPORTED_OPERATION)`。

### 8.9 Audit / Outbox（非目标）

- `AuditStore` 的哈希链需要与业务写入同一事务；`OutboxStore` 的原子性依赖业务数据所在的事务。coord 没有业务事务上下文，因此不在本模块实现。
- 需要集中式审计时，可由业务在 coord KV-T 上单独组合（CAS 维护 head），但这是业务侧/后续独立设计，不放进 `seedj-infra-coord` 的 v1。
- 结论：`OutboxStore` 继续由 `seedj-jdbc-postgresql` 提供；Audit 继续跟随业务存储。

### 8.10 Workflow（阶段 5，独立模块）

`seedj-workflow-coord` 是 coord workflow v2 的**兼容适配器**。由于 coord 是 `branch/round/seat` 模型、seedj 是 `step/task/assignment` 模型，适配器需要同时做定义映射和读模型聚合。

#### 定义映射

- `WorkflowDefinitionId(namespace, name)` → coord `Deploy(namespace, yaml)`；namespace 加 `seedj.coord.namespace` 前缀。
- `WorkflowDefinitionVersion(major, minor)` → coord version 字符串 `"major.minor"`。
- `StepKind`：
  - `ACTIVITY` → coord 的 `set`/`do` 空操作（seedj 的 ACTIVITY 没有外部执行器契约）。
  - `HUMAN_TASK` → coord 原生 `approve` 节点；把 `assignees/signCompletion/rejectCompletion/rejectTargets/dueIn` 映射到 `approvers/pool/readers/roles/mode/veto/deadline/onPass/onReject`。
  - `WAIT_SIGNAL` → coord 的 signal/wait 构造；`waitSignalName` 对齐 `suspension.expected_signal`。
  - `COMPENSATION` → coord 的 `compensate`/`compensatedBy`。
- 不能映射的 `HumanTaskSpec` 字段（`formKey/formSchema`、`allowAddAssignee` 等）在 `register` 时 **fail-closed**，并在异常里点名字段；不能静默丢弃。

#### 实例与任务

- `StartWorkflowRequest.instanceId` 作为 coord `workflow_id`（#380）；在 #380 之前不发布该模块。
- `WorkflowInstance` 从 `GetStatus` + `GetInstanceDetail` 组装：`currentStep = current_state_name`，`pendingTaskIds` = 打开轮次/席位对应的 seedj TaskId，`variables` = input/output 的 JSON 映射。
- `HumanTask` 的 `TaskId` 优先用 coord `round_id`（一个 step 的审批轮次），席位/投票映射到 `TaskAssignment`/`ApproveRecord`；前/后加签在 #381 之后使用派生的 task id。
- `listTasks`/`describeTask`/`search`/`history` 依赖 #380 的查询与历史 RPC；在此之前用 `GetInstanceDetail` 只能实现 `describe` 的子集，不发布 bean。
- 动作映射：Approve/Reject/Transfer/Delegate/Withdraw(Instance)/Terminate/Signal 直接用 coord；任务级 Withdraw/Urge/Cc/AddAssignee BEFORE/AFTER/Resume 依赖 #381；Reject 到目标步骤与 AtLeastRejects/RecordAndContinue 依赖 #382。
- `WorkflowCapabilities` 必须逐项与实现一致；`WorkflowProviderContractTest` 全绿前不装配。

#### 错误映射

coord 的 `NOT_FOUND` / `FAILED_PRECONDITION` / `INVALID_ARGUMENT` / `ALREADY_EXISTS` 分别映射到 seedj 的 `WorkflowNotFoundException` / `WorkflowStateConflictException` / `RejectTargetNotAllowedException` / `WorkflowVersionConflictException` 等；不要把所有失败都包成 `WorkflowProviderException`。

## 9. 测试策略

### 9.1 契约 TCK

每个能力在模块内提供 TCK 子类，直接复用 `core` test-jar：

| 能力 | TCK | 阶段 |
|---|---|---|
| ID | `IdGeneratorContractTest`（Long 生成器） | 0 |
| EventBus 发布 | `EventBusTransportContractTest` | 0 |
| Lock | `LockProviderContractTest` | 2 |
| PKI | `PkiProviderContractTest` | 3 |
| Transit | `TransitProviderContractTest` | 4 |
| EventBus 消费 | `EventConsumerStoreContractTest` | 1 |
| Workflow | `WorkflowProviderContractTest` | 5 |

Numbering / GlobalSequencer 没有抽象 TCK，用模块内语义测试（并发唯一、scope 隔离、年度隔离、单调）。

### 9.2 真实 coord 集成夹具

`coord dev` 是 v1 数据面，**不能**用于 MQ v2 的测试。模块测试使用一个 v2 夹具：

- 两个 Testcontainers（同一 network）：
  - `coord-server`：镜像 `ghcr.io/byteforce-cn/coord:v0.4.2`，挂载 `server.toml`，命令 `coord --config /etc/coord/server.toml server`。
  - `coord-agent`：同一镜像，挂载 `agent.toml`，命令 `coord agent --agent-config /etc/coord/agent.toml`，`static_peers` 指向 `coord-server:50051`。
- 镜像已确认存在：`ghcr.io/byteforce-cn/coord:v0.4.2`（coord release workflow 推送 GHCR；Docker Hub 的 `byteforce/coord:0.4.2` 不是发布源）。
- `server.toml` 必须开启：`multi_raft.enabled=true`、`multi_raft.pd.enabled=true`、`kv_t.enabled=true`、`mq_v2.enabled=true`，并用单节点 `cluster.initial_nodes` + 覆盖整个 keyspace 的 `initial_regions`。
- `agent.toml` 必须设置 `data_plane="v2"`，服务集对齐 `ServiceConfig::dev_mode()`（除 replication 全开），transit 用测试 KEK。
- 健康等待：agent `http://host:19528/healthz`（或对应 HTTP 端口）；server `http://host:50061/healthz`。
- 镜像可通过 `-Dseedj.coord.test.image=` / `SEEDJ_COORD_TEST_IMAGE` 覆盖；本地没有 Docker 时 `@Testcontainers(disabledWithoutDocker = true)` 跳过，CI 有 Docker 必须实际运行。
- 该夹具是阶段 0 的交付物；如果两容器 v2 拓扑在 CI 不稳定，退路是在一个容器里用 `sh -c` 启动 server + agent（仍必须是 v2），并记录原因。

### 9.3 装配测试

- `ApplicationContextRunner` + Mockito mock `CoordClient`（Mockito 5 支持 final class）验证：
  - `seedj.coord.enabled=false` 时没有 client / 没有 provider；
  - `provider=coord` 且能力开关关闭时没有 bean；
  - `provider=local` 时本地 bean 仍然存在（回归）；
  - provider=coord 但 coord 未启用时，守卫在启动期失败；
  - `transport=coord` 且有订阅但无 consumer store 时，守卫失败。
- 不启动真实 agent 的测试不应依赖网络。

### 9.4 多实例 / 端到端

- Lock：两个 provider（不同 holder）争同一 key；租约过期后第二个获得。
- ID：两个 client 并发取号，集合无重复。
- EventBus：一个 client 发布，另一个 consumer 组消费（阶段 1 后）。
- PKI：A 签发、B 用 `sslContext` 验证并互信（阶段 3）。
- Workflow：同一 coord 上跑 seedj TCK 的 33 个场景（阶段 5）。

### 9.5 CI 与门禁

- 模块测试进入默认 `verify`；Testcontainers 拉取 `byteforce/coord:0.4.2`，拉取失败必须红，不允许静默跳过。
- 新增模块后更新 `ModuleClassPath.MODULES`、`config/public-api.txt`、`config/cognitive-load-baseline.properties`、`config/coverage-baseline.json`。
- 覆盖率基线：阶段 0 先按实测值设下限（建议 line 0.75 / branch 0.55），之后只升不降。
- SpotBugs/Spotless 沿用根 POM；新模块不豁免。

## 10. 构建、发布与文档

- `bom/pom.xml`：`coord-java-sdk.version` 从 `0.3.5` 升到 `0.4.2`（2026-11-15 已确认 `cn.byteforce:coord-java-sdk:0.4.2` 在 Maven Central 可解析）；新增 `seedj-infra-coord`、`seedj-workflow-coord` 的 `dependencyManagement`。
- 根 `pom.xml` 的 `<modules>` 增加两个模块（顺序放在 `seedj-infra-local` / `seedj-workflow-local` 附近）。
- 两个模块 POM 继承 root，依赖：
  - `core`（compile）+ `core` test-jar（test）
  - `coord-java-sdk`（compile）
  - `spring-boot-autoconfigure`（provided）
  - `slf4j-api`（provided）
  - `spring-boot-starter-test`、Testcontainers（test）
  - `micrometer-core`（optional/provided，仅用于 §7.5 的可选桥）
- `AutoConfiguration.imports` 登记两个自动配置类。
- public API：只登记 `CoordAutoConfiguration`、`CoordProperties`、`CoordWorkflowAutoConfiguration`、`CoordWorkflowProperties`（4 个），provider 实现尽量 package-private；`api.publicTypes` 从 610 上调到 614，并在基线文件注释理由。
- 架构门禁：`ModuleClassPath.MODULES` 增加两个模块；新增规则“infra-coord 不依赖 jdbc/iam/server”“workflow-coord 只依赖 core + infra-coord”。
- 文档：
  - `docs/guide/modules.md`：模块职责与依赖方向。
  - `docs/guide/spi.md`：coord provider 能力矩阵、配置键、未支持项。
  - `docs/guide/configuration.md`：`seedj.coord.*` 与 provider 选择。
  - `docs/guide/known-limitations.md`：按 issue 列出尚未支持的 coord 能力。
  - `docs/guide/decision-guide.md`：什么时候选 coord、什么时候留 local/Postgres。
  - `CHANGELOG.md`：Unreleased 条目。
- 发布：走 tag，不经 PR；发布前确认 `coord-java-sdk:0.4.2` 可从 Central/私服解析。

## 11. 分期

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| 0 | 模块骨架、BOM、`CoordClient`、配置、错误映射、守卫、ID/Numbering/GlobalSequencer/EventBusTransport、v2 测试夹具、文档 | 两个 TCK 全绿；装配/守卫测试；CI 绿 |
| 1 | EventConsumerStore（#377） | `EventConsumerStoreContractTest` 全绿；多实例消费 |
| 2 | Lock（#379） | `LockProviderContractTest` 全绿；150ms 租约 |
| 3 | PKI（#375） | `PkiProviderContractTest` 全绿；双实例互信 |
| 4 | Transit（#376，若 coord 做命名密钥） | `TransitProviderContractTest` 全绿；否则文档标 unsupported |
| 5 | `seedj-workflow-coord`（#380–#382） | `WorkflowProviderContractTest` 33 个场景全绿 |

阶段 1–5 的顺序按 coord issue 就绪情况调整；每个阶段独立 PR，不把未绿的 provider 合进主线。

## 12. 风险与未决

1. **coord issue 的结论可能改变设计**：PKI 如何表达吊销、Transit 是否做命名密钥、Workflow 是否愿意补 seedj 兼容原语，都会影响阶段 3–5。模块骨架与阶段 0 不受影响。
2. **v2 测试夹具较重**：两容器 + multi_raft/PD 配置；如果 CI 不稳定，需要在阶段 0 解决，不能用 v1 夹具替代 MQ v2 测试。
3. **coord SDK 依赖足迹**：gRPC/Netty/protobuf 会进入使用该模块的应用；这是 opt-in 模块，文档要明确。
4. **EventBus 发布/消费不对称**：阶段 0 可以先发布、不能消费；守卫必须防止“有订阅但无 store”的静默丢消息。
5. **PKI 证书生命周期**：coord 默认 24h，seedj `signingIdentity` 习惯长有效期；适配器传 `validity`，但需要 #375 确认上限/续期语义。
6. **Public API 基线**：新增 4 个 public 类型要上调 `api.publicTypes`；如果实现类被迫 public，数量会更大，评审时要盯住。
7. **本地 provider 条件变更**：给 `seedj-infra-local` 加 `provider` 条件是小改动，但会影响所有现有装配测试；阶段 0 一并回归。
8. **Workflow 的语义差距可能超出 issue 范围**：33 个 TCK 场景要求 assignment 状态机、前后加签、催办/抄送、死锁回退等；如果 coord 只愿意做查询/动作的一部分，`seedj-workflow-coord` 应继续不发布，而不是降级。
9. **ID/编号的连续性**：coord idgen 从空序列开始分配（首个值 1），没有 seed/offset；coord 的 ID 是十进制字符串，不是雪花格式。已发号的业务（如员工号）切到 coord 会碰撞或改变格式，因此 coord-backed ID/Numbering 在 v1 是 **greenfield only**；已有数据迁移必须另立方案。
10. **启动依赖**：`seedj.coord.enabled=true` 后，EventBus topic capability 需要 agent 在启动期可达；coord 故障会阻断采用该能力的服务启动。部署顺序和 coord 的可用性预算要写进运维文档。
11. **配置键迁移**：阶段 0 给本地模块补 `provider` 条件，默认值保持兼容；但如果宿主已经手写了 `seedj.<cap>.provider`（自定义值），需要按新规则核对，避免 capability 静默消失。

## 13. 留给实现者

- 按阶段 0 先写模块骨架与测试夹具；不要先写被 issue 阻塞的 provider。
- `CoordProperties` 的嵌套配置类可以避免新增 public 类型；如果 Spring 绑定需要 public，优先拆成嵌套 `public static class`（public API 门禁会跳过 `$` 内部类，但 `@ConfigurationProperties` 的绑定语义要测试）。
- ID 的批缓存、EventBus 的 topic 元数据缓存、错误映射是三个容易出错的点，先写单测再写装配。
- 阶段 1–5 开始前，先读对应 coord issue 的最终 API，不要按本设计的临时形状硬编码。
- 需要向维护者确认的两个操作项：
  1. CI 是否允许运行两容器 v2 夹具（镜像已确认 `ghcr.io/byteforce-cn/coord:v0.4.2` 存在）；
  2. 是否接受 `api.publicTypes` 从 610 上调到 614（以及最终实测值）。
- 需要产品确认的一个采用问题：coord-backed ID/Numbering 是 **greenfield only**（没有 seed/offset，ID 格式从雪花字符串变为十进制字符串）。已有编号/ID 数据迁移必须另立方案，见 §12 第 9 条。
