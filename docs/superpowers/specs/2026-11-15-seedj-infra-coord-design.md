# seedj 全量切换到 coord（移除 local）设计

> 状态：Draft v2（待评审）
> 日期：2026-11-15
> 取代：本文件 v1（分阶段、local 为默认、coord 显式 opt-in）
> 关联：coord #375–#382。本设计**不依赖**这些 issue 关闭：seedj 侧按 coord 0.4.3 的现有契约适配。
> 已确认（2026-11-15）：测试也不保留内存替身；没有存量数据需要迁移；本地与 CI 都允许用 Docker 跑 PG/coord 夹具；coord 现有契约带来的能力损失（锁最小 1 秒、PKI 无吊销、Transit 单 KEK、消费无延迟重试）接受。

## 1. 意图

`seedj-infra-local` / `seedj-workflow-local` 退役。此后：

- **coord 承载基础设施能力**：ID、编号、全局序列、EventBus 发布与消费、锁、PKI、Transit、策略判定、工作流。
- **PostgreSQL 承载业务状态**：命令/事件存储、对象（temporal）、审计、Outbox/Inbox、IAM 数据、读模型。
- `seedj-it` 不再使用任何 local 实现，全量跑在 coord + PostgreSQL 上。
- 业务代码仍然只依赖 `core` 的契约，不 import `cn.byteforce.seedj.infra.coord.*`。

这是 1.0 之前的架构收口：删掉「本地实现」这条并行路径，用 coord 现有能力重新定义 seedj 的能力边界。契约按 coord 能表达的范围收窄；coord 做不到的语义从契约里删除（编译期消失），而不是留一个运行时抛异常的桩。

## 2. 一屏结论

| 能力 | 归属 | 与今天的差异 |
|---|---|---|
| ID | coord idgen | 删除 UUID7/雪花/本地字符串生成器；ID 形态变为十进制字符串 |
| 编号 / 全局序列 | coord idgen | 删除 `PostgresNumbering`、`PgGlobalSequencer`（与 coord 重复） |
| EventBus 发布 | coord MQ v2 | 已落地（阶段 0），保留 |
| EventBus 消费 | coord MQ v2 | 契约去掉「投递租约 + 延迟重试」；改为分区内串行 offset 语义 + 每 group 一个 DLQ topic |
| 锁 | coord Lock | 删除 `PostgresLockProvider`；TTL 最小 1 秒（coord 只有整秒） |
| PKI | coord PKI | 删除 `BcPkiProvider`；契约去掉 revoke / 按 serial 查状态 / CA 导入 |
| Transit | coord Transit | 删除 `BcTransitProvider`；只保留全局 envelope + purpose HMAC，去掉命名密钥与签名 |
| 工作流 | coord workflow（新模块） | 删除 `seedj-workflow-local`；`WorkflowProvider` 按 coord 审批模型收窄 |
| 策略判定 | coord Rego（新能力） | 新增 `PolicyEvaluator`；IAM 的角色/权限数据仍在 PG |
| 命令/事件/对象/审计/Outbox/Inbox/IAM 数据 | PostgreSQL | 不变 |
| 测试替身 | 不保留 | 测试用 PG/coord 真实现或 Mockito mock，不再维护内存 store |

## 3. 目标、非目标、反目标

### 3.1 目标

1. 仓库中不存在 `seedj-infra-local`、`seedj-workflow-local`：模块、BOM 条目、`AutoConfiguration.imports`、public API、门禁、文档全部清除。
2. 每个 coord 能力有契约 TCK + 装配测试 + 真 coord 集成测试；`seedj-it` 全绿。
3. 能力边界诚实：contract 里存在的操作都支持；不支持的操作不在 contract 里。
4. 单一实现路径：生产与测试都不存在「内存兜底」。coord 不可用时启动失败或调用失败，不降级。
5. 构建可复现：CI 能解析 coord SDK、能拉起 coord v2 夹具。

### 3.2 非目标

- 不改 coord 代码；不要求 #375–#382 关闭。coord 的新能力到达后另立设计接入。
- 不用 coord 存业务数据（命令/事件/对象/审计/IAM/Outbox）。
- 不保留「本地 dev 模式」；本地开发同样需要 coord agent + PostgreSQL。
- 不做历史数据迁移。**已确认目标环境是 greenfield、没有存量数据**：ID/编号从 coord 空序列开始；coord envelope 重新加密 IAM 的 TOTP seed 与 JWK 私钥；coord HMAC 的 keyId 重新生成。若将来出现存量环境，迁移（双读、重加密、回滚）另立设计。

### 3.3 反目标

- **运行时桩**：`revoke()` 之类保留在接口里、调用时抛 `UNSUPPORTED`。要么支持，要么从接口删除。
- **测试替身模块**：新建 `seedj-test-support` 把 `InMemory*` 搬过去。那只是把 local 改名。
- **两套并行实现**：coord 与 PG 同时提供锁/编号/序列，靠配置选择。同一个能力只有一个实现。
- **coord 宕机时静默放行**：授权、幂等、事件发布失败必须显式失败。

## 4. 方案与被否决的方案

### 4.1 A：硬切（选用）

删除两个 local 模块，测试也只用 coord + PG。测试里需要持久化时用 Testcontainers（PG、coord）；只测「SPI 消费者」的测试用 Mockito mock SPI。不保留任何可复用的内存实现。

**代价**：约 40 个 starter/it 测试文件要改夹具；CI 增加 coord 容器启动时间。**收益**：一条实现路径，删掉 65 个生产类和约 100 个只服务于内存实现的测试。

### 4.2 B：分层切（否决）

生产只留 coord + PG，内存替身迁到 test-scope 模块。测试快，但等于保留第二套实现与第二套语义；「移除 local」名不副实，且内存实现会持续漂移。

### 4.3 C：PG 补位（否决）

coord 做不到的（消费租约、亚秒锁、工作流）用 PG 实现。会造出第二套协调状态，与「coord 唯一承载」冲突；PG 已有锁/编号/序列的重复实现也说明这条路会把协调语义散在两个系统里。

### 4.4 D：先在 coord 补齐 #375–#382（否决）

契约最干净，但把 seedj 的交付押在另一个仓库的 8 个开放 issue 上（其中工作流是模型级差距）。本次明确选择「seedj 适配 coord 现有契约」。

## 5. 能力映射与契约变更

### 5.1 ID、编号、全局序列

- `IdGenerator<String>` 由 `CoordIdGenerator` 提供，内部 `nextBatch` 预取 + `AtomicLong` 发号。
- `Numbering` → `idgen.nextId(namespace + ":" + prefix + ":" + scope)`；保留 scope 正则校验。
- `GlobalSequencer` → `idgen.nextId(namespace + ":" + clockName)`。
- 删除：`Uuid7IdGenerator`、`SnowflakeIdGenerator`、`LocalStringIdGenerator`、`NodeIdAllocator`、`InMemoryNumbering`、`PostgresNumbering`、`PgGlobalSequencer`、`IdProperties` 中雪花/节点相关配置。
- **breaking**：ID 从 UUID/雪花字符串变成十进制字符串，可能有空洞、不保证连续；只适用于新数据。`docs/guide/spi.md` 与 `known-limitations.md` 写明。

### 5.2 EventBus 发布

沿用阶段 0 实现：`publish` + `idempotencyKey=eventId` + 稳定分区（`partitionKey` 或 `eventId` 哈希）+ headers 透传；`ensureTopic` 用确定性 requestId。topic 名不加 namespace。

发布端已实现于 `feat/seedj-infra-coord` 分支（commit `c8e62c8`，未合并）；本计划第一步把它并入主线，P2 补 TCK 与真 coord 集成测试。

### 5.3 EventBus 消费（契约变更）

coord MQ v2 提供 poll / ack / moveToDlq / pollDlq / group 成员与 generation 围栏，但**没有投递级租约、延迟重试、attempts 计数**，而且 ack 只能前进（`offset` 是最后处理位置，服务器提交 `offset+1`；ack 旧 offset 幂等、不回滚）。适配后的语义是「分区内串行、按 offset 至少一次」：

- `poll(PollRequest)`：coord `poll`；`PollRequest` 去掉 `lease`。group 成员由 `joinGroup`/`heartbeat` 维护，同一分区同时只有一个成员。
- 同一分区内严格串行：一条消息失败时**不 ack、不推进该分区的游标**，在进程内按退避重试同一 offset；成功后才 ack 并前进。阻塞发生在分区内部，符合分区日志的常规语义。
- `EventSubscription.concurrency` 语义改为「跨分区并发」；同一分区永远串行。
- `ack(ReceivedEvent)`：`ack(group, memberId, generation, offset)`；generation 过期 → `EventBusException(LEASE_LOST)`（`GROUP_EPOCH_STALE`）。`ReceivedEvent` 去掉 `deliveryId` / `leaseUntil`。
- `retry(ReceivedEvent, DeliveryFailure)`：**去掉 `delay` 参数**；退避由 container 的进程内表决定（重启归零）。
- `deadLetter(ReceivedEvent, DeliveryFailure)`：**不用 coord 的 `moveToDlq`**（它会 tombstone 主日志 offset，导致其他 group 也收不到）。改为把 `DeadLetterEnvelope` 发布到 `seedj.dlq.<topic>.<group>` 再 ack：只用 publish + ack 两个已有原语，保留 group 隔离；崩溃窗口内可能重复，按 eventId 幂等。
- `deadLetters(topic, group, limit)`：从该 DLQ topic 的起始 offset 读到 `limit`。
- 删除 `FileEventBusTransport`、`MemoryEventBusTransport` 与 `seedj.eventbus.transport` 配置。
- **breaking**：`retry(event, delay, failure)` → `retry(event, failure)`；`EventConsumerStore` 的租约语义删除；`DeadLetter.group` 保留（DLQ 按 group 分 topic）。

### 5.4 Lock（契约变更）

- `tryAcquire(key, Duration)`：`ttl` 向上取整到秒，最小 1 秒；`ttl < 1ms` 视为非法 → `LockException(INVALID_TTL)`。租约 `holderId = instanceId + ":" + UUID`，`lockKey = namespace + ":" + key`。
- `LockLease.close()` → `release(name, holderId, leaseId)`；已过期/已释放视为成功（幂等）。
- 删除 `PostgresLockProvider` 与 `seedj.lock.memory.*` / `seedj.lock.postgres.*`。
- **breaking**：亚秒租约不再支持（coord #379 未关闭）；`docs/guide/spi.md` 写明 TTL 粒度。

### 5.5 PKI（契约收窄）

coord 0.4.3 提供 `initCa` / `issueCert(CN, ttl, dnsNames, ipAddresses)` / `renewCert(serial, ttl)` / `rotateCert(CN, ttl, SAN)` / `listCerts(CN)` / `getCertByCN` / `verifyCert` / `getCaCert`，证书带 `status`（active/retired）与 `parentSerial`。没有：显式吊销、按 serial 查状态、CA 导入、usage/EKU 定制。

- `PkiProvider` 保留：`issue`、`signingIdentity`、`rotateSigningIdentity`、`caCertificate`、`sslContext`、`trustContext`、`close`。
- 删除：`revoke(serial, reason)`、`status(serial)`、`RevocationReason`、`CertificateStatus`、CA 导入配置、`keyUsage/EKU` 字段。
- `issue`：subject → CN；SAN 直接传；`Duration` → ttl 秒。请求里带非默认 keyUsage/EKU 时在**参数校验期**拒绝（`INVALID_REQUEST`），不进 coord。
- `signingIdentity(alias)`：`getCertByCN` 不存在则 `issueCert`；`rotateSigningIdentity` → `rotateCert`，旧证书由 coord 标记 retired、保留到 notAfter。
- `sslContext` 用 JDK 从 PEM 构建 KeyManager/TrustManager（不依赖 BouncyCastle）。CA 首次使用惰性 `initCa`。
- CN：`CertificateRequest.subject` 若是 DN（`CN=...`）取 CN 分量，否则整串作 CN；CN 必须是 coord 认可的 DNS 名，否则 `INVALID_REQUEST`。
- 每实例证书：coord 按 CN get-or-create。CN 必须包含实例标识（`<app>-<instance>`），SAN 随请求传入；返回证书的 SAN 与请求不一致时 `INVALID_REQUEST`，不复用别的实例的证书。
- `signingIdentity(alias)` 用 alias 作 CN（`iam-jwt-signer` 这类单标签名属于合法 DNS 名）；coord 拒绝时按 `INVALID_REQUEST` 暴露。
- 负向 mTLS 测试需要第二个「不受信 CA」：coord 只有一个 CA 且不支持导入，因此 `seedj-it` 保留一个 **test-only** 自签 CA 工具（BouncyCastle，只存在于测试源码），不算「local 实现」。
- **risk accepted**：没有吊销能力；密钥泄露只能等过期或换 CN。coord #375 关闭前，文档 `known-limitations.md` 明示。

### 5.6 Transit（契约收窄）

coord 提供全局 envelope（`encrypt/decrypt` + context）与 purpose-bound HMAC（`keyId` 标识 material）、`rewrap(dekId)`；没有命名密钥、生命周期管理、非对称签名、dataKey API。

- `TransitProvider` 只保留 `crypto()`、`mac()`、`supports()`、`close()`。
- 删除：`signer()`、`keyAdmin()`、`TransitSigner`、`TransitKeyAdmin`、`ExportedKey`、`KeyInfo`。
- `TransitCrypto`：`EncryptRequest/DecryptRequest` 去掉 `keyName`/`version`（全局密钥），保留 `context`；`Ciphertext` 携带 opaque payload + `String keyId`（coord 的 `dekId`），`Integer keyVersion` 删除；`rewrap(RewrapRequest)` 用 `Ciphertext.keyId`，不再有 `keyName`；`encryptBatch`/`decryptBatch` 保留（循环调用 coord，不是原子批量）；`generateDataKey` 删除。
- `TransitMac`：`HmacRequest` 的 `keyName` 改为 `purpose`，`MacResult.keyVersion:Integer` → `String keyId`（coord 返回的 material id）；`verifyHmac` 必须带 `keyId`。
- 已持久化的 ciphertext/MAC 属于 §3.2 的 greenfield 假定：不做旧格式解码。
- `IamSecretProtector` 的 coord 实现放 `seedj-infra-coord`（`CoordIamSecretProtector`），忽略旧 `keyName` 配置。
- **risk accepted**：不能按业务密钥名做隔离/轮换；所有 envelope 共用一个 KEK。

### 5.7 Workflow（映射 + 收窄）

coord 0.4.3 已有审批面：`deployDefinition`、`startByDefinition`、`getStatus`、`getInstanceDetail`、`listInstances`、`listPendingTasks(For)`、`approve/reject/acknowledge/addSign/removeSign/returnToNode/transfer/delegate/withdraw/terminate`（含 on-behalf-of）、`signal`、`cancel`、`watchInstance`、定义版本/回滚/删除。没有：调用方指定实例 ID、通用历史流、任意过滤的 search/query。

`seedj-workflow-coord` 做定义映射与读模型聚合：

| seedj | coord |
|---|---|
| `register(definition)` | `deployDefinition(namespace, yaml)`；`ACTIVITY`→noop，`HUMAN_TASK`→approve 节点，`WAIT_SIGNAL`→signal/wait，`COMPENSATION`→compensate |
| `start(request)` | `startByDefinition`；实例 ID 由 coord 生成；请求带 `instanceId` 时拒绝（`INVALID_REQUEST`） |
| `describe` | `getStatus` + `getInstanceDetail` 组装 |
| `search` | `listInstances(namespace, status, pageSize, pageToken)`；seedj 查询条件里的关键字/时间范围删除 |
| `history` | 不支持，从 `WorkflowProvider` 删除 |
| `listTasks` | `listPendingTasks` / `listPendingTasksFor(assignee)`；`TaskQuery` 只保留 assignee + 分页 |
| `describeTask` | 从 pending 列表 + `getInstanceDetail` 派生；找不到返回 empty |
| `act` | `WorkflowAction` 收窄为 coord 支持的动作集；`RejectTargetNotAllowed` ← `INVALID_ARGUMENT`，状态冲突 ← `FAILED_PRECONDITION` |
| `cancel`/`terminate` | `cancel` / `terminate(reason)` |
| `WorkflowCapabilities` | 逐项与实现一致；`supportsHistory=false`、`supportsDynamicForm=false`、`supportsUrge/Cc=false`、`supportsAddAssigneeBefore/After=false` |

- 定义映射遇到无法表达的 `HumanTaskSpec` 字段（`formKey/formSchema`、`allowAddAssignee` 等）在 `register` 期 fail-closed，异常点名字段。
- `WorkflowProvider` 的 `findDefinition` 用 coord 定义查询；TCK 场景按新契约重写（33 → 约 20）。
- **本模块当前没有产品消费方**（`WorkflowProvider` 在 server/starter 主代码中零引用），因此不阻塞 local 移除。

### 5.8 策略判定（新增，Rego）

- `seedj-infra-coord` 新增 `PolicyEvaluator`（package-private 实现 + 一个 public 自动配置）：`evaluate(query, input)` / `explain` / `putBundle` / 版本与回滚。
- IAM 的角色/权限/绑定/SoD 数据仍在 PG；`IamPolicyDecisionPoint` 的 coord 实现把 `IamAuthorizationRequest` + subject 属性 + 已解析权限编码为 JSON input，调用 `data.seedj.authz.allow`。
- seedj 自带一份 Rego bundle（`classpath:rego/seedj-authz.rego`），启动时 `putBundle` 幂等写入；tenant = `seedj.coord.namespace`，package 名全局唯一（coord 所有启用 bundle 共用一个引擎）。
- 策略写入（`IamPolicyAdminController`）时把 seedj 条件 DSL 编译成 bundle 内容：`ALLOW/DENY`、`priority`、subject/action/resource 匹配、条件子集（等值、集合、时间窗、组织范围）。遇到无法编译的条件类型在写入期拒绝，不做运行时忽略。
- 判定可用性：`decide` 走 coord `evaluate`，本地 LRU 缓存（默认 TTL 5s，键 = 请求摘要）；coord 不可用且缓存未命中 → 失败关闭（拒绝 + `PROVIDER_UNAVAILABLE`），不静默放行。
- SoD 仍在 Java（`SodRuleSet`/`SodEvaluator`）；Rego 只做 RBAC/ABAC 判定，input 里带 SoD 需要的角色互斥事实。所有 seedj 策略共用一个 bundle，`explain` 只用于拒绝审计。
- **这是本设计里最大的新开发**，但不阻塞 local 移除（IAM 现有 Java 判定在 `core`，不依赖 local）。作为 P6 最后接入；默认接受「每次未命中缓存一次 gRPC 判定 + 5s 缓存 + 失败关闭」，产品否决则 P6 不做，P0–P5 不受影响。

### 5.9 PG 侧的取舍

- 保留：命令/事件存储、temporal、审计、Outbox/Inbox、IAM 全量、读模型、`PgHybridStateStore`（HybridClock 的本地状态，不是协调原语）。
- 删除：`PostgresLockProvider`、`PostgresNumbering`、`PgGlobalSequencer`（coord 已提供，同一个能力只留一个实现）。
- **代价**：单机部署也必须运行 coord agent；HR 的 `application-postgres.yml` 从 `lock.postgres.enabled=true` 改为 coord。

### 5.10 明确不迁移

`AuditStore`、`OutboxStore`、`InboxStore`、`CommandStore`、`EventStore`、`TemporalStore`、IAM 各仓储继续由 `seedj-jdbc-postgresql` 提供。coord 没有业务事务上下文，不承载它们。

## 6. 配置与装配

### 6.1 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `seedj.coord.enabled` | `true` | 总开关；关闭时所有 coord 能力不装配，依赖它们的应用启动失败 |
| `seedj.coord.agent-host/port` | `127.0.0.1:19527` | agent gRPC |
| `seedj.coord.namespace` | `seedj:${spring.application.name:default}` | ID/编号/锁/工作流/策略命名空间 |
| `seedj.coord.instance-id` | `${spring.application.name}:${host}:${uuid}` | 锁持有者前缀 |
| `seedj.coord.auth.token` / `token-file` / `COORD_AUTH_TOKEN` | — | 鉴权，按此优先级 |
| `seedj.coord.tls.*` | 关闭 | CA/client cert/key |
| `seedj.coord.allow-insecure-plaintext` | `false` | 仅 loopback dev/test |
| `seedj.coord.id.name` / `batch-size` | `application` / `1000` | idgen 业务键与预取 |
| `seedj.coord.numbering.prefix` | `numbering` | 编号 scope 前缀 |
| `seedj.coord.clock.name` | `global-sequencer` | 全局序列 idgen 名称 |
| `seedj.coord.eventbus.partitions` / `producer` | `4` / 应用名 | 建 topic 与审计 |
| `seedj.coord.pki.ca-common-name` | `seedj-ca` | 首次 initCa |
| `seedj.coord.policy.bundle-name` | `seedj-authz` | Rego bundle 名 |

删除的键：`seedj.<cap>.provider`、`seedj.eventbus.transport`、`seedj.lock.memory.*`、`seedj.lock.postgres.*`、`seedj.id.*` 的本地生成器配置、`seedj.pki.state-file`、`seedj.transit.*` 的命名密钥配置。能力开关保留 `seedj.<cap>.enabled`。

### 6.2 装配与失败模式

- 一个 `CoordClient` bean（`destroyMethod=close`），各 provider 不关闭共享 client。
- `seedj.coord.enabled=false` 但某能力需要 coord → 启动失败（`InfrastructureException(CONFIG_ERROR)`），消息点名键。
- EventBus 有 `EventSubscriber` 但 consumer store 不可用 → 启动失败。
- TLS/鉴权缺失 → coord SDK fail-closed；`UNAUTHENTICATED/PERMISSION_DENIED` 映射 `CONFIG_ERROR`，不重试。
- agent 不可达：topic capability 在启动期失败 → 启动失败；ID/Lock/PKI 首次调用失败（惰性）。写进 runbook。

### 6.3 错误映射

只读 `CoordException.getErrorCode()`：`INVALID_ARGUMENT`→能力自己的 `INVALID_*`；`NOT_FOUND`→`NOT_FOUND`；`ALREADY_EXISTS`→冲突；`UNAUTHENTICATED/PERMISSION_DENIED`→`CONFIG_ERROR`；`UNAVAILABLE/DEADLINE_EXCEEDED/NOT_LEADER`→`BACKEND_ERROR`（可重试）；`RESOURCE_EXHAUSTED/QUOTA_EXCEEDED/MESSAGE_TOO_LARGE`→`LIMIT_EXCEEDED`；`AGENT_UNAVAILABLE/UNAVAILABLE/DEADLINE_EXCEEDED`→`BACKEND_ERROR`；`TXN_CAS_FAILED/TXN_CONFLICT/REBALANCE_IN_PROGRESS/GROUP_EPOCH_STALE`→可重试（ack 场景为 `LEASE_LOST`）；其余→`INTERNAL_ERROR`。保留 `CoordException` 作 cause。

## 7. 模块、依赖与门禁

```text
core
 ├── seedj-jdbc-postgresql        # 业务持久化（不含 lock/numbering/sequencer）
 ├── seedj-infra-coord ─────────── coord-java-sdk
 │                                # id / numbering / sequencer / eventbus / lock / pki / transit / policy
 └── seedj-workflow-coord ──────── seedj-infra-coord + coord-java-sdk
```

- 删除 `seedj-infra-local`、`seedj-workflow-local`：根 `pom.xml` modules、`bom/pom.xml` 条目、`AutoConfiguration.imports`。
- provider 依赖 SDK 的**能力子接口**（`IdGenClient`、`LockClient`、`PkiClient`、`TransitClient`、`MqV2Client`、`PolicyClient`、`WorkflowClient`）而不是 `CoordClient`；自动配置从 `CoordClient` 取子接口建 bean。这样装配测试 mock 接口即可，不需要 `mock-maker-inline`，也不用为测试造 seedj 包装层。
- `seedj-workflow-coord` 只依赖 `core` + `seedj-infra-coord`，复用 `CoordClient`。
- public API：删除 local/workflow-local 约 65 个类型；新增 `CoordAutoConfiguration`、`CoordProperties`、`CoordWorkflowAutoConfiguration`、`CoordWorkflowProperties`（provider/映射器保持 package-private）。
- 门禁同步：`config/public-api.txt`、`config/cognitive-load-baseline.properties`（`api.publicTypes` 净下降）、`config/coverage-baseline.json`、`seedj-it/conformance/ModuleClassPath.MODULES`、`ArchitectureTest`/`PackageInfoTest`/`LicensePresenceTest` 的模块清单。
- 架构规则新增：`seedj-infra-coord` 不依赖 jdbc/iam/server；`seedj-workflow-coord` 只依赖 core + infra-coord；`core` 仍零 Spring/零 coord。

## 8. 测试策略（硬切）

### 8.1 原则

- 不维护内存 store。provider 语义用真实现测；SPI 消费者用 Mockito mock。
- 契约 TCK 留在 `core` test-jar，字段/方法随契约收窄同步修改。
- 每个能力三件套：TCK + 装配测试（`ApplicationContextRunner` + mock 能力子接口，见 §7）+ 真 coord 集成测试。

### 8.2 coord 夹具

- 两容器（**host network**，server 与 agent 都绑定 `127.0.0.1`）：agent 拒绝非 loopback 上的明文绑定，而 `agent.toml` 不能开 dev 旁路；开 auth 需要 CCT/Ed25519 签发链路，测试夹具不建这套。host network 下双方都在 loopback，鉴权关闭即可，代价是仅 Linux 可跑、端口随机分配。
- server 开 `multi_raft` + `multi_raft.pd` + `kv_t` + `mq_v2`；agent `data_plane=v2`，服务集按 `ServiceConfig::dev_mode()` 打开。镜像 `ghcr.io/byteforce-cn/coord:v0.4.3`。
- 健康等待：agent HTTP healthz、server healthz；镜像可用 `-Dseedj.coord.test.image` 覆盖。
- 无 Docker 时 `@Testcontainers(disabledWithoutDocker=true)` 跳过；CI 有 Docker 必须真跑，拉取失败即红。
- 该夹具放在 `seedj-infra-coord` test-jar，供 `seedj-workflow-coord` 与 `seedj-it` 复用。

### 8.3 受影响的测试

| 位置 | 处理 |
|---|---|
| `seedj-infra-local` 自身约 100 个测试 | 随模块删除；TCK 覆盖由 coord provider 测试接替 |
| `seedj-iam-starter` 25 个测试文件 | 依赖内存 IAM store 的改 PG 容器；纯装配测试改 Mockito |
| `seedj-biz-starter` 15 个测试文件 | 命令/对象/审计 store 改 PG 容器（已有 `HrOnboardingPostgresTest` 可复用夹具） |
| `seedj-jdbc-postgresql` | 删除 lock/numbering/sequencer 实现与其测试 |
| `seedj-it` | 全量切 coord + PG（见 8.4） |

### 8.4 seedj-it 全量切换

- `ClusterExtension` 在启动三个服务前先起 coord v2 + PG；服务配置全部 `seedj.coord.*`，不再有 local bean。
- 集群 TLS/mTLS：证书来自 coord PKI（`initCa` + `issueCert`），替换 `ClusterPki`/`BcPkiProvider`。
- `EventBusIT`：断言从磁盘文件改为 coord topic/DLQ；`EventConsumeIT` 用真实 group 消费。
- `IdIT`：断言十进制字符串、跨实例唯一；删除 UUID 格式断言。
- `TransitIT`：envelope + purpose HMAC；删除命名密钥场景。
- `FieldMaskingIT`/`CommandAuthorizationIT`/`OidcIT`：仅换夹具（PG），断言不变。
- `WorkflowPersistenceIT`：JDBC schema/timer 保留；新增 coord 工作流 TCK 由模块内覆盖。
- conformance 测试（`ModuleClassPath`、`ArchitectureTest`、`PublicApiSurfaceTest`、`PackageCycleTest` 等）按新模块清单更新。

### 8.5 CI 前置

1. **coord SDK 可解析**：`cn.byteforce:coord-java-sdk:0.4.3` 目前不在 Central（404）。二选一：
   - 在 coord 仓走发布流程把 0.4.3（或 0.4.4）发布到 Central（推荐）；
   - CI 增加 coord 仓 checkout + `mvn -pl coord-java-sdk install -DskipTests`（与 eis 的 pin 方式一致）。
   不解决这条，构建无法开始。
2. Docker 可用：本地 Docker 29.x 已确认；CI 用 `ubuntu-latest` 的 Docker，允许拉取 PG 与 coord 镜像。v2 夹具启动时间计入 `timeout-minutes`。
3. `config/ci/verify.sh` 覆盖新模块；Testcontainers 失败不允许静默跳过。

## 9. 分期与出口条件

每个阶段独立 PR，`main` 始终保持绿。

| 阶段 | 内容 | 出口 |
|---|---|---|
| P0 前置 | SDK 发布/CI 安装；coord v2 夹具；`CoordClient` 配置与守卫 | 夹具本地与 CI 都能起；`coord.enabled=false` 的守卫测试绿 |
| P1 契约收窄 | `core` SPI/TCK：EventConsumerStore、Lock、Pki、Transit、Workflow；删除对应 local-only 类型 | `core` 编译、TCK 编译；API 门禁同步 |
| P2 coord provider | 消费、锁、PKI、Transit + TCK + 装配测试 | 四个 TCK 全绿；无 local 代码引用 |
| P3 workflow-coord | 模块骨架、定义映射、动作映射、TCK | 新 TCK 全绿；`seedj-workflow-local` 删除 |
| P4 切换与删除 | starter/server 配置切 coord；删 `seedj-infra-local`；删 PG 重复实现；BOM/门禁/文档 | 全仓无 `infra.local` 引用；`mvn verify` 绿 |
| P5 it 全量 | `ClusterExtension` + IT 重写；conformance 清单 | `seedj-it` 在 coord + PG 上全绿 |
| P6 策略 | `PolicyEvaluator` + Rego bundle + IAM 判定接入 | 授权场景回归；`explain` 审计可用 |

P6 不阻塞 P0–P5。P1 的契约收窄必须先于 P2（否则 provider 写不出来），P4 必须等 P2/P3 全绿。

## 10. 风险与未决

1. **coord SDK 不在 Central**：阻塞整个计划；发布流程在 coord 仓，需人工确认（0.4.3 已 build，未 publish）。
2. **PKI 无吊销**：只能靠短 TTL + rotate。安全评审需接受。
3. **Transit 无命名密钥**：所有 envelope 共用一个 KEK，业务无法按密钥隔离；IAM 只能整体轮换。
4. **消费无延迟重试**：退避与 attempts 在进程内，重启后丢失；毒消息可能需要人工干预 DLQ。
5. **锁最小 1 秒**：亚秒租约的调用方需改逻辑或改用 PG 事务锁——但那违反「单一实现」，需业务确认。
6. **ID 形态变化**：greenfield only；已有员工号/单号无法迁移。
7. **工作流模型差距**：实例 ID 由 coord 生成、无历史流、query 过滤面窄；未来产品需求若要求这些，只能回头推 coord issue。
8. **coord 成为启动/授权强依赖**：agent 不可用会阻断启动（EventBus topic）与授权（policy）。部署顺序、容量与故障演练要写进 runbook。
9. **测试时长**：PG + 两容器夹具会让 `seedj-it` 显著变慢；CI 预算需重新评估。
10. **分区内串行**：coord 消费的吞吐上限 = 分区数；高并发 topic 只能靠加分区。
11. **CN get-or-create**：同一 CN 的第二个实例会拿到第一个实例的证书与 SAN；CN 必须带实例标识，否则就是安全缺口。
12. **greenfield 确认**：目标环境无存量 ID/编号/密文/MAC；若出现存量环境，必须另立迁移设计。
10. **公开 API 收缩**：1.0 之前是最后窗口；`config/public-api.txt` 与文档要一次性对齐。

## 11. 留给实现者

- 先做 P0：SDK 可解析 + v2 夹具。夹具不稳就不要往下写 provider。
- P1 的契约修改先写 TCK 再改接口；每个删除的公开方法都要在 `docs/guide/api-compatibility.md` 记录。
- `CoordErrors`、ID 批缓存、topic 元数据缓存、consumer 游标是四个易错点，先写单测。
- workflow 映射先在纸上把 seedj 的 `StepDefinition`/`HumanTaskSpec` 逐字段对照 coord DSL，列出无法映射的字段，再写代码。
- P4 删除 local 前，用 `grep -rn "infra.local\|workflow.local"` 做一次零引用检查，再删模块。
- 需要产品确认：亚秒锁的调用方、已有 ID/编号/密文/MAC 数据是否真的不存在、工作流历史/查询的产品需求是否真的不存在、P6 的每次判定远程调用是否可接受。
