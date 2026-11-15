# coord 适配器：PKI / Transit / Workflow / Policy

状态：待评审 ｜ 作者：seedj maintainers ｜ 日期：2026-11-15

## 1. 意图

`seedj-infra-coord` 目前只把 coord 当「ID + 编号 + 序列 + 锁 + 消息」用。coord 0.4.3 的
Java SDK 已经提供 `pki()` / `transit()` / `policy()` / `workflow()` 四个客户端，而 seedj 的
`PkiProvider` / `TransitProvider` / `WorkflowProvider` / ABAC 决策点还没有 coord 实现：
`seedj.<cap>.provider=coord` 只在 `CoordProviderGuard` 里被列了名字，选它等于启动失败。

本设计把这四个适配器落地，并明确「coord 给不了的那部分」怎么表达——这是本设计的主要
内容，因为四个能力没有一个能和 seedj 契约逐字段对齐。

## 2. 目标

1. `seedj.pki.provider=coord`、`seedj.transit.provider=coord`、`seedj.workflow.provider=coord`、
   `seedj.iam.policy.provider=coord` 四个开关都能真正装配出对应 SPI 的 bean。
2. 每个适配器对自己的能力诚实：能用 `supports()` / `capabilities()` 表达的，逐项声明；
   表达不了的，在装配或调用点 fail-closed（抛异常），不静默降级、不静默忽略字段。
3. 每个适配器的行为都有可执行判据：契约测试（能过的必须过）+ 装配测试 + 真集群集成测试。
4. 缺口的边界写进 `docs/guide/known-limitations.md`，不留在代码注释里。

## 3. 非目标 / 反目标

- 不做 coord 侧的 CA、KMS、审批引擎重写；也不在 seedj 侧重实现 coord 已有的状态机。
- 不引入第二个 coord 客户端封装层（`CoordClient` 仍是唯一入口，适配器只组合原语）。
- 反目标：为了让契约测试变绿而在 seedj 侧伪造 coord 没有的语义。例如用「解密再加密」
  冒充 `rewrap`（明文会经过业务进程），或用本地 Map 冒充吊销账本（多实例不一致）。
- 反目标：把 `seedj-infra-coord` 变成「万一半支持」的默认选项。四个开关默认都关，
  local 实现仍是默认。

## 4. 约束（已核对的事实）

### 4.1 coord 侧能力面（tag v0.4.3 + main）

| 能力 | coord 承诺 | 与 seedj 契约的关系 |
|:--|:--|:--|
| PKI | 签发/续期/轮换/列举/取 CA/校验；**无吊销（无 CRL/OCSP）**；密钥服务端生成，固定 ECDSA P-256、KU `digitalSignature+keyEncipherment`、EKU `clientAuth+serverAuth`；`GetCertByCN` 返回私钥（= 私钥读取权） | `revoke()`/`status()`/`CertificateRequest.keyPair`/`CertificateUsage` 无对应 |
| Transit | 单 KEK 信封加密 `encrypt(plaintext, context)`；`hmacSignWithPurpose(data, purpose, algorithm, keyId)`；`rewrap(dekId)`；**无非对称签名（B-TR-1）、无 key admin、无 data key、无批量** | `keyAdmin()`/`signer()`/`generateDataKey()`/版本化 `keyVersion` 无对应 |
| Policy | `evaluate(query, inputJson)` 走 Rego bundle（KV 分发 + watch 收敛）；`checkPermission` 是 **agent 本地 RBAC**，远程调用一律 deny | 无 seedj Policy SPI；最接近的是 `IamPolicyDecisionPoint` |
| Workflow | 引擎实现了 13 个审批命令 + 5 个查询 + `History`（`grpc_handlers.rs` 的 `WorkflowEngineService`）；定义走 CNCF SW DSL，`approve` 节点有 `approvers/pool/roles/mode(JoinPolicy)/veto/reject/deadline/allow_cc/timeout_policy` | seedj v2.4 的 21 项能力里有 6 项没有对应语义 |

### 4.2 coord Java SDK 与引擎的落差

`WorkflowEngineService` 已经实现，但 **SDK 0.4.3 没暴露**：`ListTasks`、`GetTask`、`History`、
`Urge`、`Cc`、`WithdrawTask`、`ResumeWorkflow`；`Start.workflow_id`（指定实例 id）在 main 分支
才有 `startByDefinitionWithId`，v0.4.3 tag 没有。

`seedj` 的 `WorkflowProvider` 里 `listTasks` / `describeTask` 是**必选方法**，`history` 是可选的
核心读路径。没有 SDK 方法就没有任何合规实现路径（不能在 seedj 自己写 gRPC，SDK 是唯一入口）。

### 4.3 seedj 侧约束

- `WorkflowProviderContractTest`（1735 行 / 32 个用例）在第 45 行硬断言
  `supportsHumanTasks/supportsProviderClock/supportsResumeWorkflow` 全为 `true`；
  `TransitProviderContractTest`（268 行）直接调 `keyAdmin().createKey(...)`，不看 `supports()`。
  两者都按「candidate 必须全能力」写死。
- `TransitProvider` **有** `supports(KeyType, TransitOperation)`；`WorkflowProvider` **有**
  `capabilities()`。契约里本来就写着「调用方必须先查能力」——TCK 没照着做。
- local 装配：`pki`/`transit` 只有 `@ConditionalOnMissingBean`，没有 `seedj.<cap>.provider` 开关；
  `workflow` 有 `provider=memory`（`matchIfMissing=true`）。
- CI 取 SDK 的方式：`config/conformance/coord-ref.txt` 钉 `tag=` 或 `sha=`，
  `config/ci/install-coord-sdk.sh` 按 **bom 里的版本号** 从 coord 源码现场 `mvn install`，
  且**本地仓库已有该版本时直接跳过**；`~/.m2` 的 cache key 是 `hashFiles('**/pom.xml')`。
  ⇒ 只改 ref、不升版本号，CI 会静默复用旧 jar。

## 5. 选型

四个能力各选一条路，理由是「契约能不能在不撒谎的前提下成立」。

| 能力 | 选型 | 为什么不是另一种 |
|:--|:--|:--|
| PKI | **契约优先**：coord 负责签发，seedj 在 coord KV 上补一个吊销/别名账本，把 `PkiProvider` 的语义补全 | 「原生保真」会留着 `revoke` 抛不支持——但 `signingIdentity` 的「已吊销身份不得静默复活」是安全语义，不能缺 |
| Transit | **原生保真 + 能力矩阵**：只映射 coord 真有的 encrypt/decrypt/HMAC，其余 `supports()=false` 且调用即抛 | 「契约优先」需要伪造 named key、key 版本、非对称签名、data key；每一项都要么是假实现，要么把明文拉进业务进程 |
| Workflow | **原生子集 + 能力门控**：按 coord 自己的字段映射文档转译，缺的 6 项声明为 `false` 并在调用点拒绝 | 「契约优先」= 在 coord KV 上再写一个审批状态机，和 coord 引擎的计票/回退双写，必然分叉 |
| Policy | **新接缝**：`IamPolicyDecisionPoint` 的 coord 实现，配 Rego bundle `Evaluate` | `IamPolicyRepository` 是「策略 CRUD + epoch」的存储面，coord 的 bundle 是版本化 Rego 包，形状对不上；`checkPermission` 是 agent 本地 RBAC，远程用等于全拒 |

### 5.1 配套的契约测试改动（core，test-jar）

TCK 必须按契约自己写的方式工作：调用方先查能力。

- `TransitProviderContractTest`：每个用例前 `Assumptions.assumeTrue(provider.supports(...))`；
  并新增一条**下限**断言（三套实现都必须支持 `AES256_GCM96/ENCRYPT`、`DECRYPT`、`HMAC`、
  `VERIFY_HMAC`），避免「全声明 false 就能过」。
- `WorkflowProviderContractTest`：可选能力用例按 `capabilities()` 门控；把
  `startIsIdempotentAndCapabilitiesAreDeclared` 里三条硬断言 true 改成「声明与实现一致性」
  断言（`supportsProviderClock == true` ⇒ `now()` 是 provider 时钟等）。
- 现有实现（`BcTransitProvider`、`InMemoryWorkflowProvider`）声明全 true，行为不变。

这是 core 的测试改动，不是 core 的契约改动；公共 API allowlist（`config/public-api.txt`）不变。

## 6. PKI：`CoordPkiProvider`

### 6.1 组成

- `PkiClient`（签发/轮换/取 CA）。
- **吊销/别名账本**：`CoordKeySpace.of(namespace, "pki")` 下的两条前缀
  - `alias:<alias>` → `{alias, cn, currentSerial, subject, dnsNames, ipAddresses, ttlSeconds, usage}`
  - `serial:<serial>` → `{serial, alias, cn, notBefore, notAfter, revoked, reason, revokedAt}`
  - 写路径用 `CoordKvStore.putIfAbsent/replace`（读集校验的 CAS），不引入新原语。

### 6.2 身份命名空间

coord 的 PKI **按 CN 全局唯一**：`issueCert` 是以 CN 为键的 get-or-create，`getCertByCN` 返回私钥。
CN 不隔离，同 agent 上的两个应用（或两个测试）用同一个 alias 会拿到同一份密钥；任何持有
`pki:cert:read` 的消费方也能按 CN 取走 seedj 的私钥。

⇒ CN = `<identity-prefix>:<alias>`，`identity-prefix` 来自 `seedj.coord.pki.identity-prefix`
（默认 `seedj`）。账本键也用同一前缀。这同时解决三件事：跨应用不撞车、契约测试之间可隔离
（每个用例换一个随机前缀）、审计能看出证书归属。

`issue(request)` 的 subject 同样加前缀（`<prefix>:<cn>`）：否则 `issue("CN=svc")` 会和另一个
应用的 `svc` 共用一张证书。代价是签出来的 subject 与请求不完全一致——需要精确 subject 的
场景应使用本地 provider（那里的 CA 由调用方掌控）。写进 Javadoc 与 known-limitations。

### 6.3 语义映射

| seedj | 实现 |
|:--|:--|
| `signingIdentity(alias)` | 读 `alias:` 记录；当前 serial 已吊销 ⇒ `PkiException(REVOKED)`；否则 `issueCert(cn, ttl, dns, ips)`（coord 侧 get-or-create，过期自动换钥）；返回后 CAS 回写 alias→serial |
| `rotateSigningIdentity(alias, reason)` | 有当前身份 ⇒ 账本标记吊销 + `rotateCert(cn, ttl, dns, ips)`；无身份 ⇒ 等价 `signingIdentity` |
| `revoke(serial, reason)` | 账本 `putIfAbsent`，重复吊销幂等，**首个 reason 为准** |
| `status(serial)` | 账本：已吊销 ⇒ `REVOKED`；`notAfter <= now` ⇒ `EXPIRED`；否则 `VALID`；账本无记录 ⇒ `UNKNOWN` |
| `issue(request)` | `subject` 取 CN 并加前缀；`keyPair != null` ⇒ `INVALID_REQUEST`；`validity` → TTL 秒；`dnsNames/ipAddresses` 原样透传；写 `serial:` 账本 |
| `caCertificate()` | `getCaCert()` PEM → `X509Certificate`，构造期缓存 |
| `sslContext(identity)` | KeyManager = leaf + `[leaf, ca]` + PKCS#8 私钥；TrustManager = 只用本 PKI CA |
| `close()` | 幂等；置 closed 标记，之后抛 `PROVIDER_CLOSED`；**不关共享的 `CoordClient`** |

### 6.4 CA 初始化与能力授权

- 装配**不**调用 `initCa`：那是管理动作，需要 `pki:ca:init`，数据面应用不该常备这个授权。
  CA 未初始化时 `caCertificate()`/`signingIdentity()` 抛 `CA_UNAVAILABLE`，消息里点名
  「先 `coord pki ca` 初始化，或设置 `seedj.coord.pki.init-ca=true`」。
- `seedj.coord.pki.init-ca`（默认 `false`）：`true` 时装配期调一次 `initCa(seedj.coord.pki.ca-common-name)`，
  失败则启动失败（fail-closed）。只给 dev/test 用。
- 部署前置：CCT 至少持有 `pki:ca:init`（仅 init-ca=true 时）、`pki:cert:issue`、
  `pki:cert:rotate`、`pki:cert:read`。缺权限时第一次调用就失败，装配期不预检（预检需要
  并发一次读 RPC，且会把「暂时没授权」变成「起不来」）。

### 6.5 fail-closed 与已知偏差

- **SAN 必须显式传**：永远不让 coord 走「CN 派生 DNS SAN」的回退。`dnsNames` 为空时传
  `[sanitize(cn)]`（小写、非法字符换 `-`），保证任意 alias/CN 都能签出来。
- `CertificateUsage.SERVER/CLIENT` 无法表达：coord 的 profile 恒为双向（两个 EKU 都在）。
  签发结果是请求的**超集**，接受并记录偏差；不因此拒绝请求（否则 `CertificateRequest.server(...)`
  在 coord 上不可用）。
- `CertificateRequest.keyPair`（CSR 式交付）coord 做不到，直接拒绝：这是「私钥不出域」模型
  的分歧点，不能假装成功。
- **吊销只在 seedj 语义内生效**：`signingIdentity` 不会复活，`status` 会报 `REVOKED`；但
  TLS 对端不认（coord 无 CRL/OCSP，B-PKI-1）。写进 known-limitations，并在 Javadoc 点名。
- `issue()` 是 coord 的 get-or-create，且**只在 SAN 与 TTL 都与当前 active 记录一致时**幂等
  （`coord-agent/src/pki.rs:439-441`）；参数不同 ⇒ 等价轮换（新 serial + 新密钥，旧记录
  retired）。SDK javadoc 写的「per CN, period」是过时的，以 consumer-contract §9 与实现为准。
  适配器不做「签发后校验参数、不一致就 rotate」的补偿：那是重复实现 coord 的判定，还会把一次
  读变成一次轮换。集成测试钉住这条行为。
- `IssuedCertificate.serialNumber` 的「只在有效期内稳定」由此自然成立。

## 7. Transit：`CoordTransitProvider`

### 7.1 能力矩阵（`supports()` 的唯一事实源）

| 操作 | 支持 | 说明 |
|:--|:--|:--|
| `ENCRYPT` / `DECRYPT` | ✅ | keyName 编进 context（见 7.2）；AES/CHACHA 系列均 true |
| `HMAC` / `VERIFY_HMAC` | ✅ | keyName → `purpose`，主材料的 key_id 来自配置 |
| `REWRAP` | ❌ | coord 的 `Rewrap(dekId)` 只回新 dekId，不回新密文；SDK 也不暴露明文包解析。用「解密+加密」冒充会把明文拉进业务进程 |
| `GENERATE_DATA_KEY` | ❌ | coord 不暴露 DEK |
| `SIGN` / `VERIFY` | ❌ | B-TR-1：coord 不提供远程非对称签名 |
| `EXPORT` / `ROTATE` / `CONFIGURE` / `DELETE` | ❌ | 无 key admin 面 |
| 批量 | ✅（循环） | `encryptBatch/decryptBatch/rewrapBatch` 逐条调用单条接口，逐条返回 `BatchResult`；`rewrapBatch` 因 rewrap 不支持而整批失败 |

### 7.2 字段映射

- **keyName**：coord 只有一个 KEK，没有 named key。为了保住「同一密文只能被同一个 seedj key 解开」，
  keyName 编进 AAD：
  `context = "seedj:transit:v1|" + keyName + "|" + (request.context ?? "")`（UTF-8）。
  解密时用同一公式重算 ⇒ keyName 不匹配会得到 `DECRYPTION_FAILED`。
- **keyVersion**：coord 无版本概念。非 null 一律 `UNSUPPORTED_OPERATION`，不静默忽略
  （静默忽略会让「指定旧版本解密」变成「用最新版本解」，是数据兼容性事故）。
- **keyName 不做存在性校验**：coord 只有一个 KEK，没有命名密钥，也就没有「创建密钥」这个动作
  （`keyAdmin.createKey` 本身不支持）。keyName 是用途标签，写错名字的密文只能用同一个错名字
  解开——这与 seedj 其他 provider 的 `KEY_NOT_FOUND` 不同，写进 known-limitations。
- **Ciphertext**：`value = base64(packet)`，`keyVersion = null`，
  `contextHash = sha256hex(context)`（诊断用）。
- **HMAC**：`purpose = keyName`；`HmacRequest.keyVersion` 与 coord 的不透明 `key_id`
  （`default-kek` 这类字符串）无法互转 ⇒ 只支持主材料：`keyVersion != null` ⇒
  `UNSUPPORTED_OPERATION`；`MacResult.keyVersion = null`；签名返回的 `keyId` 丢弃。
  验签用配置项 `seedj.coord.transit.primary-key-id`（默认 `default-kek`）作为 coord 的必填 `key_id`。
  **后果**：轮换窗口内「按旧材料复算」在 seedj 侧表达不了，写进 known-limitations。
- 算法：seedj `HmacAlgorithm` → coord 算法名，不支持的取值 `UNSUPPORTED_ALGORITHM`。

### 7.3 错误映射（`CoordErrors` 新增两个入口）

`CoordException` → `TransitException`：`UNAVAILABLE/DEADLINE_EXCEEDED/AGENT_UNAVAILABLE/NOT_LEADER/
SAFE_TS_LAG/EPOCH_STALE/COMMIT_REJECTED` ⇒ `BACKEND_UNAVAILABLE`（retryable）；
`INVALID_ARGUMENT` ⇒ `DECRYPTION_FAILED`（解密路径）/ `INVALID_CONTEXT`（加密路径）；
`FAILED_PRECONDITION` ⇒ `INVALID_CONFIGURATION`；其余 ⇒ `INTERNAL_ERROR` 语义的 `BACKEND_UNAVAILABLE`。

`close()` 与 PKI 同：幂等、不关共享 client、之后抛 `PROVIDER_CLOSED`。

## 8. Workflow：`CoordWorkflowProvider`

字段级映射以 coord 仓的 `docs/production/seedj-workflow-provider-mapping.md` 为**唯一**口径，
本设计只定 seedj 侧的三件事：能力矩阵、身份编码、fail-closed 点。

### 8.1 能力矩阵（v1）

| 能力 | 声明 | 依据 |
|:--|:--|:--|
| `supportsHumanTasks` / `supportsRejectToStep` / `supportsRemoveAssignee` / `supportsTransfer` / `supportsWithdraw` / `supportsWithdrawInstance` / `supportsUrge` / `supportsCc` | ✅ | 引擎 + SDK 都有 |
| `supportsAddAssignee` | ✅（仅 `AddAssigneePosition.CURRENT`） | `AddSign` 只支持当前轮次加席 |
| `supportsAddAssigneeBefore` / `supportsAddAssigneeAfter` | ❌ | coord #381 未建模，`register`/`act` 拒绝 |
| `supportsDelegate` | ✅ | proto `delegate_for_ms` 到期后由 deadline 定时器恢复原审批人（`DELEGATION_EXPIRED`）；SDK 需补这个参数（§10.1） |
| `supportsResumeWorkflow` | ✅（依赖 10.1 的 SDK `ResumeWorkflow`） | proto 语义 = 按上一轮席位重建 + 新截止 |
| `supportsDynamicForm` | ❌ | `Approve/Reject` 只带 comment；表单由 seedj 侧存 |
| `supportsParticipantResolution` | ❌ | 表达式解析在 coord 引擎内，`resolveParticipants()` 无法透传 |
| `supportsProviderClock` | ❌ | 无时钟 RPC；状态时间由 coord 填，`now()` 用本地时钟 |
| `supportsCompensation` | ❌ | 逆序补偿要靠 DSL 连线，seedj 的 DAG 无法推导；`COMPENSATION` 步骤 `register` 拒绝 |
| `supportsSearch` / `supportsHistory` / `supportsVersioning` / `supportsSignalIdempotency` | ✅ | `ListInstances` / `History` / 不可变定义 + 版本 / `idempotency_key` |

`History` 的 `actionId` 当前恒空 ⇒ `WorkflowHistoryEvent.actionId = null`，写进 Javadoc。

### 8.2 定义转译（`register` / `findDefinition`）

- `WorkflowDefinitionId(namespace, name)` → coord `(namespace, name)`；namespace 用
  `seedj.coord.workflow.namespace`（默认 = `CoordNamespaces` 的值），不把 seedj 的业务
  namespace 直接透传（避免和 coord 自己的命名空间撞车）。
- `WorkflowDefinitionVersion(major, minor)` → 版本串 `"%d.%d"`，按字符串精确匹配。
- `steps` → CNCF SW `do:` 状态列表：`HUMAN_TASK` → `type: approve`；`ACTIVITY` → `set`；
  `WAIT_SIGNAL` → `listen`/`wait`；`COMPENSATION` → 拒绝。
- `dependsOn`：只接受**线性链**（每步最多一个前驱、整体无环且覆盖全部步骤）；
  菱形/并行依赖 `register` 抛 `WorkflowProviderException`（coord 无通用 DAG 调度器）。
- **超时策略必须显式转译**：seedj 的约定是 `HumanTaskSpec.metadata["timeoutPolicy"]`
  （缺省 `FAILED`，`WAITING` = 挂起等人工恢复；参见 `InMemoryWorkflowTimeouts` 与 TCK
  `overdueTaskCanWaitAndBeResumed`）。映射到 approve 节点的 `timeoutPolicy`：
  `dueIn == null` ⇒ `none`；否则缺省/`FAILED` ⇒ `fail`；`WAITING` ⇒ `waiting`；其他值 ⇒
  `register` 失败（不认识的值不能当缺省处理）。
- 时间字段：`StepDefinition.timeout` → 状态级 `timeout`；`WorkflowDefinition.workflowTimeout`
  → 定义级 `timeout`；`defaultStepTimeout` 只在步骤未声明 timeout 时参与上述推导。
  `retryPolicy != noRetry()` ⇒ `register` 拒绝（coord 的重试靠 `onErrors` 连线，语义不同）。
- **fail-closed 的粒度**：只拒绝「会改变行为、且 coord 无法表达」的值。`RetryPolicy.noRetry()`、
  TCK 那套 30s 超时、空 `metadata` 必须接受，否则 `register` 会把所有正常定义都拒掉，
  `supportsHumanTasks` 也就无从成立。
- `HumanTaskSpec` → `approvers`（`ParticipantSpec.value`）、`mode`（`AllApprove`→`all`、
  `AnyApprove`→`any`、`AtLeastCount(n)`→`quorum:n`、`AtLeastPercentage(p)`→`ratio:num/den`）、
  `reject`（`AnyRejectTerminates`→`anyReject`、`AtLeastRejects(n)`→`atLeastRejects:n`、
  `RecordAndContinue`→`recordAndContinue`）、`deadline`（ISO8601，由 `dueIn` 转）、
  `allow_cc`、`roles`（GROUP/DEPARTMENT 参与者）、`sign_admins`（`CE_LEVELER` 类）。
- 无法表达的一律 `register` 失败并点名字段：`formKey/formSchema`、`allowedAddPositions`
  里的 `BEFORE/AFTER`、`rejectTargets` 超出「已到达节点」、`retryPolicy != noRetry`、
  `metadata["timeoutPolicy"]` 取值不认识。
- `findDefinition(id, version)` → `getDefinition(workflowId)`；`version == null` 取最高版本
  （`listDefinitionVersions` 按 seedj 的 `(major, minor)` 数值序，不是字符串序）。

### 8.3 实例与动作

- `start` → `startByDefinitionWithId(defId, instanceId, inputJson)`：`instanceId` 就是 coord 的
  `workflow_id`，幂等由 coord 的「同 id + 同定义哈希」保证；正好对上 seedj 的
  `start` 按 `instanceId` 幂等。`startedBy` 与 `initialVariables` 进 `input` JSON。
- `act` → 各命令，`actionId` 直接当 `idempotency_key`；`occurredAt` 丢弃（coord 服务端取时）。
- 动作 → RPC 的表按 coord 映射文档 §3；`AddAssigneeAction.position != CURRENT` ⇒
  `WorkflowProviderException`；`DelegateAction.delegateFor` 原样映射 `delegate_for_ms`
  （null/零 ⇒ 不自动到期）。
- `describe` → `getStatus` + `getInstanceDetail`：状态映射
  `PENDING→PENDING`、`RUNNING→RUNNING`、`WAITING|SUSPENDED→WAITING`、`COMPLETED→COMPLETED`、
  `FAULTED→FAILED`、`CANCELLED→CANCELLED`、`TERMINATED→TERMINATED`；`currentStep` 取
  `currentStateName`；`pendingTaskIds` 取 detail 里 `status ∈ {created, claimed}` 的席位。
- `listTasks(TaskQuery)` → `ListTasks`（SDK 新增）：`instanceId/assignee/completedBy/candidateGroup/
  node/status/dueBefore` 一一对应；`limit` 与 `cursor` 用 coord 的 page token。
- `describeTask(TaskId)` → `GetTask`；`history(instanceId)` → `History`。
- 失败映射：`NOT_FOUND` ⇒ `WorkflowNotFoundException`；`ALREADY_EXISTS` ⇒
  `WorkflowStateConflictException`；`FAILED_PRECONDITION` ⇒ `WorkflowStateConflictException`
  或 `HumanTaskStateConflictException`（按 RPC 分）；`UNAVAILABLE/DEADLINE_EXCEEDED` ⇒
  `WorkflowProviderException`（retryable 语义由 message 带出）。

## 9. Policy：`CoordIamPolicyDecisionPoint`

### 9.1 接缝

- 实现 `IamPolicyDecisionPoint`，替换 `IamAbacConfiguration` 里的
  `defaultIamPolicyDecisionPoint` delegate（`CompositeAuthorizationDecisionPoint` 的外层不变）。
- **delegate 里不只“匹配策略”**：`DefaultIamPolicyDecisionPoint` 还负责两件事，coord 实现必须
  照做，否则会出现「看着能用、判定静默错」：
  1. 先跑 `IamResourceAttributeResolver`（有序）把 `resourceAttributes` 解析出来；不解析，
     Rego 里读到的是空 map，规则会静默走 deny 分支。
  2. deny 时写 `SecurityAuditEvent`（同一个 `SecurityAuditSink`，同一套「只审计拒绝」口径），
     否则 ABAC 拒绝链路丢审计。

  因此 `CoordIamPolicyDecisionPoint` 的构造参数是 `(ObjectProvider<IamResourceAttributeResolver>,
  SecurityAuditSink, IdGenerator<String>, PolicyClient, 配置)`，单元测试要有一条「Rego 能看到解析后的属性」
  与一条「deny 产生审计」。
- **缓存层不用**：`CachingIamPolicyDecisionPoint` 的 key 里带 `IamPolicyRepository.currentEpoch`，
  而 coord bundle 的变更走 `PutBundle`/`Watch`，**不会**推高这个 epoch。套上缓存 = 授权变更在 TTL
  （默认 30s）内静默不生效，且无法用现有接口 invalidation。
  ⇒ `provider=coord` 时直接注册 `iamPolicyDecisionPoint`（绕过缓存）。代价：每次决策一次 gRPC。
  等 coord 能给出 bundle 版本号再谈缓存（后续项，不在本设计内）。
  这意味着 PR4 要改一行 `seedj-iam-starter`（`@ConditionalOnProperty` 守住）。
- 新配置键 `seedj.iam.policy.provider=coord`（默认缺省 ⇒ 走 `DefaultIamPolicyDecisionPoint`）。
  `CoordProviderGuard.PROVIDER_KEYS` 增加该键。

### 9.2 判定

- 输入：`IamAuthorizationRequest` → JSON
  `{subject:{userId,username,tenantId,roles,permissions,groups,amr,acr,attributes}, action,
  resourceType, resourceId, resourceAttributes, environment}`（Jackson，经 `CoordJson`）。
- 查询：`seedj.coord.policy.query`（默认 `data.seedj.allow`）→ `evaluate(query, input)`。
- 结果解码：
  - `true` / `false` ⇒ `allow` / `deny("policy_denied")`；
  - 对象 ⇒ 读 `allow|allowed`（boolean）+ 可选 `reason`、`policyId`；
  - `null`（规则未定义）⇒ **deny**，reason `policy_undefined`；
  - 其它类型或解析失败 ⇒ **deny**，reason `policy_invalid_result`。
- `CoordException` ⇒ **deny**（`policy_unavailable`）+ WARN 日志，不向上抛：
  决策点必须对每个请求给出结论，认证/授权路径不接受异常逃逸。这是「fail-closed」在本能力
  的落点，与 PKI/Transit 的「调用点抛异常」不同，理由写在类注释里。
- 部署前置：CCT 持有 `coord:policy:evaluate`；bundle 的装载/回滚（`coord:policy:manage`）
  不属于应用权限，由运维执行。bundle 收敛是 ≤10s 量级，所以 IT 里要轮询而不是立即断言。

## 10. 跨仓依赖与版本（**不由本仓交付**）

### 10.1 coord 的读面缺口

`WorkflowEngineService` 里的任务读面（`ListTasks` / `GetTask` / `History`）与任务级命令
（`Urge` / `Cc` / `WithdrawTask`）在 coord 的 main 上已经有了，**但 coordinator 的 SDK 0.4.3 没有
暴露**，且 `Start.workflow_id`（指定实例 id）要到 `startByDefinitionWithId` 才可用（0.4.3 没有）。

`WorkflowProvider` 的 `listTasks` / `describeTask` 是**必选方法**，没有 SDK 方法就没有合规实现路径。

**结论：coord 的 SDK 属于 coord 自己的路线图，seedj 不为它提 PR、不改它的 proto、不碰它的分支。**
`seedj.workflow.provider=coord` 在 coord 发布包含上述读面的 SDK 之前不做实现，见 §13。

### 10.2 如果将来 coord 发布了新 SDK

seedj 侧只需要两件事，且必须一起做（否则 CI 会静默复用旧 jar）：

- `bom/pom.xml` 升 `coord-java-sdk.version`（`install-coord-sdk.sh` 在本地仓库已有同版本时直接跳过，
  `~/.m2` 的 cache key 又只看 `**/pom.xml`，只改 ref 不算数）。
- `config/conformance/coord-ref.txt` 指向对应的 tag。

## 11. 装配

| 能力 | 开关 | 装配类 | 冲突处理 |
|:--|:--|:--|:--|
| PKI | `seedj.pki.provider=coord` | `CoordAutoConfiguration` | `@AutoConfigureBefore(name="…infra.local.pki.PkiAutoConfiguration")`；local 增加 `provider=local`（`matchIfMissing=true`） |
| Transit | `seedj.transit.provider=coord` | 同上 | 同上（`…infra.local.transit.TransitAutoConfiguration`） |
| Workflow | `seedj.workflow.provider=coord` | 同上 | 同上（`…infra.local.workflow.WorkflowAutoConfiguration`，其 `memory` bean 已按 provider 门控） |
| Policy | `seedj.iam.policy.provider=coord` | 同上 | 用 `@ConditionalOnMissingBean(name="defaultIamPolicyDecisionPoint")` + `@AutoConfigureBefore` 抢占；starter 不改契约 |

新增配置项（都在 `CoordProperties` 下，带校验）：

| 键 | 默认 | 用途 |
|:--|:--|:--|
| `seedj.coord.pki.identity-prefix` | `seedj` | 证书 CN 的实例前缀（`<prefix>:<alias>`），同时是账本命名空间 |
| `seedj.coord.pki.identity-ttl` | `720h` | `signingIdentity` 的证书有效期（coord 侧 TTL） |
| `seedj.coord.pki.ca-common-name` | `seedj-ca` | `init-ca=true` 时 `initCa` 的 CN（幂等） |
| `seedj.coord.pki.init-ca` | `false` | 装配期尝试初始化 CA（仅 dev/test） |
| `seedj.coord.transit.primary-key-id` | `default-kek` | HMAC 验签必填的 coord `key_id` |
| `seedj.coord.policy.query` | `data.seedj.allow` | `Evaluate` 的 Rego 查询 |
| `seedj.coord.workflow.namespace` | `seedj:workflow` | 定义命名空间前缀 |

### 11.1 部署前置（写进 configuration.md）

| 能力 | 必需授权 |
|:--|:--|
| PKI | `pki:cert:issue`、`pki:cert:rotate`、`pki:cert:read`（`pki:ca:init` 仅 `init-ca=true`） |
| Transit | `coord:transit:crypto` |
| Policy | `coord:policy:evaluate`（bundle 管理是 `coord:policy:manage`，属运维） |
| Workflow | `coord:workflow:read`、`coord:workflow:execute`、`coord:workflow:define` |

coord 的 CCT/令牌没授这些能力时，装配能成功，第一次调用才报 `PERMISSION_DENIED`；
适配器把它映射成对应能力的 `CONFIG_ERROR` 类错误码（不重试），消息点名缺失的能力。

缺少授权不能预检：预检需要每次启动发一轮读 RPC，而且会把「暂时没授」变成「起不来」，
与 coord「惰性连接」的现有姿态一致。

## 12. 测试

| 层 | 内容 | 位置 |
|:--|:--|:--|
| 单元（无 Docker） | 账本 CAS/幂等/吊销后不复活；CN 前缀隔离；`CoordException`→错误码表；context 绑定（keyName 不匹配解不开）；定义转译（线性链/菱形拒绝、mode/reject/timeoutPolicy 映射、`noRetry`/30s 默认值必须接受）；查询/动作映射与“不支持就抛”的一致性；决策解码（true/false/对象/null/坏 JSON）+ 属性先解析可见于 Rego 输入 + deny 写审计；`supports()`/`capabilities()` 与调用点拒绝一致 | `seedj-infra-coord/src/test`（复用 `fixture/FakeCoordBackend` 的模式，新增 `FakePki/Transit/Policy/Workflow` 客户端） |
| 装配 | 四个开关各出一种 bean；开关关掉时不出现；guard 覆盖新键 | `CoordAutoConfigurationTest`、`CoordProviderGuardTest` |
| 契约 | `PkiProviderContractTest` 全量过（每个用例一个随机 `identity-prefix` 做隔离，共用一个 agent）；`TransitProviderContractTest` 按 `supports()` 门控后过；`WorkflowProviderContractTest` 按 `capabilities()` 门控后过 | `seedj-it`（需要 Docker；TCK 现在都在 core/本地实现里跑，coord 版本必须连真 agent） |
| 集成 | 真集群：PKI 签发→吊销→`signingIdentity` 抛错→同 CN 换 TTL 得到新 serial；Transit 加解密 + HMAC 往返 + 不支持操作抛错；Workflow deploy→start→approve→detail→（`timeoutPolicy=WAITING` 路径）超时挂起→resume；Policy PutBundle→轮询收敛→Evaluate 判定翻转（验证不走缓存） | `seedj-it`（`CoordCluster` 夹具扩 PKI/transit KEK/workflow durable/policy 所需配置） |

`CoordCluster` 需要新增的 agent 配置：PKI（`init-ca=true` 或夹具显式 init）、transit（注入
`COORD_TRANSIT_KEK` 与 `COORD_TRANSIT_KEK_ID`）、workflow（durable 装配开启）、policy（无额外开关）。
夹具目前不带令牌跑（`CoordCluster.java:130-141`），集成测试继续用无鉴权的 agent；能力授权
只写进文档，不进夹具。

## 13. 交付拆分

每个 PR 独立 CI 全绿，按依赖顺序：

1. **coord**：SDK 0.4.4（7 个 workflow 方法 + `delegateForMs` + DTO）、修正映射文档两处漂移、tag `v0.4.4`。
2. **seedj**：core TCK 能力门控 + local `provider=local` 开关（无行为变化）。
3. **seedj**：`CoordPkiProvider` + `CoordTransitProvider` + 装配/guard/单元/IT。
4. **seedj**：`CoordIamPolicyDecisionPoint` + 配置键 + `IamAbacConfiguration` 的缓存旁路 + 单元/IT。
5. **seedj**：`CoordWorkflowProvider` + 转译/映射/动作/查询 + 单元/IT + 文档
   （`spi.md` 适配器表、`modules.md` 行、`configuration.md` coord 段、`known-limitations.md`、`CHANGELOG`）。

## 14. 我的判断（评审时可逐条推翻）

1. **CN 带实例前缀**：`issue()` 的 subject 也被改写。不改写就会和同 agent 上其他应用/其他
   `seedj` 部署共用密钥（而且 `pki:cert:read` = 私钥读取权）。需要精确 subject 的场景回本地
   provider——这是数据面托管身份的必然取舍。
2. **PKI 吊销账本放在 coord KV，而不是进程内**：多实例下吊销必须跨实例可见；`signingIdentity`
   的决定走 `readCurrent`（事务快照），不会用到过期读。
3. **PKI 不拒绝 `CertificateUsage.SERVER/CLIENT`**：签发结果是请求的超集（两个 EKU 都在），
   拒绝会让 `CertificateRequest.server(...)` 在 coord 上完全不可用。偏差写进文档。
4. **`issue()` 不做参数后校验**：coord 自己就在「SAN/TTL 不同 ⇒ 轮换」，适配器再校一遍等于
   重复实现它的判定，还把一次读变成一次轮换。行为由集成测试钉住。
5. **Transit 的 `rewrap` 声明不支持**，而不是「解密 + 加密」：后者把明文带进业务进程，
   与 rewrap 存在的理由（不动明文地换 KEK）相反。
6. **Transit 不建 key 目录**：`keyAdmin` 全不支持，就没有「创建密钥」这个动作；再加一个只在
   配置里出现的目录等于凭空造一个 coord 没有的 provisioning 面。keyName 就是用途标签。
7. **Policy 绕过 `CachingIamPolicyDecisionPoint`**：缓存的失效信号来自 `IamPolicyRepository`
   的 epoch，和 coord bundle 的版本无关；套上就是「授权改了但 TTL 内不生效」。宁可每次决策
   一次 RPC，也不接受无法失效的缓存。
8. **`seedj.iam.policy.provider` 而不是 `seedj.policy.provider`**：接缝在 IAM 的 ABAC 决策点，
   不是新的顶层能力；键名要能指向唯一的 SPI。
9. **TCK 能力门控放在 core test-jar**：契约本来要求调用方先查能力，TCK 不查是 TCK 的缺陷；
   把门控放进 coord 模块会让每个新 provider 重复这套逻辑。门控之外还要留下限断言，防止
   「全声明 false 就能过」。
10. **超时策略用 `metadata["timeoutPolicy"]` 这个约定**：它现在只活在内存实现和 TCK 里，
    core 文档没写。适配器必须认它（否则 `WAITING`/`resume` 整条路径不可达），并在 `spi.md`
    把「缺省 FAILED / WAITING」写成公开约定。
