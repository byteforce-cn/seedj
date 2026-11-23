# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `seedj-workflow-coord`：`WorkflowProvider` 到 coord workflow v2 的全量适配器（定义编译进
  `document.tags`、round 级读面、12 个动作 + cancel/terminate、coord KV sidecar、
  `seedj.workflow.provider=coord` 装配）。契约 TCK 31 个用例跑真 coord 集群；coord 测试
  基线升到 0.4.6（Resume 时间预算取步骤 `timeout`）。
- `seedj-infra-coord`：`CoordProperties.resolveNamespace(Environment)` 统一命名空间解析
  （显式配置 → `seedj:<spring.application.name>` → `default`），lock/id/eventbus/workflow
  适配器共用同一入口。
- `seedj-pki-contract`：PKI 契约 v2（包 `cn.byteforce.seedj.provider.pki`）。覆盖
  `issue` / `signCsr`、Transit `sign` / `verify`、`revoke` / CRL / `tidy`、按 serial 读证书、
  `issuer` 查询、alias → 身份映射与 `sslConfig`；`IssuedCertificate` 用
  `KeyMaterial.CoordManaged|External` 表达私钥归属，不再强制携带 `PrivateKey`。
- `core` 新增 `cn.byteforce.seedj.observability`：`HealthStatus` / `ObservableProvider`，作为
  所有 provider 的统一健康面。
- `seedj-infra-coord` 升级 coord-java-sdk 0.4.4 并全量适配 PKI：`IssueCert` / `SignCsr` /
  `RevokeCert` / `GetCertStatus` / `GetCrl` / `RotateCrl` / `Tidy` / `GetCertBySerial` /
  `Transit.Sign|Verify`；alias 账本收缩为 `alias → CN + serial`，吊销状态以 coord 为权威。
- `seedj-infra-local` 的 BC PKI 全量实现：CSR 签发、CRL（含吊销原因）、tidy、按 serial 读
  证书、issuer 查询、`sslConfig`；`sign` / `verify` 委托本地 Transit（不支持 prehashed/PSS）。
- `seedj-it`：`ClusterPki` / `OidcIT` 适配新契约；`CoordPkiProviderIT` 跑 coord 0.4.4 真集群
  契约套件；coord 测试镜像固定 `v0.4.4`。
- `seedj-infra-coord`（P0）：coord 0.4.3 的 ID、编号、全局序列与 EventBus 发布适配器；
  provider 显式选择，失败消息点名 agent 地址；测试含真集群夹具（Testcontainers）。
- `seedj-infra-coord` 分布式锁：用 coord KV + OCC 事务组合出毫秒级租约（`TxnClient` 读集
  校验 + 记录内 `expiresAt`），`seedj.lock.provider=coord` 显式选择；过期持有者不会误删
  新持有者的记录。
- `seedj-infra-coord` 消费端：`seedj.eventbus.transport=coord` 同时提供 `EventConsumerStore`
  （MQ v2 join/poll/ack + KV 投递账本），支持毫秒级租约、延迟重试、attempts 与按组合查询死信；
  到期重投按位点回读，retention 缺失转死信。
- `seedj-im-starter`（阶段 1）：帧协议、Netty TCP/WebSocket 传输、IAM JWT 握手、心跳与未实现命令 `501`；`seedj-server/im` 改为单类宿主。
- IM 阶段 2 骨架：`core` 新增 `cn.byteforce.seedj.im` 契约（会话/成员任期/消息/游标/序号）与四个
  存储端口；`seedj-infra-local` 与 `seedj-jdbc-postgresql` 各一套实现（`im_*` 五张表 + `im_seq`，
  迁移 scope `im`，能力序号 220）；`seedj.im.store=memory|jdbc` 单开关，`jdbc` 缺数据源时启动失败。
- IM 命令分发可扩展：`ImCommandHandler` + `ImCommandRegistry` 取代写死的 switch，宿主注册 bean
  即可新增命令字，未注册仍回 `501`；处理器异常按契约错误码映射（403/404/409/500）。
- IM 登录携带 `device_id`；`seedj.im.max-sessions-per-user` 真正生效，新增
  `seedj.im.session-overflow=reject|evict-oldest`。
- IM 单聊闭环：`CHAT_SINGLE_REQ`（可按 `peer_id` 自动建会话）→ `CHAT_DELIVER` → `ACK_REQ` /
  `READ_RECEIPT` → `OFFLINE_PULL_REQ` / `SESSION_SYNC_REQ`；`client_msg_id` 幂等，正文不进
  outbox 事件。
- IM 命令在 `ImSessionExecutor` 里执行（Netty IO 线程不做 JDBC），队列满回 `429`；宿主可覆盖
  `ImConversationPolicy` / `ImAuthorizationGate`，或用 `ImConversationService` /
  `ImMessagingService` 从自己的端点复用同一路径。
- IM 群聊：`CHAT_GROUP_REQ` / `CHAT_GROUP_RESP`，群内广播按当前成员，单聊/群聊命令字严格区分；
  `send` 遇到序号冲突重试一次。
- IM HTTP 管理端点（`seedj.im.http.enabled`，默认开）：建单聊/群聊、列会话、加人、退群，授权沿用
  会话服务（租户 + 成员 + 策略）。
- `ImMessageStore` 追加 `edit` / `recall` / `deleteBefore`（SPI，1.0 前仍可调整）；memory 与 jdbc
  两套实现同步落地，jdbc 追加 `idx_im_message_created` 迁移（V4）。
- IM 增强（P4）：撤回 `RECALL_REQ` / 编辑 `EDIT_REQ`（本人或群主/管理员撤回，已撤回不可编辑，
  投递/补拉视图清空正文并带 `edited_at` / `recalled_at`）、会话列表 `0x14`、成员列表 `0x16`
  （支持 `at_seq` 时点成员与 `ImDisplayNameResolver`）、输入中 `0x18`（2 秒节流）、在线状态
  `0x1A`（节点内视图，变化通知只发给单聊对端）。
- IM 附件引用：`IMAGE` / `FILE` 的 body 必须带 `key`，`size` 不超过
  `seedj.im.message.max-attachment-bytes`；二进制上传仍归宿主。
- IM 保留期清理：`seedj.im.message.retention` 非空时按 `retention-sweep` / `retention-batch`
  分批删除到期消息，调度失败只记日志、下轮重试。
- IM 跨节点投递：`seedj.im.push.provider=coord` 经 outbox + EventBus 广播投递意向（只带
  `message_id`，不带正文），其它节点按 id 回读消息后投给本节点连接；订阅组按
  `seedj.im.push.node-id` 区分（空则进程随机，生产建议显式配置）。
- `seedj-it` 的 coord 端到端：`CoordIT` 在真 coord v2 集群（server + agent 容器）上验证 ID、编号、
  全局序列、分布式锁、EventBus 发布/消费与「重试 → 死信」，并核对 coord 侧 topic 与消息。
- `seedj-im-starter`（D6/D7）：`CARD` 消息一等类型（`schema`/`fallback_text`/`actions` 校验），系统发送入口
  `ImSystemNotifier`；新增 `CARD_ACTION_REQ/RESP/NOTIFY` 命令字与 `ImCardActionStore`（`message_id + action_id`
  幂等，pending/done + abandon 重试）；内存与 PostgreSQL 两套实现（IM 迁移 V5）。
- `seedj-ai-starter`（IM 审批卡片适配器）：可选 `seedj-im-starter` 依赖；`ApprovalCardNotifier` 给每个审批人发
  审批卡片，`ApprovalCardActionHandler` 调 `ApprovalService.act`；没有 IM 时回退日志通知。
- `seedj-ai-starter`（外部副作用两段协议）：`ExternalEffectService` 先固化 `prepared` 再调用外部系统，
  `decision_id` 作为外部幂等键，`confirmed`/`failed` 只追加；已确认重试不重调，U7 审计条目带
  `decision_id`，审计失败不回滚效果。
- `seedj-ai-starter`（T3-local 准入）：守卫把已校验的决策写成 `agent_decision_admission`，命令事务设置
  `agent.decision_id` 等 GUC；PostgreSQL RLS 函数 `agent_admission_ok` 对不可逆表兜底，直连无 admission
  的写入被拒。审计 payload 追加 `decision_id` / `ledger_intent_id`，对账优先按 decision 直取；账本新增
  `pendingOutcomes` / `pendingAudit` / `pendingApprovals` 与 `audit(partition)`（发现幽灵记录），审批过期
  扫描接上真实数据。
- `seedj-ai-starter`（T2-local 审批）：不可逆动作走 `pending_approval` → `GET/POST /v1/decisions/{id}/approval`
  → `POST /v1/decisions/{id}/grant`；审批引擎只依赖 `WorkflowProvider`（本地用 `seedj-workflow-local`），
  审批快照绑定 `args_hash`，驳回/超时落 `rejected` outcome，未开审批时不可逆动作直接 deny。
  `BoundedLoop` 新增 `PENDING_APPROVAL` 结局与 `DecisionClient.grant`。
- `seedj-ai-starter`（LLM）：`seedj.ai.llm.*` 配置 + OpenAI 兼容的 native tool-calling 客户端
  （`OpenAiCompatibleLlmGateway`）。`seedj.ai.tools[]` 按任务契约渲染成 `execute_command` 的工具枚举，
  模型输出一次 tool_call 即映射成 `Proposal`；坏输出（无/多 tool_call、未知工具或命令、缺 rationale、
  非法 targets）一律抛 `LlmException`，不降级成 `FINISH`；只对传输错误/429/5xx 重试；`api-key` 不进
  日志与 `toString()`。默认关闭，宿主 `LlmGateway` bean 仍优先。
- `seedj-ai-starter`（T1-local）：策略版本钉死。决策面在落 intent 前求值 `PolicyEvaluator`，把
  `PolicyRef`（engine/bundle/bundle_version/epoch/verdict）写进 intent 与 token；守卫对不可逆动作比对
  `PolicyHeadSource` 的 head，新增 `policy_unavailable` 与既有的 `policy_stale`。默认关闭，关闭时行为与
  T0 一致；coord 的实际策略求值（U8）仍未接，适配器只需实现两个端口。
- `seedj-ai-starter`（T0）：决策账本（内存 / PostgreSQL 两套实现 + 哈希链与只追加触发器）、
  DecisionToken（Ed25519 签发/校验 + JWKS）、执行侧 guard（`x-agent-decision`，`POST /commands/*`）、
  决策面（`POST /v1/decisions`、记录读、outcome 上报、对账）与有界 agent 循环；
  `seedj-server/ai` 是只开决策面的可运行宿主，`seedj-it` 的 pilot 宿主进程内跑完
  「脚本 LLM → 决策面 → guard → 命令」四件套。**T0 不含**策略求值、审批、不可逆动作的行级门禁、
  外部副作用两段协议与 IM 回传，边界逐条见 [`docs/guide/ai-starter.md`](docs/guide/ai-starter.md)。
- 开发者文档站（`docs/`，VitePress）与 GitHub Pages 发布。
- 贡献指南、安全政策、行为准则、支持说明与 issue/PR 模板。

### Changed

- IM 命令真正由 `ImSessionExecutor` 执行：Spring 装配下命令离开 Netty IO 线程（队列满回 `429`），
  此前文档声明的线程模型与实现不一致。
- IM 配置 `seedj.im.message.*` 的嵌套绑定修复：`ImProperties.Message` 只保留 record 规范构造器，
  避免 Spring 构造器绑定静默放弃整个 message 配置块。
- `README` 改为公开仓门面：可复制的快速开始、业务改动示例与模块表。
- 源码树中的文档引用全部指向仓内 `docs/`；架构决策不再出现在公开树。
- `seedj.iam.jwk.provider=pki` 只接受可导出的 RSA 私钥（`KeyMaterial.External`）。coord
  托管私钥不能当 IAM RS256 JWK，启动期给出明确错误；默认 JDBC/memory JWK 不受影响。
- `seedj.pki.provider=coord` 的 PKI 现在把 role 当 CN 命名空间前缀，`issuerRef` 只接受
  `default`；已知偏差登记在 `docs/guide/known-limitations.md`。

### Fixed

- 集群 IT 的服务身份：`spring.application.name` 改由命令行参数注入，不再被各服务的
  `application.yml` 覆盖；coord 消息的 producer 与默认命名空间因此按服务区分，而不是全部
  串到同一个名字。
- `search` 的当前视图快路径补上 `valid_to IS NULL`，与 `find` 共用同一口径：关闭业务时间轴（软删/归档）的对象不再在列表里复活，`find` 与 `search` 结论一致；`uq_*_current` 仍只约束未封口行，重新打开通过先封口再追加实现。
- IAM `store=jdbc` 的 refresh_token 交换不再 500：claims/attributes 里的集合改用 `ArrayList`，不再把 JDK 内部 `ImmutableCollections` 类型 id 写进 `oauth2_authorization`；授权行编解码器同时兼容升级前已写入的旧类型 id，已签发的 refresh token 无需重新登录。
- 快速开始示例的 `org_unit_id` 改为真实 UUID，`page` 从 1 开始。

### Removed

- `core` 的 `cn.byteforce.seedj.pki` 包（`PkiProvider` / `PkiErrorCode` / `PkiException`）。
  契约与异常迁入 `seedj-pki-contract`，包名改为 `cn.byteforce.seedj.provider.pki`；不保留
  双契约。

## [1.0.0] - 未发布

首个正式版本发布时填写。
