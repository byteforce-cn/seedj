# coord 能力适配器实现计划（PKI / Transit / Policy / Workflow）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `seedj.pki.provider=coord`、`seedj.transit.provider=coord`、`seedj.iam.policy.provider=coord`、`seedj.workflow.provider=coord` 四个开关各自装配出真实可用的 SPI 实现，并对 coord 给不了的部分 fail-closed。

**Architecture:** 每个能力一个适配器，组合 `CoordClient` 的对应客户端与已有的 `CoordKvStore`（只 PKI 需要自建账本）。能力边界由 `supports()` / `capabilities()` 声明，声明与调用点拒绝由单测钉住；契约测试在 core test-jar 里按能力门控。

**Tech Stack:** Java 21、Spring Boot 自动配置、coord-java-sdk 0.4.3→0.4.4、JUnit 5 + AssertJ + Testcontainers、Maven（JDK 25 可构建，`--release 21`）。

**Spec:** `docs/superpowers/specs/2026-11-15-coord-capability-adapters-design.md`

## Global Constraints

- 分支 → PR → CI 全绿 → merge → 删分支；不得直接推 `main`（coord 用 `--merge`，seedj 用 `--squash`）。
- 凭证只从 `GH_TOKEN` 读；不得写进文件或提交。
- `core` 零 Spring 依赖；适配器不 import starter 实现；`seedj-infra-coord` 不依赖 `seedj-infra-local` / `seedj-iam-starter`。
- 适配器不 `close()` 共享的 `CoordClient`；`close()` 幂等，之后抛 `PROVIDER_CLOSED`。
- 装配只在 `seedj.coord.enabled=true` 时创建 `CoordClient`；`provider=coord` 而 coord 未启用 ⇒ 启动失败（`CoordProviderGuard`）。
- 新增配置项都要在 `CoordProperties` 里做构造期/启动期校验，非法值让启动失败。
- 提交信息用中文、conventional 前缀（`feat(coord):` / `test(coord):` / `docs(coord):`）。
- 格式化：`./mvnw spotless:apply`（google-java-format AOSP 4 空格，行宽 100）。

## Review Focus

1. **`signingIdentity` 在吊销/过期/并发下的行为**——调用方预期「同一 alias 永远拿到可用身份，已吊销就报错」，最容易被跨实例并发或账本 CAS 失败破坏。
2. **`keyName`/`context` 混用**——调用方预期「keyA 加密的密文用 keyB 解不开」，AAD 派生写错就变成静默可解。
3. **`register` 对正常定义的接受面**——调用方预期「TCK 那套 30s 超时 + noRetry 的定义能注册」，拒绝得过宽会让适配器整体不可用。
4. **ABAC 属性缺失**——调用方预期「资源属性解析器生效」，没跑解析器时 Rego 读到空 map 会静默 deny。
5. **coord 不可达时的错误分类**——调用方预期「可重试的按可重试处理」，把 `UNAVAILABLE` 映射成不可重试会让业务方放弃重试。

---

### Task 1: ~~coord SDK 0.4.4~~ → 外部依赖，不在本仓

**不做。** coord 是另一个仓库：它的 SDK 读面（`ListTasks`/`GetTask`/`History`/`Urge`/`Cc`/`WithdrawTask`）
与 `Start.workflow_id` 由 coord 自己的路线图交付。seedj 不为它开分支、不提 PR、不改 proto。

Task 6（Workflow 适配器）依赖它，所以在它发布前 **Task 6 不启动**；Task 2–5、7 不受影响。

### Task 2: core 契约测试按能力门控 + local 的 provider 开关

**Files:**
- Modify: `core/src/test/java/cn/byteforce/seedj/transit/TransitProviderContractTest.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/workflow/spi/WorkflowProviderContractTest.java`
- Modify: `seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/pki/PkiAutoConfiguration.java`
- Modify: `seedj-infra-local/src/main/java/cn/byteforce/seedj/infra/local/transit/TransitAutoConfiguration.java`
- Test: `core/src/test/java/cn/byteforce/seedj/transit/TransitProviderSupportGateTest.java`（新）
- Test: `seedj-infra-local/src/test/java/cn/byteforce/seedj/infra/local/ProviderSelectionTest.java`（新）

**Interfaces:**
- Consumes: `TransitProvider.supports(KeyType, TransitOperation)`、`WorkflowProvider.capabilities()`（都已存在）
- Produces: 契约测试可被「只支持子集」的实现继承；`seedj.pki.provider` / `seedj.transit.provider` 两个新开关（`local` / `coord`，`matchIfMissing=true` ⇒ `local`）

- [ ] **Step 1: 写失败测试** `TransitProviderSupportGateTest`：一个 `TransitProvider` 桩，`supports` 全 false，除 `ENCRYPT/DECRYPT/HMAC/VERIFY_HMAC`；断言 `new TransitProviderContractTest(){...}.supportedOperationsAreDeclared()` 触发**下限**失败（消息含 `AES256_GCM96 ENCRYPT`）。
- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现门控**：`TransitProviderContractTest` 每个用例首行 `Assumptions.assumeTrue(provider.supports(...))`；新增 `@Test void supportedOperationsAreDeclared()` 断言最小集合（`ENCRYPT`/`DECRYPT` on `AES256_GCM96`、`HMAC`/`VERIFY_HMAC` on `HMAC`）；`WorkflowProviderContractTest` 的 21 项能力用例按 `capabilities()` 门控，删除第 45-72 行的三条硬断言，改为一致性断言（`supportsProviderClock ⇒ now()` 非空且单调不减；`supportsResumeWorkflow ⇒ resume 用例执行`）。
- [ ] **Step 4: local 开关**：两个 `@AutoConfiguration` 加 `@ConditionalOnProperty(prefix="seedj.<cap>", name="provider", havingValue="local", matchIfMissing=true)`；`ProviderSelectionTest` 断言 `provider=local` 出 bean、`provider=coord` 时 local 不出 bean（用 `ApplicationContextRunner`）。
- [ ] **Step 5: 跑** `./mvnw -o -pl core,seedj-infra-local,seedj-workflow-local -am test`，全绿。
- [ ] **Step 6: 提交 + PR**（seedj，`--squash`）。

### Task 3: `CoordPkiProvider`

**Files:**
- Create: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordPkiProvider.java`
- Create: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordPkiState.java`
- Create: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordPkiCertificates.java`（PEM→`X509Certificate`/`PrivateKey`、`SSLContext`）
- Modify: `CoordAutoConfiguration.java`（bean）、`CoordProperties.java`（4 个 `pki.*` 键 + 校验）、`CoordErrors.java`（`pki()`）
- Test: `CoordPkiProviderTest`（TCK 子类，`seedj-it`）、`CoordPkiStateTest`、`CoordPkiCertificatesTest`、`FakePkiClient`

**Interfaces:**
```java
CoordPkiProvider(PkiClient pki, CoordKvStore store, CoordKeySpace space, CoordEndpoint endpoint,
                 String identityPrefix, Duration identityTtl, Clock clock)
```
- `cn(alias) = identityPrefix + ":" + alias`；账本键 `alias:<alias>` / `serial:<serial>`。

- [ ] **Step 1: 写失败单测** `CoordPkiStateTest`：`revoke` 幂等且首个 reason 为准；`replaceAlias` CAS 失败返回 false；未知 serial ⇒ `status` 走 `UNKNOWN`。
- [ ] **Step 2: 写失败 TCK** `seedj-it/.../it/coord/CoordPkiProviderContractTest extends PkiProviderContractTest`，`newProvider()` 用随机 `identityPrefix`，复用同一 `CoordCluster`。
- [ ] **Step 3: 实现**（按 spec §6.2/§6.3/§6.4）。
- [ ] **Step 4: 跑** `./mvnw -o -pl seedj-infra-coord,seedj-it -am -Dtest='CoordPki*' test`（无 Docker 时 IT 跳过，CI 跑）。
- [ ] **Step 5: 提交 + PR。**

### Task 4: `CoordTransitProvider`

**Files:**
- Create: `CoordTransitProvider.java`、`CoordTransitContext.java`（AAD 派生 + base64 + sha256）
- Modify: `CoordAutoConfiguration.java`、`CoordProperties.java`（`transit.primary-key-id`）、`CoordErrors.java`（`transit()`）
- Test: `CoordTransitProviderTest`（`seedj-it`，TCK 子类 + 不支持操作用例）、`CoordTransitContextTest`

**Interfaces:**
```java
CoordTransitProvider(TransitClient client, CoordEndpoint endpoint, String primaryKeyId, Clock clock)
static byte[] CoordTransitContext.of(String keyName, String context)
```

- [ ] **Step 1: 写失败单测** `CoordTransitContextTest`：同 keyName 同 context ⇒ 字节相同；不同 keyName ⇒ 不同；`context` 为 null 与空串等价。
- [ ] **Step 2: 写失败 TCK** `CoordTransitProviderContractTest`（按 `supports()` 门控后全绿）+ `unsupportedOperationsThrow`（`REWRAP`/`SIGN`/`createKey`/`generateDataKey` 抛 `UNSUPPORTED_OPERATION`，且 `supports()` 全为 false）。
- [ ] **Step 3: 实现**（spec §7.1–§7.3）。
- [ ] **Step 4: 跑测试 + 提交 + PR。**

### Task 5: `CoordIamPolicyDecisionPoint`

**Files:**
- Create: `CoordIamPolicyDecisionPoint.java`、`CoordPolicyInput.java`（`IamAuthorizationRequest` → JSON）
- Modify: `CoordAutoConfiguration.java`、`CoordProperties.java`（`policy.query`）、`CoordProviderGuard*.java`（`seedj.iam.policy.provider`）、`CoordErrors.java`（`policy()` 如需）
- Modify: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/starter/iam/autoconfigure/IamAbacConfiguration.java`（`provider=coord` 时不注册缓存 PDP）
- Test: `CoordIamPolicyDecisionPointTest`（`FakePolicyClient`）、`CoordPolicyInputTest`、`seedj-it` 的 `CoordPolicyIT`

- [ ] **Step 1: 写失败单测**：`true` ⇒ allow；`false`/`null` ⇒ deny（reason `policy_denied` / `policy_undefined`）；对象 `{"allow":true,"reason":"x"}` ⇒ allow 且 reason 透传；坏 JSON ⇒ deny `policy_invalid_result`；`CoordException` ⇒ deny `policy_unavailable`；**属性解析器被调用且结果出现在 Rego 输入里**；**deny 触发一次 `SecurityAuditSink`**。
- [ ] **Step 2: 实现**（spec §9.2，审计事件形状照抄 `DefaultIamPolicyDecisionPoint`）。
- [ ] **Step 3: 跑 + 提交 + PR。**

### Task 6: `CoordWorkflowProvider`

**Files:**
- Create: `CoordWorkflowProvider.java`、`CoordWorkflowDefinitions.java`、`CoordWorkflowDsl.java`、`CoordWorkflowMapping.java`、`CoordWorkflowActions.java`、`CoordWorkflowQueries.java`
- Modify: `CoordAutoConfiguration.java`、`CoordProperties.java`（`workflow.namespace`）、`bom/pom.xml` + `config/conformance/coord-ref.txt` + `CoordCluster.IMAGE`（→ 0.4.4）
- Test: `CoordWorkflowDslTest`、`CoordWorkflowMappingTest`、`seedj-it` 的 `CoordWorkflowProviderContractTest` + `CoordWorkflowIT`

- [ ] **Step 1: 写失败单测** `CoordWorkflowDslTest`：`human + activity` 线性链 ⇒ YAML 含 `approve`/`set` 与 `mode: all`；菱形 `dependsOn` ⇒ `WorkflowProviderException`（消息含步骤名）；`AtLeastPercentage(2/3)` ⇒ `ratio:2/3`；`metadata["timeoutPolicy"]="WAITING"` ⇒ `timeoutPolicy: waiting`，缺省 ⇒ `fail`，`dueIn == null` ⇒ `none`；`retryPolicy != noRetry` ⇒ 拒绝；TCK 的 `noRetry + 30s` 定义必须被接受。
- [ ] **Step 2: 写失败单测** `CoordWorkflowMappingTest`：`RUNNING/WAITING/SUSPENDED/FAULTED` 状态映射；`pendingTaskIds` 只取 `created|claimed`。
- [ ] **Step 3: 实现定义转译与映射**（spec §8.2/§8.3）。
- [ ] **Step 4: 实现入口与动作/查询分发**（`startByDefinitionWithId`、`idempotency_key=actionId`、7 个新 SDK 方法）。
- [ ] **Step 5: 跑 TCK**（`seedj-it`，Docker）；未门控的用例必须全绿，被门控的按 `capabilities()` 跳过。
- [ ] **Step 6: 提交 + PR。**

### Task 7: 文档与变更记录

**Files:**
- Modify: `docs/guide/spi.md`（§5 适配器表 + `timeoutPolicy` 约定）、`docs/guide/modules.md`（coord 行）、`docs/guide/configuration.md`（coord 段 + 新键 + 授权前置）、`docs/guide/known-limitations.md`（PKI 吊销边界、Transit 能力缺口、Workflow 能力矩阵、Policy 不走缓存）、`CHANGELOG.md`
- Test: `docs` 构建 `npm run docs:build`（或 CI 的 docs.yml）

- [ ] **Step 1: 写文档**（每条缺口一句「调用方预期」+「实际」）。
- [ ] **Step 2: 跑 `npm run docs:build`**。
- [ ] **Step 3: 提交 + PR。**

---

## Self-Review

- **Spec coverage**：spec §6/§7/§9 各有 Task 3/4/5；§8（Workflow）在 Task 6，且以 §10 的外部依赖解除为前提；§10 的跨仓依赖显式不做（Task 1 划掉）；§11 装配在 Task 2/3/4/5；§12 测试在每单任务的 Step 1–2 与 Step 5；§13 拆分即 Task 1–7；§14 的 10 条判断都落在具体步骤（前缀、账本、usage、issue 不后校验、rewrap、不建 key 目录、绕过缓存、键名、TCK 门控、`timeoutPolicy`）。
- **类型一致**：`CoordKeySpace`/`CoordKvStore`/`CoordJson` 均沿用现有包内签名；`CoordPkiProvider` 构造参数在 Task 3 的 Interfaces 里写死，坐标一致。
- **比例**：plan 只锁签名与断言，不抄实现体。
