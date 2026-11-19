# AI 装配：决策账本、DecisionToken、guard 与有界 agent 循环

`seedj-ai-starter` 把「agent 能不能动这条命令」变成一条可复核的链：决策面先落 intent 再签发
DecisionToken，执行侧 guard 用 `x-agent-decision` 头逐字核对请求，命令落库后由对账按链上事实补
`reconciled`。宿主只留一个 `@EnableAIServer` 与几行配置。

> 当前阶段：T0 的全部能力（决策账本、DecisionToken、执行侧 guard、决策面 HTTP、有界 agent 循环）
> 加 T1-local 的**策略版本钉死**（`PolicyRef` 进 intent 与 token，守卫校验不可逆动作的 head）、
> **LLM 适配层**（`seedj.ai.llm.*` + OpenAI 兼容的 native tool-calling，模型输出直接映射成
> `Proposal`）与 **T2-local 审批**（`seedj.ai.approval.*`，不可逆动作 pending → approve →
> grant）。**不含** coord 的实际策略求值（上游 U8 未落地）、coord 审批工作流适配器、
> 不可逆动作的行级门禁（T3）、外部副作用两段协议与 IM 回传，逐条见
> [明确不做的范围](#明确不做的范围)。

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
| `seedj.ai.plane.permission` | `plane` 开，**启动期非空白**（默认 `agent:decide`） | 调 `/v1/decisions`、读记录与上报 outcome 所需的权限名；空白即启动失败 |
| `seedj.ai.identity.actor` | `plane` 开，**启动期 UUID** | agent 自身身份；缺失即启动失败 |
| `seedj.ai.identity.on-behalf-of` | `plane` 开，**启动期 UUID** | 被代办的自然人；同上 |
| `seedj.ai.decision.audience` | **guard 开时必填**（启动期） | 本服务名，token 的 `aud` 比什么；**只能来自配置**，不从 `Host` 等请求头推。`plane` 侧可空：空则要求每条请求自带 `audience`，两者都缺那条 `execute_tool` 400 |
| `seedj.ai.policy.enabled` | 默认 `false` | 策略版本钉死：开则决策面写 `PolicyRef`、守卫校验不可逆动作；关则逐字保持 T0 |
| `seedj.ai.policy.engine` | 默认 `local` | `PolicyRef.engine`；与 `bundle`、`version` 一起构成受管策略包 |
| `seedj.ai.policy.bundle` | `policy.enabled=true` 时**必填** | 受管策略包名（T1 单包） |
| `seedj.ai.policy.version` | `policy.enabled=true` 时**必填** | 当前 head 版本 |
| `seedj.ai.policy.epoch` | 可空 | 当前纪元；给了就必须与 token 里的 `epoch` 相等 |
| `seedj.ai.policy.verify` | `irreversible` | `none` 只钉版本、不校验；`irreversible` 只对两个不可逆档位强制 |
| `seedj.ai.decision.kid` | 有默认值 | 密钥标识；未知 `kid` 的 token 一律拒绝 |
| `seedj.ai.decision.signing-key` | 生产必填 | Ed25519 私钥；空则临时密钥并 WARN |
| `seedj.ai.runtime.model.name` / `.version` | `runtime` 开，**启动期** | 决策请求与账本的 `model` 唯一来源 |
| `seedj.ai.llm.enabled` | 默认 `false` | 是否装配 OpenAI 兼容的 LLM 客户端；关且宿主没有 `LlmGateway` bean 时循环不装配（启动 WARN） |
| `seedj.ai.llm.base-url` / `.model` | `llm.enabled=true` 时**必填** | API 根（如 `https://api.openai.com/v1`，客户端补 `/chat/completions`）与模型名 |
| `seedj.ai.llm.api-key` | 可空 | 非空时只进 `Authorization: Bearer`；不进日志与 `toString()` |
| `seedj.ai.llm.temperature` / `.max-output-tokens` | 可空 | 不配就不发送，由服务端默认 |
| `seedj.ai.llm.timeout` | `30s` | 单次请求的连接与读取超时 |
| `seedj.ai.llm.max-attempts` | `2` | 单步最大尝试次数（含首次）；只对传输错误、429、5xx 重试 |
| `seedj.ai.llm.tool-choice` | `required` | `required` / `auto`；端点不支持强制工具调用时改 `auto` |
| `seedj.ai.tools[].command-type` / `.action-class` | `plane` 或 `guard` 开 | 工具白名单，两项都必填 |
| `seedj.ai.contracts[].id` / `.commands` | `plane` 开，**启动期** | 契约能力集，命令须先登记为工具 |
| `seedj.ai.plane.reconcile.datasource` | 可空 | 对账读的业务库 bean 名；空则用主数据源 |
| `seedj.ai.plane.reconcile.enabled` | `false` | 是否装配 60 秒定时对账 |
| `seedj.ai.plane.reconcile-ttl` | `10m` | 只核对早于 `now - ttl` 的 outcome |
| `seedj.ai.provider` | `postgres`（`matchIfMissing=true`） | **不是 `AiProperties` 的绑定项**：它是 `seedj-jdbc-postgresql` 的 `PostgresAiAutoConfiguration` 读的开关。等于 `postgres` 时装配数据库账本；其它值让该自动配置整体退出，而 starter 不提供内存账本 bean——那种宿主必须自备 `DecisionLedger` bean |

启动期失败的原文（照抄进告警规则用）：

- `seedj.ai.decision.audience 未配置：guard 用它判定 token 的 aud（防跨服务重放），不能从请求头推断`；
- `seedj.ai.plane.permission 未配置：决策面必须校验调用方权限（默认 agent:decide）`；
- `seedj.ai.plane.tenant 未配置：决策面的租户由配置钉死，不从请求读取`；
- `seedj.ai.identity.actor 未配置` / `... 不是 UUID`（`on-behalf-of` 同一条模板）；
- `seedj.ai.runtime.model.name`（或 `.version`）未配置：`runtime.enabled=true` 时必填；
- `seedj.ai.tools 的 <命令类型> 缺少 action-class`、`seedj.ai.tools 存在缺少 command-type 的条目`；
- `seedj.ai.contracts 的 <id> 声明了未在 seedj.ai.tools 登记的命令类型: <type>`。

`seedj.ai.decision.audience` 在 guard 侧没有默认值也没有回退：早先的实现会在它为空时用
`request.getServerName()`，而 `Host` 是攻击者可控的——换个 `Host` 就能把发给 A 服务的 token 重放到 B
服务。所以 guard 开着就要求配置它；只当决策面时把 `seedj.ai.guard.enabled` 设成 `false`。

### 身份与租户的表示必须逐字一致

token 里的 `tenant` / `actor` 来自决策面的配置（`seedj.ai.plane.tenant`、`seedj.ai.identity.actor`），
guard 拿它们与执行侧的**运行时事实**逐字比较（`Caller.tenantId()`、`CallerIdCodec` 解析出的 actor）。
三者不是同一套表示时，每个写命令都会 `403 identity_mismatch`，而且账本上看起来「一切正常」：

- `seedj.ai.plane.tenant` 绑定成 `UUID`，所以它**永远是 UUID 字符串**（例如
  `018f2e3a-0000-7000-8000-0000000b0007`）；而执行侧 `Caller.tenantId()` 是**不透明字符串**，seedj 的
  默认值是 `default`。guard 逐字比较两者，因此：**IAM 令牌必须带这个租户 id**（`tenant_id` claim 就是
  同一个 UUID 字符串），否则调用方租户会落到 `default`、与 plane 的 UUID 永远对不上。这也意味着
  「plane 配一个 `default` 之外的普通名字」不可行——`default` 与任何 UUID 都比不上，只能让 IAM 发同一个
  UUID。**两边表示不同 = 100% 403，与签名、密钥都无关。**
- `seedj.ai.identity.actor` 必须是**执行侧 `CallerIdCodec` 解析出的同一个 UUID**：默认 `uuid()` 要求
  调用方 `id` 本身就是 UUID；换成 `nameUuid()` 的部署要让 guard 与决策面共用同一个 codec 实现。

`identity_mismatch` 的 `detail` 会带上期望值与实际值（`expected actor … tenant …, got actor … tenant …`），
两者都不是秘密，第一次部署时先看这条。

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
token」。T2-local 已把审批流程接上：开启 `seedj.ai.approval.enabled` 后，决策面对不可逆动作返回
`pending_approval`，批准后用 `/grant` 取 token；没有审批配置时直接 deny（`APPROVAL_UNAVAILABLE`），
不会签出无审批 token。

**没有 `forbidden` 这一档**：不进 `seedj.ai.tools[]` 就是禁止——白名单之外没有「允许但不推荐」的
中间态。`ActionClass.requiresApproval()` 只对两个 `IRREVERSIBLE_*` 返回 `true`，`COMPENSABLE` 按
业务策略审批，不在 T0 的强制路径上。

## 策略版本钉死（T1-local）

T0 的 token 里 `policy` 恒为 `PolicyRef.none()`。开启策略后，决策面在落 intent 之前求值一次，把
`engine / bundle / bundle_version / epoch / verdict` 写进 **intent 与 token**（同一个值），守卫对
`IRREVERSIBLE_*` 动作比对当前 head——账本因此能回答「这一步是哪一版策略判的」，而不只是「哪一版模型判的」。

```yaml
seedj:
  ai:
    policy:
      enabled: true
      engine: local                 # 默认值
      bundle: ticket-policy
      version: 3
      epoch: 41                     # 可空；引擎不提供纪元时省略
      verify: irreversible          # none | irreversible
```

**求值顺序**（顺序是契约）：

1. 校验请求与契约能力；契约拒绝不调用求值器，intent 里写 `none()`——确定的拒绝不该被一次可能失败的
   求值改写成 500；
2. 调 `PolicyEvaluator.evaluate`，得到 `PolicyRef`；
3. 写 intent（`policy` 进哈希链）；
4. `verdict` 不是 `allow` → 写 `outcome(rejected, POLICY_DENY)`，不签 token；
5. `allow` 且 `execute_tool` → 同一个 `PolicyRef` 进 token 后签发。

`not_evaluated`（`PolicyRef.none()` 的结论）表示「策略层未参与」，按 T0 放行；其它任何非 `allow` 结论
——包括引擎返回的未知值——一律 `POLICY_DENY`。求值器抛异常时请求失败且**不落 intent**：求值失败不是
决策，不能伪造一条意图。

**守卫判定**（只对 `ActionClass.requiresApproval()` 为 true 的两档）：

| 条件 | 结果 |
|---|---|
| `seedj.ai.policy.verify=none`，或动作是 `READ_ONLY` / `REVERSIBLE` / `COMPENSABLE` | 跳过比对 |
| token 无 `policy`、`bundle` 或 `bundle_version` 缺失 | `policy_unavailable` |
| 本部署没有该 `engine` + `bundle` 的 head | `policy_unavailable` |
| head 提供 `epoch` 而 token 没有 | `policy_unavailable`（无法确认新鲜度） |
| `bundle_version` 与 head 不等，或双方 `epoch` 不等 | `policy_stale` |
| 全部相等 | 放行 |

**端口**：`PolicyEvaluator` 与 `PolicyHeadSource` 都在 `cn.byteforce.seedj.starter.ai.policy`，默认实现
读配置，宿主 bean 优先（`@ConditionalOnMissingBean`）。coord 的 `Evaluate` 返回实际求值版本（上游 U8）
之后，适配器只需实现这两个端口，token、账本、守卫都不改。

**这一版不保证什么**：head 来自配置，所以 `policy_stale` 保证的是「token 与配置声称的当前版一致」，
不是「与 coord 当时判定用的那一版一致」。后者要等 U8。

## 配置 LLM：native tool-calling

`LlmGateway` 是循环与模型之间唯一的端口。打开 `seedj.ai.llm.enabled` 后 starter 自己装配一个 **OpenAI 兼容**
的 Chat Completions 客户端：模型通过原生 tool-calling 直接给出下一步，网关把它映射成 `Proposal`。

```yaml
seedj:
  ai:
    llm:
      enabled: true
      base-url: https://api.openai.com/v1   # 本地 vLLM/Ollama 换成自己的 /v1
      api-key: ${SEEDJ_AI_LLM_API_KEY}      # 可空；本地常不收鉴权
      model: gpt-5-mini
      temperature: 0.2                      # 可空
      max-output-tokens: 1024               # 可空
      timeout: 30s
      max-attempts: 2
      tool-choice: required
```

**工具协议**：请求里带一个 `execute_command` 和三个终结工具——

| 工具 | 参数 | 映射 |
|---|---|---|
| `execute_command` | `command_type`（`seedj.ai.tools[]` 的枚举，按任务契约过滤）、`payload`、`rationale`、可选 `targets` / `expected_version` | `EXECUTE_TOOL`；`action_class` 来自配置，不读模型 |
| `finish` | `reason` | `FINISH` |
| `ask_user` | `reason` | `ASK_USER` |
| `escalate` | `reason` | `ESCALATE` |

任务契约在 `seedj.ai.contracts[]` 里时，`command_type` 的枚举只保留该契约允许的命令；契约未知时用全部
`seedj.ai.tools[]`。

**fail-closed**：没有 tool_call、一次多个 tool_call、未知工具或命令、参数不是 JSON、`execute_command` 缺
`rationale` 或超过决策面的 1KB 上限、`targets` 不是 UUID —— 一律抛 `LlmException`。网关不会把坏输出降级成
`FINISH`：一次错误的收尾比一次失败更危险。

**重试**只覆盖传输错误、429 与 5xx（`max-attempts`，默认 2 次尝试）；4xx 立即失败——配置错重试没有意义。

**与 runtime 的关系**：`seedj.ai.llm.enabled=true` 提供 `LlmGateway` bean，`BoundedLoop` 因此装配；宿主也可以
自己提供 `LlmGateway` bean 覆盖它（非 OpenAI 协议走这条路径）。两者都没有时循环不装配，启动日志会 WARN 并点名
`seedj.ai.llm.enabled` 与 `LlmGateway`。

**这一层不做的**：流式响应、把工具结果作为多轮 `tool` 消息回灌（观测由循环以文本传回）、按命令 schema 生成
`payload` 的强约束（`CommandCatalog`/`ObjectSchema` 没在 runtime 暴露）、多 provider 原生协议。需要这些时在
宿主实现 `LlmGateway`，循环与决策链不需要改。

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

打开 `seedj.ai.llm.enabled`（或宿主提供一个 `LlmGateway` bean），`runtime.enabled=true` 时 `BoundedLoop`
就装配好了：

```java
LoopResult result = loop.run(new TaskContract(contractId, conversationId, "记录一次故障工单", 4));
```

每一轮：问模型要一个提案 → `EXECUTE_TOOL` 才问决策面 → 放行才用 token 调
`POST /commands/{type}` → 工具确认成功就 `POST /v1/decisions/{id}/outcome` 上报 → `FINISH` 收尾。上限取
`min(任务契约的 maxSteps, seedj.ai.runtime.max-steps)`：任务可以更保守，不能更宽松。`EXECUTE_TOOL` 缺
`rationale` 时本地直接抛 `IllegalArgumentException`（不发一趟注定 `400` 的请求）。

**上报是运行时自己的责任，且是尽力而为。** 没有它，每个 runtime 驱动的写动作都会在账本上永久停在「有
intent 无 outcome」——四件套只在手工调 `/outcome` 的用例里成立。所以工具返回 `ok` 之后循环会主动上报；
上报失败**不让循环失败**（命令已经执行成功，把循环判失败会抹掉一次真实发生的副作用），只打一条带
`decision_id` 的 WARN 然后继续。代价很明确：**那一次决策就进入「有决策无效果」，T0 的对账无法自动修复
它**——`PlaneReconciliationSource` 默认实现返回空列表（见[第 5 条](#明确不做的范围)），没有「按租户/时间扫描
缺 outcome 的 intent」的入口。要收敛得靠 T1 的扫描接口，或运维按告警手工补。

## 安全与运维

- **token 不落库、不落日志。** 它只在两个地方出现：`POST /v1/decisions` 的响应体一次，与
  `x-agent-decision` 请求头。账本存 `args_hash` / `idem_fingerprint`（哈希）与可展示参数 `args`
  （T0 恒为空映射，见[第 9 条](#明确不做的范围)），但从不存 token；**单条 token 超过 4096 字节直接拒绝**
  （不截断——截断只会把一个坏凭据变成另一个坏凭据），`header.typ` 也必须是 `agent-decision+jws`；
  守卫的拒绝说明只带 `kid` 与 `decision_id` 前 8 位；循环观测（`LoopResult.observations`）只有
  `step / kind / type / ok / commandId`。
- **outcome 记录里的 `source` 有三种取值**：`runtime_reported`（运行时执行后上报）、`plane_decided`
  （决策面自己的结论：拒绝、无需执行、签发失败）、`reconciled`（事后与链上事实核对）。来源决定可信度：
  核对可以修正「结果未知」，不能改写运行时已观测到的成功或失败。
- **outcome 是 first-wins 且不可改。** 同一决策的第二次上报只返回首条记录，账本的 `before update or
  delete` 触发器也拒绝改写。T0 的 `reconcile` 只修 `ok + audit_entry_id 为空` 这一类（补链目标识），
  早期误报（例如上报时命令刚提交、被记成 `command_not_found`）**不会被自动覆盖**——要修只能追加
  `reconciled` 观察，或等 T1 的对账扫描入口。
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
- **决策面只做认证 + 权限校验，不是「谁都能用」。** `/v1/decisions`、`/v1/decisions/{id}` 与
  `/v1/decisions/{id}/outcome` 都要求调用方
  带 `seedj.ai.plane.permission`（默认 `agent:decide`），否则 `403`。只认证不授权等于「任何 IAM 用户
  都能以配置的 agent 身份拿一枚可执行写的 token」。生产上决策面还必须**只对 runtime 的网络可达**
  （私网 / mTLS / 网关白名单），别把它暴露在公网入口后面。
- **guard 的装配顺序是安全约束**：过滤器 order 取
  `SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10`，即安全链之后、命令入口之前。放到安全链之前
  会让 `CallerProvider` 恒空、每个写命令都 `401`（`AgentGuardFilterTest` 有专门用例）。

## 审批（T2-local）

`IRREVERSIBLE_FINANCIAL` / `IRREVERSIBLE_EXTERNAL` 的 `execute_tool` 不再同步签 token，而是走四步：

1. `POST /v1/decisions`：校验 → 落 intent → 启动审批工作流 → 追加 `APPROVAL(PENDING)`，返回
   `{"verdict":"pending_approval","decision_id":…,"token":null}`；
2. `GET /v1/decisions/{id}/approval`：看状态、截止时间与审批人；
3. `POST /v1/decisions/{id}/approval`：`{"action":"approve"|"reject","comment":…}`；
4. `POST /v1/decisions/{id}/grant`：已批准且未过期才签发 token，响应 `{"token":…}`。

```yaml
seedj:
  ai:
    approval:
      enabled: true
      approvers:
        - 018f2e3a-0000-7000-8000-000000000118
      deadline: 4h
      allow-self-approve: false
      # 定义默认 seedj-ai/agent-approval@1.0，通常不用改
```

- **审批引擎是端口**：只依赖 `WorkflowProvider`。本地加 `seedj-workflow-local`（`seedj.workflow.provider=memory`
  或默认）即可；`approval.enabled=true` 但没有 `WorkflowProvider` bean 时**启动失败**并点名。
- **审批人**：`approvers[]` 至少一个 UUID；审批端点的调用方 `id` 必须在这个列表里，且
  `allow-self-approve=false` 时不能等于 `identity.on-behalf-of`（发起人）。只看
  `seedj.ai.plane.permission` 不够。
- **绑定**：审批快照的 `argsHash` 必须等于 intent 的 `argsHash`；grant 每次都重读账本，参数变了不可能
  复用旧审批。
- **拒绝/过期**：驳回追加 `APPROVAL(REJECTED)` + `outcome(rejected, APPROVAL_REJECTED)`；超时追加
  `APPROVAL(EXPIRED)` + `outcome(rejected, APPROVAL_EXPIRED)`。两者之后 grant 都是 409。
- **运行时**：`BoundedLoop` 遇到 pending 返回 `LoopResult.Outcome.PENDING_APPROVAL`（`detail` 是
  `decision_id`），不调工具、不阻塞等人类；人在审批端点批准后，宿主用 `DecisionClient.grant(id)` 取
  token 再提交命令。`DecisionGrant.allowed()` 不会把 pending 当放行。
- **通知**：默认只记日志；有 IM 依赖时由卡片适配器覆盖（见 IM 指南）。
- **coord 适配**：本版审批事实来自 `WorkflowProvider`，coord 的 `Evaluate` 版本（U8）与
  workflow COMMITTED 之后只需换 Provider/端口实现，token、账本、guard 不改。

## 准入、RLS 与审计直链（T3-local）

守卫校验 token 之后、命令执行之前，会把这次决策写成一条 `agent_decision_admission`（decision_id /
command_type / targets / args_hash / actor / expires_at），并在命令事务内设置
`agent.decision_id` / `agent.command_type` / `agent.args_hash` 三个事务级 GUC。不可逆表的 RLS 策略用
`agent_admission_ok(command_type, args_hash)` 核对：没有 admission、已过期或参数不一致，直连写入被数据库拒绝。

```yaml
seedj:
  ai:
    guard:
      admission:
        enabled: true      # 有 DataSource 时默认开
        ttl: 5m            # 实际取 min(token.exp, now + ttl)
```

- **直连兜底**：`app_rw` 对不可逆表保留 DML，但受 RLS 约束；`guard_rw` 只写 admission。宿主按
  `alter table ... enable row level security` + `force row level security` + 一条
  `using/with check (agent_admission_ok(...))` 策略接入；`seedj-it` 的 `AdmissionRlsIT` 是可复制样例。
- **审计直链**：guard 把 `decision_id` / `ledger_intent_id` 带进 `DecisionContext`，命令审计 payload
  追加这两个键；`reconcile.sql` 的 `audit_by_decision` 优先按 `decision_id` 直取，历史条目回退
  `command_id`。审计迁移 V3 建了表达式索引。
- **全量对账**：`LedgerReconciliationSource` 从账本扫描「outcome=ok 但 audit_entry_id 为空」；
  `reconciliationJob.reconcileOnce` 另扫「有 intent 无 outcome」并追加
  `reconciled(unknown, outcome_missing)`。两者都按 reason 去重。
- **幽灵记录**：`DecisionLedger.audit(partition)` 除链校对之外比对表行数与链头；
  `rowCount != headSequence` 即绕过账本直插的幽灵行。`verify` 保持只校对链头快照的语义。
- **没有 DataSource**：内存宿主不装配 admission，启动 WARN；`agent.decision_id` 也不会设置，RLS 不适用。

## 明确不做的范围

以下每条都是**已知缺口**，不是「做了但没写文档」。做评测或对外承诺时按这条清单扣分。

1. **coord 的实际策略求值（`Evaluate`）。** T1-local 已交付：决策面钉版本、守卫校验 head、`policy_stale`
   与 `policy_unavailable` 都可用；但求值结果来自配置而不是 coord——`PolicyClient.evaluate` 仍只返回
   不透明 `byte[]`，没有「本次判定实际使用的那一版」（U8）。等 U8 落地后替换两个端口即可。
2. **coord 侧的 `Start` / `Approve` 适配器。** T2-local 已用 `WorkflowProvider` 端口接上本地审批；
   coord 工作流 COMMITTED 后换 Provider 实现即可。T3-local 的 `decision_admission` + RLS 兜底已交付；
   宿主需按指南给不可逆表套策略。
3. **外部副作用的两段协议与 IM 回传。** 「先固化外部可见内容、执行后只做事实记录」没有实现；审批
   通知与动作回传（IM 的系统卡片）也没有。
4. **runtime 只对 `EXECUTE_TOOL` 走决策面。** `READ` / `FINISH` / `ASK_USER` / `ESCALATE` 是循环内
   转换，不调决策面、不落账、不签 token。原因：决策面的 intent 要求 `command` 必填，而 `Proposal` 只
   在 `EXECUTE_TOOL` 时携带 `CommandRef`。代价是 runtime 驱动的只读步骤不进账本；决策面自己的只读
   路径（宿主直接调用并带上 `command`）仍可用并已测。
5. **跨租户扫描。** `DecisionLedger` 的扫描接口按租户与时间过滤；跨租户全量扫描仍未开放（决策面
   租户由配置钉死，一次只处理一个租户）。
6. **`verify` 看不到链头之后的幽灵记录；用 `audit`。** `verify` 仍只校对链头快照；`audit(partition)`
   比对表行数与 `last_sequence`，能发现直连 `INSERT` 且未推进链头的行。
7. **`audit_entry` 与账本已可按 `decision_id` 直链。** 命令审计 payload 追加 `decision_id` /
   `ledger_intent_id`；对账优先按 `decision_id` 直取，历史条目回退 `command_id`。审计链契约本身
   （`@Audited` 的 `{command_id,event_ids}`）不变，直链是附加键。
8. **`seedj-ai-starter` 自己的单测需要 Docker。** 账本契约（`PostgresDecisionLedgerTest`）、对账与
   决策面宿主测试都用 Testcontainers 起 `postgres:16-alpine`；没有 Docker 时这个模块的 `verify` 跑
   不完。纯规则测试不需要 Docker（如 `EvalBaselineTest`）。
9. **LLM 适配层只有一种协议。** OpenAI 兼容的 Chat Completions + tool-calling；流式、结构化输出强约束、
   多 provider 原生格式、按命令 schema 生成 payload 都不做。非 OpenAI 协议由宿主实现 `LlmGateway`。
10. **账本的 `args` 在 T0 恒为空映射。** spec §6.2 要求 `args` 存**脱敏后**的可展示参数，而 T0 没有实现
   字段级脱敏（`ObjectSchema` 的 `writeOnly` 与命令专用字段都在命令注册表里，决策面没有接）。存原文会把
   `writeOnly` 字段写进哈希链账本，因此决策面选择留空：指纹信息由 `args_hash` 与 `idem_fingerprint`
   承担，账本不承载载荷文本。等 T1 接上按 schema 的脱敏后再补 `args`。

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
