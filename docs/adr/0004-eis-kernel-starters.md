# ADR-0004：EIS 内核族（`starter-eis-*`）设计

| 项 | 值 |
| --- | --- |
| 状态 | Accepted（已采纳；2026-10-10 IAM 首落地轮，决策与实现同批） |
| 日期 | 2026-10-10 |
| 决策人 | （待填写） |
| 关联 | EIS 仓 `docs/LANDING-IAM.md`（落地方案 v0.3.1）、`02/03/04/05` 契约与 `tools/lib/kernel.py`（行为基准）；[`../../starter/eis-core`](../../starter/eis-core/) / [`eis-audit`](../../starter/eis-audit/) / [`eis-temporal`](../../starter/eis-temporal/) / [`eis-authz`](../../starter/eis-authz/) / [`eis-it`](../../starter/eis-it/)；[ADR-0001](./0001-coord-starter-spring-cloud-replacement.md)（coord 家族与中间件白名单） |

## 背景与问题

1. **目标**：把 EIS 规范（`eis` 仓：数据/操作/时间/授权/审计/演化六契约）沉淀为可复用的 Java 构件，
   让 IAM 成为第一个域服务；通用能力回归 seedj、领域工件留在 eis/服务仓。
2. **行为基准**：`eis/tools/lib/kernel.py` 是"可执行规格"（命令受理、封口、as_of、审计链、幂等指纹）；
   Java 实现必须逐语义对齐，并以冻结向量与真库集成测试固化。
3. **硬约束**：中间件白名单 = **PostgreSQL 16 + coord**；不引入 Redis/MQ/网关缓存等；
   能力能用 coord 即用 coord，但"正确性不外包"（数据/判定/审计权威在 PG + 契约内）。
4. **工程约束**：seedj 现状基线（Boot 4.1 / Security 7 / Jackson 3 / JDBC / Testcontainers）、
   覆盖率门禁（starter ≥ 70%）、禁 record/Lombok、中文 Javadoc。

## 决策

### D1 模块边界（5 分）

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `starter-eis-core` | 命令运行时（幂等/授权钩子/规则链/事件/审计/封口同事务）、双时间轴对象存储、outbox、命令端点 | spring-jdbc、starter-json、seedj `core` |
| `starter-eis-audit` | 审计哈希链（规范化/锚行串行写/验证/锚定 SPI/管理端点） | eis-core |
| `starter-eis-temporal` | 时点查询端点（`/objects`、`/time-travel`），半开区间与读投影脱敏 | eis-core |
| `starter-eis-authz` | 授权解析引擎（`/resolve` `/check`、代理展开、SoD）、远程客户端与 TTL 缓存 | eis-core |
| `starter-eis-it` | Testcontainers PG16 集成套件（不产出可消费构件） | 上述全部 |

### D2 语义权威与对齐

- 契约语义以 `eis` 仓为唯一权威；Java 与 `kernel.py` 逐项对齐：命令单一时刻、封口相接、
  半开区间 `[from, to)`、受理才入审计链、幂等指纹 = SHA-256（`pythonDumps` 规范化
  `{payload, targets, type}`）；
- 审计规范化按 `hash-chain.spec.md §2` 逐字实现，**冻结向量 `447c6e29…7410` 逐位一致**为合并门禁；
- 时间全链路 UTC **微秒**：入哈希与落库前截断为微秒（T-R007；纳秒差异会造成回读重算失配）。

### D3 存储与一致性

- 业务正确性全部由**单 PG 事务**承担（command/event/object/audit/outbox 同生共死）；
- 契约表原样物化（`eis-core.ddl` / `eis-audit.ddl` 资源分发）；契约外缺口以**部署侧辅助表**补足
  （`eis_idempotency` / `eis_command_event` / `eis_command_rejection` / `eis_outbox` / `eis_audit_head`），**不改契约文件**；
- 审计序列由锚表单行 `UPDATE … RETURNING` 串行分配（**不复用 BIGSERIAL 缺省分配**：并发/回滚留缺口）；
- 对象更新 = 行锁（有界重试）+ 封旧行 + 追新行；跨节点原子性不依赖锁，靠 PG 约束与事务。

### D4 中间件白名单的落地口径

- 只依赖 PG16 + coord；coord 能力按 EIS 落地方案分档接入：T1（注册/配置/锁/限流/观测）沿用；
  T2（Event 失效脉冲、LeaderElection 选主）为**可选、fail-open**、经应用侧胶水接线；
  policy/mq/workflow/txn/cache 等服务**不进 v1 请求链路**（依据见 `LANDING-IAM §3.4`）；
- 不引入任何白名单外依赖（含"只是一个库"的缓存/调度实现）。

### D5 敏感字段

- 存储层读取原值（凭证校验等内部流程需要）；**出参投影**（HTTP/事件/日志）按 `EisObjectType.sensitiveFields` 剔除；
- 命令处理器可覆写 `storedPayload` 对落库载荷脱敏（如凭证散列）。

### D6 测试与门禁

- **不引入 H2**：EIS 依赖排他约束/部分唯一索引/append-only 规则等 PG 特性——集成测试一律
  Testcontainers PG16（`starter-eis-it`，`-Pit` 档）；单元测试以 Mockito 覆盖逻辑与 SQL 组装；
- 新模块纳入既有覆盖率/零测试守卫/SpotBugs 门禁。

## 后果

- 后续域服务（accounting/inventory/…）复用本族模块，只需注册对象类型、处理器、规则与授权钩子；
- `starter-eis-it` 与 `starter-coord-it` 同为测试套件模块（不入 BOM、禁止 install/deploy）；
- 重量级集成验证依赖本机 Docker（与既有 coord 套件一致）；
- 审计是**必配能力**：容器缺少 `EisAuditAppender` 时运行时不可装配（fail-fast，审计非可选装饰）。
