# AI 装配：决策账本、DecisionToken、guard 与有界 agent 循环

`seedj-ai-starter` 把「agent 能不能动这条命令」变成一条可复核的链：决策面先落 intent 再签发
DecisionToken，执行侧 guard 用 `x-agent-decision` 头逐字核对请求，命令落库后由对账按链上事实补
`reconciled`。宿主只留一个 `@EnableAIServer` 与几行配置。

> 当前阶段（T0）：决策账本（内存 / PostgreSQL 两套实现）、DecisionToken 签发与校验、执行侧
> guard、决策面 HTTP（`POST /v1/decisions`、`GET /v1/decisions/{id}`、
> `POST /v1/decisions/{id}/outcome`、JWKS）、有界 agent 循环（`BoundedLoop`）。**不含**策略求值、
> 人工审批、不可逆动作的行级门禁、外部副作用两段协议与 IM 回传，逐条见
> [T0 明确不做](#t0-明确不做)。

## 依赖与模块分层

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>seedj-ai-starter</artifactId>
</dependency>
```

四层，谁都不越界：

```text
契约 core（cn.byteforce.seedj.ai / cn.byteforce.seedj.ai.spi）
    值类型与两个端口，零 Spring 依赖
    DecisionIntent / DecisionRecord / DecisionEffect / DecisionTokenClaims /
    ActionClass / AiErrorCode / DecisionLedger / ToolRegistry
实现 seedj-infra-local、seedj-jdbc-postgresql
    账本的两套实现，同一份契约测试约束
    InMemoryDecisionLedger / PostgresDecisionLedger（表 agent_decision_chain、
    agent_decision_record，迁移由 AiSchemaCapability 执行）
装配 seedj-ai-starter
    注解、配置绑定与三个自动配置
    @EnableAIServer / AiProperties / AiGuardAutoConfiguration /
    DecisionPlaneAutoConfiguration / AiRuntimeAutoConfiguration
宿主 seedj-server/ai（可运行样板）
    只当决策面：plane.enabled=true、guard.enabled=false、runtime.enabled=false
    AiServer
```

`seedj-ai-starter` 依赖 `seedj-biz-starter`（拿 `CallerProvider`、`CommandCatalog` 与命令入口的线
格式），**`seedj-biz-starter` 不得反向依赖 `seedj-ai-starter`**，`core.ai` 也不得依赖 Spring 或
JDBC——两条都由 `seedj-it` 的 ArchUnit 规则守着。

## 启用：`@EnableAIServer`

```java
@SpringBootApplication
@EnableBizServer
@EnableAIServer
public class AiServer {

    public static void main(String[] args) {
        SpringApplication.run(AiServer.class, args);
    }
}
```

`@EnableAIServer` 与 `@EnableImServer` 同语义：**只做能力开关**。它 `@Import` 两个
`@Configuration`——biz 的启用标记与 AI 自己的 `AiEnablementConfiguration`——后者的唯一产物是
`AiEnabledMarker`。所有能力 bean 都挂在 `@ConditionalOnBean(AiEnabledMarker.class)` 后面，因此：

- 注解只声明「这台进程可能有 AI 能力」，不隐含组件扫描、不启动后台线程、不改安全链；
- 模块被依赖但宿主没写注解时，AI 能力一个都不装配（命令端点不会被默认开启的 guard 误拦）。

**它不在 `AutoConfiguration.imports` 里，这是刻意的。** 自动配置清单的语义是「classpath 上有就
生效」，而标记的语义是「注解写了才生效」。本模块被 `seedj-it` 依赖，一旦把
`AiEnablementConfiguration` 登记进清单，IAM / IM / HR 的测试上下文都会拿到标记，默认开启的 guard
会开始拦它们的 `POST /commands/{type}`——一处登记，全仓多个宿主同时坏掉。清单里只有三个能力
自动配置（guard / plane / runtime），它们各自门控在标记之后：

```text
cn.byteforce.seedj.starter.ai.autoconfigure.AiGuardAutoConfiguration
cn.byteforce.seedj.starter.ai.autoconfigure.DecisionPlaneAutoConfiguration
cn.byteforce.seedj.starter.ai.runtime.AiRuntimeAutoConfiguration
```

## 四个开关：总闸与三个角色

同一份 starter 可以只当决策面、只当执行侧，或三者同进程（T0 pilot 就是三者同进程，见
`seedj-it` 的 `PilotApplication`）。分角色部署时每个开关都显式写出来，不要靠默认值。

| 键 | 默认 | 装配什么 | 什么时候关 |
|---|---|---|---|
| `seedj.ai.enabled` | `true` | 总闸：连 `AiEnabledMarker` 都不产时三个角色全失效 | 想让进程完全不含 AI |
| `seedj.ai.plane.enabled` | **`false`** | 决策面 | 执行侧不需要签发 token |
| `seedj.ai.guard.enabled` | **`true`** | 执行侧 | 只当决策面 |
| `seedj.ai.runtime.enabled` | **`false`** | agent 循环 | 决策面/执行侧不跑循环 |

- **决策面**（`plane`）：`DecisionService`、`POST /v1/decisions`、`GET /v1/decisions/{id}`、outcome
  上报、`GET /.well-known/agent-decision-jwks.json` 与能力集（`CapabilityRegistry`）。
- **执行侧**（`guard`）：`ConfiguredToolRegistry`、`DecisionTokenIssuer`（供公钥）、
  `DecisionTokenVerifier` 与挂在 `POST /commands/*` 上的 `AgentGuardFilter`。只当决策面时关掉它：
  同进程既签发又验签，出了问题分不清哪一侧生效。
- **运行时**（`runtime`）：`DecisionClient`、`ToolInvoker`、`BoundedLoop`（后者还要求宿主提供
  `LlmGateway` bean，starter 不猜模型）。

`plane.enabled=true` 时 `ToolRegistry` 与 `DecisionTokenIssuer` 由决策面侧提供，`guard.enabled=true`
时由 guard 侧提供；两边都开则靠 `@ConditionalOnMissingBean` 收敛成同两个单例，签发的公钥与验签
用的公钥因此必然一致（`GuardPlaneCompositionTest` 钉住这一点）。

## 必需配置键

启动期校验放在会用它的那个 bean 的构造器里：缺哪个键，启动失败的消息就点名哪个键。

| 键 | 必填时机 | 说明 |
|---|---|---|
| `seedj.ai.plane.tenant` | `plane` 开，**启动期 UUID** | 租户只来自配置；账本分区与 token 都用它 |
| `seedj.ai.identity.actor` | `plane` 开，**启动期 UUID** | agent 自身身份；缺失即启动失败 |
| `seedj.ai.identity.on-behalf-of` | `plane` 开，**启动期 UUID** | 被代办的自然人；同上 |
| `seedj.ai.decision.audience` | 运行期 | token 的 `aud`；请求体也给了时以请求为准 |
| `seedj.ai.decision.kid` | 有默认值 | 密钥标识；未知 `kid` 的 token 一律拒绝 |
| `seedj.ai.decision.signing-key` | 生产必填 | Ed25519 私钥；空则临时密钥并 WARN |
| `seedj.ai.runtime.model.name` / `.version` | `runtime` 开，**启动期** | 决策请求与账本的 `model` 唯一来源 |
| `seedj.ai.tools[].command-type` / `.action-class` | `plane` 或 `guard` 开 | 工具白名单，两项都必填 |
| `seedj.ai.contracts[].id` / `.commands` | `plane` 开，**启动期** | 契约能力集，命令须先登记为工具 |
| `seedj.ai.plane.reconcile.datasource` | 可空 | 对账读的业务库 bean 名；空则用主数据源 |
| `seedj.ai.plane.reconcile.enabled` | `false` | 是否装配 60 秒定时对账 |
| `seedj.ai.plane.reconcile-ttl` | `10m` | 只核对早于 `now - ttl` 的 outcome |
| `seedj.ai.provider` | `postgres`（有数据源时） | 账本实现；非 `postgres` 时不装配数据库账本 |

启动期失败的原文（照抄进告警规则用）：

- `seedj.ai.plane.tenant 未配置：决策面的租户由配置钉死，不从请求读取`；
- `seedj.ai.identity.actor 未配置` / `... 不是 UUID`（`on-behalf-of` 同一条模板）；
- `seedj.ai.runtime.model.name`（或 `.version`）未配置：`runtime.enabled=true` 时必填；
- `seedj.ai.tools 的 <命令类型> 缺少 action-class`、`seedj.ai.tools 存在缺少 command-type 的条目`；
- `seedj.ai.contracts 的 <id> 声明了未在 seedj.ai.tools 登记的命令类型: <type>`。

`seedj.ai.plane.reconcile.datasource` 为空且宿主没有主 `DataSource` 时不算启动失败：outcome 上报
（`POST /v1/decisions/{id}/outcome`）返回 `503`，对账不装配，启动打一次 WARN。

`seedj.ai.tools[]` 与 `seedj.ai.contracts[]` 是列表，缩进一格都不要错：

```yaml
seedj:
  ai:
    tools:
      - command-type: ticket.create
        action-class: COMPENSABLE
      - command-type: ticket.comment
        action-class: REVERSIBLE
    contracts:
      # id 必须是 UUID 字符串：runtime 的 TaskContract.id 会被解析成 task_contract_id。
      - id: 018f2e3a-0000-7000-8000-00000000c017
        commands: [ticket.create, ticket.comment]
```

## `action-class` 的五档语义

动作分类是**命令的元数据**，由 `seedj.ai.tools[].action-class` 声明，不由调用方在请求里传入——
否则调用方能挑最宽松的一档逃过审批。取值只有五档，按「后果可恢复性」排序：

| 值 | 含义 | 审批口径 |
|---|---|---|
| `READ_ONLY` | 不改变任何状态 | 无需审批，可直接执行或缓存 |
| `REVERSIBLE` | 可由同一命令的逆操作撤销 | 无需人工审批，保留撤销入口 |
| `COMPENSABLE` | 不能直接撤销，需反向事务冲抵 | 需人工审批，留存补偿依据 |
| `IRREVERSIBLE_FINANCIAL` | 出账、扣款、开票等资金动作 | 强制人工审批 + 双人复核 |
| `IRREVERSIBLE_EXTERNAL` | 已送达外部系统、已推第三方 | 强制人工审批，先固化可见内容 |

T0 的强制力只到「token 里的 `action_class` 必须与注册表一致」（不一致即
`action_class_mismatch`），以及「两个不可逆档位要求 token 携带 `APPROVED` 审批快照，否则签不出
token」。审批流程本身还没交付（见 T0 明确不做）。

**没有 `forbidden` 这一档**：不进 `seedj.ai.tools[]` 就是禁止——白名单之外没有「允许但不推荐」的
中间态。`ActionClass.requiresApproval()` 只对两个 `IRREVERSIBLE_*` 返回 `true`，`COMPENSABLE` 按
业务策略审批，不在 T0 的强制路径上。

## 完整示例：一次写动作的完整链路

pilot 宿主（`seedj-it` 的 `PilotApplication`）一个进程同时当决策面、执行侧与运行时：

```java
@SpringBootApplication
@EnableBizServer
@EnableAIServer
public class PilotApplication { /* main */ }
```

```yaml
seedj:
  ai:
    plane:
      enabled: true
      tenant: 018f2e3a-0000-7000-8000-0000000b0007
      reconcile:
        enabled: false          # 用例直接调 ReconciliationJob#reconcile，不依赖调度
    guard:
      enabled: true
    runtime:
      enabled: true
      max-steps: 4
      model:
        name: pilot-model
        version: "1"
    identity:
      actor: 018f2e3a-0000-7000-8000-0000000a9e01
      on-behalf-of: 018f2e3a-0000-7000-8000-000000000118
    decision:
      audience: ticket-service
      kid: agent-decision-2026-11
    tools:
      - command-type: ticket.create
        action-class: COMPENSABLE
    contracts:
      - id: 018f2e3a-0000-7000-8000-00000000c017
        commands: [ticket.create]
```

### 1. 问决策面要一枚 token

```bash
curl -s http://localhost:8080/v1/decisions \
  -H 'authorization: Bearer <IAM 访问令牌>' \
  -H 'content-type: application/json' \
  -d '{
    "task_contract_id": "018f2e3a-0000-7000-8000-00000000c017",
    "conversation_id": "018f2e3a-0000-7000-8000-0000000e0001",
    "step": 1,
    "kind": "execute_tool",
    "command": {"type": "ticket.create",
                "payload": {"title": "打印机故障", "status": "open"},
                "targets": [], "expected_version": null},
    "rationale": "为本次故障建一张工单，便于后续追踪",
    "model": {"name": "pilot-model", "version": "1"}
  }'
```

```json
{"decision_id":"…","intent_record_id":"…","verdict":"allow","deny_rule":null,
 "token":"eyJhbGciOiJFZERTQSIsInR5cCI6ImFnZW50LWRlY2lzaW9uK2p3cyIsImtpZCI6…",
 "expires_at":"2026-11-17T09:41:12Z"}
```

顺序是契约：**先落 intent，再签发**。所以拿到 token 的每一次执行都能追到一条意图；拒绝
（`verdict=deny`）不签 token，但会先写 intent 再写 `outcome(rejected)`；签发失败则写
`outcome(not_issued, "signing_failed")` 后仍然 `500`——账本里不会留下「有意图无结局」的洞。

`decision_id` 由服务端生成，同时就是命令的幂等键（`idempotency_key` 恒等于它，请求里不接受）；
`intent_record_id` 是 token 里的 `ledger_intent_id`。请求里也不接受 `args_hash`、`actor`、
`on_behalf_of`、`tenant`：前者服务端按 `command` 重算，后三者只来自配置。

### 2. 带 token 提交命令

```bash
curl -s -i http://localhost:8080/commands/ticket.create \
  -H 'authorization: Bearer <IAM 访问令牌>' \
  -H 'x-agent-decision: eyJhbGciOiJFZERTQSIs…' \
  -H 'content-type: application/json' \
  -d '{"type":"ticket.create","payload":{"title":"打印机故障","status":"open"},
       "targets":[],"idempotency_key":"<decision_id>"}'
```

guard 只拦单段 `POST /commands/{type}`：`GET /commands/{id}`、`/queries/**`、`/actuator/**`、
`/.well-known/**` 一律放行。拒绝一律 fail-closed：

| 现象 | 原因 |
|---|---|
| `401` `unauthenticated` | 安全链没认出让 `CallerProvider` 拿到的调用方 |
| `401` `token_missing` | 没带 `x-agent-decision`（响应带 `WWW-Authenticate`） |
| `403` `token_expired` / `token_invalid` | 过期（`exp == now` 即过期）、结构或签名不对 |
| `403` `identity_mismatch` | `actor` / `on_behalf_of` / `tenant` 与请求侧事实不符 |
| `403` `command_mismatch` | token 钉死的命令类型被换过 |
| `403` `targets_mismatch` / `version_mismatch` | 目标集合或对象版本被换过 |
| `403` `args_hash_mismatch` | 载荷被改过（按未脱敏原文重算指纹） |
| `403` `action_class_mismatch` | 命令的动作分类低于 token 声明的那一档 |
| `403` `idempotency_key_mismatch` | `idempotency_key` 与 `decision_id` 不符 |
| `403` `aud_mismatch` | token 的受众不是本服务 |
| `400` `invalid_payload` | 不是 JSON、缺必填项、`targets` 含 `null`、body 超 1 MiB |

`expectedVersion` 的解析优先级与 `CommandController` 完全一致：`If-Match` 优先，两者都给且不等就是
`400`——否则 token 钉死的版本可以被「省略版本」绕过。

### 3. 让运行时替你走完这两步

宿主提供一个 `LlmGateway` bean，`runtime.enabled=true` 时 `BoundedLoop` 就装配好了：

```java
LoopResult result = loop.run(new TaskContract(contractId, conversationId, "记录一次故障工单", 4));
```

每一轮：问模型要一个提案 → `EXECUTE_TOOL` 才问决策面 → 放行才用 token 调
`POST /commands/{type}` → `FINISH` 收尾。上限取 `min(任务契约的 maxSteps,
seedj.ai.runtime.max-steps)`：任务可以更保守，不能更宽松。`EXECUTE_TOOL` 缺 `rationale` 时本地直接抛
`IllegalArgumentException`（不发一趟注定 `400` 的请求）。

## 安全与运维

- **token 不落库、不落日志。** 它只在两个地方出现：`POST /v1/decisions` 的响应体一次，与
  `x-agent-decision` 请求头。账本存 `args_hash` / `idem_fingerprint`（哈希）与可展示参数 `args`
  （T0 就是命令载荷本身），但从不存 token；
  守卫的拒绝说明只带 `kid` 与 `decision_id` 前 8 位；循环观测（`LoopResult.observations`）只有
  `step / kind / type / ok / commandId`。
- **JWKS 端点必须匿名可读**：`GET /.well-known/agent-decision-jwks.json`。执行侧按 token 里的
  `kid` 来取公钥，被鉴权挡在门外就等于端点不存在。注意 `seedj.biz.public-paths` 一旦设置就是
  **整体替换**默认公开路径，所以要连默认那几项一起列全（`seedj-server/ai` 的 `application.yml`
  就是这么写的：`/actuator/health`、`/actuator/info`、`/error`、`/api/public/**` 加 JWKS）。
- **密钥轮换与 `kid`**：新密钥换一个 `kid`，新旧 `kid` 在 JWKS 里重叠发布一段时间再下线旧的；未发布
  的 `kid` 一律拒绝（fail-closed），不做「试遍所有公钥」。
- **临时密钥的 WARN 是什么意思**：`seedj.ai.decision.signing-key` 为空时启动期生成一对 Ed25519
  密钥并打 WARN。它只让本地开发与测试开箱可跑：**重启后旧 token 全部验不过**，也不能跨实例共享。
  生产必须配置固定密钥。
- **`local` 与 `postgres` 两个 profile 的差异**（`seedj-server/ai`）：`local` 排除
  `DataSourceAutoConfiguration`、关闭审计、命令运行时降级、`dev-auth` 打开，账本由宿主内的
  `LocalDecisionLedgerConfiguration` 提供 `InMemoryDecisionLedger`（重启即丢，starter 故意不提供内存
  账本 bean）；`postgres` 用真实数据源、跑迁移、账本落 `agent_decision_chain` /
  `agent_decision_record`。两个 profile 都只开决策面（`guard` / `runtime` 关闭），生产上还要自己给锁
  实现（如 `seedj.lock.postgres.enabled=true`）。
- **账本只能追加**：`agent_decision_record` 的 `before update or delete` 触发器在数据库层拒绝改写，
  与 SPI 语义互为双保险。
- **guard 的装配顺序是安全约束**：过滤器 order 取
  `SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10`，即安全链之后、命令入口之前。放到安全链之前
  会让 `CallerProvider` 恒空、每个写命令都 `401`（`AgentGuardFilterTest` 有专门用例）。

## T0 明确不做

以下每条都是**已知缺口**，不是「做了但没写文档」。做评测或对外承诺时按这条清单扣分。

1. **策略求值（`Evaluate`）与 `policy_stale`。** T0 不做 coord 的策略求值，`GuardContext` 不携带策略
   head，因此守卫实现的是第 1–8、10 条加一条受众校验；`AiErrorCode.POLICY_STALE` 恒不被返回。等
   上游 `Evaluate` 返回实际使用的 bundle 版本（U8）后由 T1 补。
2. **审批（`Start` / `Approve`）、`decision_admission` 与不可逆动作的行级门禁。** token 能携带
   `APPROVED` 状态，但 T0 没有审批流程，也没有在数据库层按 `action_class` 拦不可逆动作；归 T2/T3。
3. **外部副作用的两段协议与 IM 回传。** 「先固化外部可见内容、执行后只做事实记录」没有实现；审批
   通知与动作回传（IM 的系统卡片）也没有。
4. **runtime 只对 `EXECUTE_TOOL` 走决策面。** `READ` / `FINISH` / `ASK_USER` / `ESCALATE` 是循环内
   转换，不调决策面、不落账、不签 token。原因：决策面的 intent 要求 `command` 必填，而 `Proposal` 只
   在 `EXECUTE_TOOL` 时携带 `CommandRef`。代价是 runtime 驱动的只读步骤不进账本；决策面自己的只读
   路径（宿主直接调用并带上 `command`）仍可用并已测。
5. **对账的跨租户扫描入口。** `PlaneReconciliationSource` 的默认实现
   `ConfiguredReconciliationSource` 返回空列表并在启动时 WARN，`DecisionLedger` 也没有「按租户/
   时间扫描」的读接口。所以 T0 的对账不是全量对账：它只核对宿主（或运维脚本）给出的 decision_id。
6. **`verify` 看不到链头之后的幽灵记录。** 校对从链头往下按序扫记录，直连 `INSERT` 绕过账本、没有
   推进链头的那类记录不在快照内。这是快照语义的直接推论，不是 bug——要发现它得比对表行数与链头
   `last_sequence`。
7. **`audit_entry` 与账本只能按 `command_id` 关联。** seedj 的审计条目 payload 固定为
   `{command_id, event_ids}`（`@Audited` / `AuditAppendRequest`），不写 `decision_id` /
   `ledger_intent_id`，所以 `select id from audit_entry where payload->>'command_id' = ?` 是对账唯一
   可用的关联方式：可回放，但不是按 `decision_id` 的直链。U9 之后应改为直取。
8. **`seedj-ai-starter` 自己的单测需要 Docker。** 账本契约（`PostgresDecisionLedgerTest`）、对账与
   决策面宿主测试都用 Testcontainers 起 `postgres:16-alpine`；没有 Docker 时这个模块的 `verify` 跑
   不完。纯规则测试不需要 Docker（如 `EvalBaselineTest`）。

## 已知仓库级问题（写给维护者）

`pom.xml` 的 spotless 配置把 `google-java-format` 这一步按 `jvm-version`
（`<lintSuppressions>`）静默抑制，而 Maven 跑在 JDK 25 上、GJF 1.22 不支持该版本——**GJF 实际从未
执行**。于是 `spotless:check` 只证明三件事：删掉未用 import、去掉尾空格、文件以换行结尾。

因此：

- 不要相信「spotless 绿」等于「格式合规」，也不要为了对齐 GJF 去全仓重排：本仓已是 AOSP 风格，
  重排会制造 import 顺序分裂。
- 新增代码的唯一要求是：≤100 列、与相邻文件同风格、不引入未用 import。
- 想在 CI 上真正跑 GJF，得连同 JDK 与 GJF 版本一起升级，那是独立的一次改动，不属于本分支。

## 相关文档

- [模块与装配](modules.md)
- [决策指南](decision-guide.md)：什么时候选 AI starter
- [上游诉求与收敛路径](ai-upstream.md)：U2/U3/U8/U9/U10/U11/U12 与 IM 的 D6/D7/D10
- [配置说明](configuration.md)
- [安全](security.md)
9. **账本的 `args` 在 T0 恒为空映射。** spec §6.2 要求 `args` 存**脱敏后**的可展示参数，而 T0 没有实现
   字段级脱敏（`ObjectSchema` 的 `writeOnly` 与命令专用字段都在命令注册表里，决策面没有接）。存原文会把
   `writeOnly` 字段写进哈希链账本，因此决策面选择留空：指纹信息由 `args_hash` 与 `idem_fingerprint`
   承担，账本不承载载荷文本。等 T1 接上按 schema 的脱敏后再补 `args`。
