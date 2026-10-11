# starter-eis-audit — EIS 审计哈希链

`05-audit-contract/hash-chain.spec.md` 的权威 Java 实现：九元规范化哈希、
**锚行串行分配**（连续无缺口）、验证器（对齐 `verify.tool` 四类错误）、锚定 SPI 与管理端点。

## 依赖与装配

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-eis-audit</artifactId>
</dependency>
```

前置：`JdbcTemplate` 与 `EisClock`（`starter-eis-core` 提供）；
表结构：执行 `META-INF/eis/ddl/eis-audit.ddl`（`audit_entry` 契约表 + 锚表 `eis_audit_head`）。

装配后即成为 `EisCommandRuntime` 的 `EisAuditAppender`：仅 **accepted** 命令在命令事务内入链；
动作 = 命令类型；资源 = 首事件主体域；载荷 = `{command_id, event_ids}`。

## 语义要点

- **规范化**（规范 §2）：`id / sequence / timestamp(UTC 微秒, …Z) / actor / action / resource.type /
  resource.id(NULL→空串) / payload(规范 JSON) / prev_hash`，LF 连接、UTF-8 不转义非 ASCII；
- **冻结向量**：`447c6e299d9adaec35d89e273b15d28752eeaac4674000b5df88d2e53a087410`
  （测试逐位校验）；
- **串行写**：序列由锚表单行 `UPDATE … RETURNING` 分配——**不复用 `BIGSERIAL` 缺省分配**
  （并发/回滚会留缺口）；锚行 `last_hash` 恒等于最新条目哈希，空链 = 创世前序哈希（64×`0`）；
- **时间**：全链路 UTC 微秒（入哈希前纳秒截断，保证回读重算一致）；
- **append-only**：`audit_entry` UPDATE/DELETE 由 DDL 规则短路。

## 验证与锚定

- `EisAuditChain#verify()` — 全链重算，错误类型 `sequence_gap` / `prev_hash_mismatch` /
  `invalid_hash` / `empty_chain`（语义与 `verify.tool` 一致）；
- `EisAuditAnchor` — 锚定 SPI（v1 提供文件/日志实现）；锚定动作由运维/选主定时任务触发，
  不在每次追加时执行；
- 管理端点（受应用安全链保护）：`GET /admin/audit/verify`（与 `verify.tool --json` 同形报告）、
  `GET /admin/audit/head`、`POST /admin/audit/anchor`。

## 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.eis.audit.enabled` | `true` | 总开关 |
| `byteforce.eis.audit.anchor-file` | — | 锚定文件路径；为空 = 日志锚定（链头写应用日志） |

## 边界

- 不实现签名（`signature` 字段不参与 v1 链验证，规范 §6）；
- 整链替换检测依赖外部锚定（部署策略，规范 §5）。
