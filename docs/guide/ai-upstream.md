# AI starter 的上游诉求与 T0 收敛

T0 不是「设计落地」，而是「把能落地的那一半落地，并把卡住的部分写清楚」。这份清单是给上游的
需求单，也是给我们自己验收的口径：每条都写明**不做的代价**与**T0 现在怎么绕**，不写「以后再说」。

依赖关系（决定排期）：**U8 是 T1 的前置**；**U9 是「账本 ↔ 审计链双向互链」的前置**（T0 只做到
单向，T3 的对账要靠它收尾）；U2、D7 是 T2 的前置；U10 是身份模型的前置。

## 摘要

| # | 对象 | 诉求 | 卡住谁 | T0 怎么绕 |
|---|---|---|---|---|
| U2 | seedj / coord | 审批读面稳定：`GetStatus.input` 可读不可变 | T2 | 不调审批，只落账本 |
| U3 | coord | 删除与保留的关系写进文档，快照先落账 | T2 | 审批快照一节，T0 无审批 |
| U8 | coord | `Evaluate` 返回 bundle 版本与 epoch | **T1** | 不比对策略版本 |
| U9 | seedj | 审计条目自定义 payload，支持结果条目 | **T0 互链 / T3** | 按 `command_id` 关联 |
| U10 | seedj IAM | `effectiveProxies` 稳定契约 | 身份模型 | 身份由配置钉死 |
| U11 | seedj | `CommandAdmission` SPI 与命令读面补字段 | 进程内直调 | 守卫停在 HTTP 层 |
| U12 | seedj | PKI 签名身份支持 Ed25519 | 密钥生命周期 | 自带密钥，只发布 JWKS |
| D6 | seedj IM | 系统通知/卡片为一等类型 | T2 通知 | 无通知，人工看台账 |
| D7 | seedj IM | 卡片动作回传命令 | T2 审批 | 无回传，审批不在 T0 |
| D10 | seedj IM | 投递指标维度（actor/tenant） | 可观测性 | 无投递，只记应用日志 |

## U2（seedj / coord）：审批读面必须可读且不可变

- **诉求**：审批绑定 `args_hash` 的唯一来源是 coord 的 `GetStatus.input`（`bytes`），它必须可读且不
  可变；建议给 `GetInstanceDetail` 补 `input` 字段（现状没有），并用契约测试锁住。
- **不做的代价**：`args_hash` 绑定只是口头约定——决策面无法证明「人批准的就是 agent 要执行的那个
  参数」。这是 T2 的准入条件。
- **T0 怎么绕**：不调审批。`ActionClass.requiresApproval()` 只对两个 `IRREVERSIBLE_*` 返回 `true`，而
  这两档在 T0 没有可用的审批来源，所以 T0 的 `seedj.ai.tools[]` 不该声明它们（声明了也只能拿到
  「审批快照缺失」）。审批快照进账本是 T2 的事。

## U3（coord）：删除与保留的关系

- **诉求**：`DeleteInstance` 与审计/账本保留的关系写进接入文档；终态删除不可回滚；审批快照必须在
  删除竞态之前落账本。
- **不做的代价**：合规评审时会出现「审批记录被清了」这类无法回答的问题。
- **T0 怎么绕**：T0 没有审批实例，也就没有删除竞态。账本自身是只追加的（数据库层触发器拒绝
  `UPDATE` / `DELETE`），所以「谁删了审批」这个问题在 T0 不会出现，T2 才需要。

## U8（coord）：`Evaluate` 返回实际使用的策略版本

- **诉求**：策略求值结果要带 `bundle_version` + `epoch`（至少主 bundle；多 bundle 时返回集合）；另需
  一个读 server KV 的策略 head 入口（`GetPolicyHead{epoch, bundles[]}`）。
- **不做的代价**：token 无法钉死策略版本，**T1 不可实现**：不可逆动作的「按这版策略判的」这件事没有
  载体，`policy_stale` 也就无从判定。
- **T0 怎么绕**：不比对策略。决策面签出的 token 里 `policy` 恒为 `PolicyRef.none()`，守卫实现的是
  第 1–8、10 条校验加一条受众校验；`AiErrorCode.POLICY_STALE` 恒不被返回。这不是「策略不重要」，
  是「没有版本可钉」。

## U9（seedj）：自定义审计条目与结果条目

- **诉求**：`@Audited` 的 payload 不再固定为 `{command_id, event_ids}`，允许带
  `decision_id` / `ledger_intent_id`；`AuditAppendRequest.action` 不再绑死命令类型；支持结果/outcome
  条目（一次执行既记「要做什么」也记「做成了什么」）。
- **不做的代价**：**链 ↔ 账本只能单向回放**。审计链上找不到 `decision_id`，所以「有效果无决策」只能
  靠 `command_id` 猜；「有决策无效果」靠对账补。这是 T0 pilot 的互链缺口，也是 **T3 对账的前置**。
- **T0 怎么绕**：`seedj-jdbc-postgresql` 的对账查询按
  `select id from audit_entry where payload->>'command_id' = ?` 关联（见
  `db/ai/reconcile.sql` 的 `audit_by_command`）。可回放、可核对，但**不是按 `decision_id` 的直链**；
  且 `audit_entry` 上没有 `payload->>'command_id'` 的表达式索引，T0 量级是顺序扫。U9 落地后这两条
  都改成按 `decision_id` 直取，改动点只有那个 SQL 文件。

## U10（seedj IAM）：`effectiveProxies` 的稳定契约

- **诉求**：决策面按 agent principal + `as_of` 解析 `ProxyBinding`（`IamBindingStore.effectiveProxies`）
  需要一份稳定契约：scope 如何匹配、歧义如何裁、无绑定如何 fail-closed；runtime 不得传权威用户 ID。
- **不做的代价**：身份停在「服务身份 + `on_behalf_of` 由配置钉死」，审计里的 actor/被代理人只能靠
  部署清单保证，换不了、也证不了。
- **T0 怎么绕**：`seedj.ai.identity.actor` 与 `on-behalf-of` 是**启动期必填 UUID**，决策面不接受请求
  自称的主体；guard 用调用方 + `CallerIdCodec` 算出 actor 与 token 逐字比对。

## U11（seedj）：`CommandAdmission` SPI 与命令读面

- **诉求**：给 `CommandRuntime.submit` 之前开一个 SPI，能拿到完整的 `CommandSubmission`（进程内调用者
  也能被拦）；`GET /commands/{id}` 返回 `type` / `idempotency_key` / `payload_fingerprint`，让守卫与
  对账不必回读请求体。
- **不做的代价**：守卫停在 HTTP 层，同进程直接 `catalog.execute(...)` 可以完全绕过决策 token——T0 的
  `AiBypassIT` 就是把这个缺口写成用例，而不是假装它不存在。
- **T0 怎么绕**：guard 是 `AgentGuardFilter`，只拦 `POST /commands/{type}`；绕过路径由用例记录（直连
  `CommandCatalog` 不经过 guard）。对账所需的 `payload_fingerprint` 由 `command_record` 表直接读。

## U12（seedj）：PKI 签名身份支持 Ed25519

- **诉求**：签名身份（密钥、轮换、吊销）能走 PKI，接受 Ed25519 私钥，或提供一个通用的 JWS 签名接口。
- **不做的代价**：`seedj.ai.decision.signing-key` 只能自带密钥，轮换与吊销脱离 PKI——生产上要么手工
  轮换，要么接受「密钥在配置文件里」。
- **T0 怎么绕**：EdDSA（Ed25519）签名与 JWKS 发布都在 starter 内实现：`DecisionTokenIssuer` 支持
  base64url 的 32 字节 seed 或 PKCS#8；公钥经 `GET /.well-known/agent-decision-jwks.json` 发布，
  换密钥用新 `kid` 重叠发布。临时密钥只在没配 `signing-key` 时生成并 WARN。

## D6（seedj IM）：系统通知 / 卡片消息是一等类型

- **诉求**：结构化 payload + 模板 + 纯文本降级；渲染失败降级为纯文本而不是丢消息。
- **不做的代价**：**T2 的审批通知没有载体**，业务方只能自己拼 JSON 塞进文本消息。
- **T0 怎么绕**：T0 不发任何通知。审批不存在，IM 也不在链路里；决策与结局只在账本与对账里。

## D7（seedj IM）：动作回传

- **诉求**：卡片按钮 → seedj `Command` → coord `Approve` / `Reject`，幂等键取 `message_id + action_id`；
  主体是 IAM 用户本人，不需要 `on_behalf_of`。
- **不做的代价**：不能回传动作的触达层只是推送通道，**审批闭环（T2）无法成立**。
- **T0 怎么绕**：没有审批，也就没有回传。T0 的写入路径只有 `POST /commands/{type}` 加
  `x-agent-decision`。

## D10（seedj IM）：投递指标维度

- **诉求**：D8 的投递指标需要 `actor` / `tenantId` 维度——要么放宽 seedj 指标标签白名单，要么允许 IM
  用独立 meter 前缀。
- **不做的代价**：投递可观测性在最底层被禁，出问题只能翻应用日志（且无法按 actor/租户聚合）。
- **T0 怎么绕**：T0 不做投递，只记应用日志；守卫拒绝计数按 `AiErrorCode` 的 `wire()` 名分类，不做
  标签维度。

## 与本分支的关系

以上没有一条在 T0 被「绕过成不存在」：能绕的都在代码里有对应的收窄点（`reconcile.sql` 的关联方式、
`PolicyRef.none()`、`PlaneReconciliationSource` 的默认空实现、guard 的 HTTP 层拦截），跑不动的都在
[AI 装配 §T0 明确不做](ai-starter.md#t0-明确不做)里逐条列出。写对外承诺时以那两处为准。
