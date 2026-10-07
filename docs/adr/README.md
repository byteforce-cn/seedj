# 架构决策记录（ADR）

本目录收录 seedj 仓库的架构决策记录（Architecture Decision Record）。
**所有 ADR 在 `docs/adr` 下统一编号、统一追踪**：一决策一文档；结论只增不改——
结论发生变化时，新建 ADR 并将旧文标记为 `Superseded`，不直接改写旧文结论。

## 索引

| 编号 | 标题 | 状态 | 日期 |
| --- | --- | --- | --- |
| [ADR-0001](./0001-coord-starter-spring-cloud-replacement.md) | coord-starter 承接 Spring Cloud 替代的架构约束 | Accepted（已采纳） | 2026-10-07 |
| [ADR-0002](./0002-error-response-contract.md) | 错误响应契约（统一错误体 / HTTP 状态 / 异常体系） | Accepted（已采纳） | 2026-10-07 |
| [ADR-0003](./0003-json-serialization-contract.md) | JSON 序列化契约（CodeEnum code 映射 / Long 字符串化） | Accepted（已采纳） | 2026-10-07 |

## 约定

- **命名**：`NNNN-kebab-case-title.md`；四位序号自 `0001` 起递增，不跳号、不复用；
  中文标题写在文档首行（`# ADR-NNNN：标题`）。
- **状态机**：
  - `Proposed`（起草，待评审）；
  - `Accepted`（已采纳）；
  - `Deprecated`（已废弃）；
  - `Superseded by ADR-XXXX`（被后续 ADR 取代）。
- **变更规则**：
  - 决策结论变化：新建 ADR，旧文状态改为 `Superseded by ADR-XXXX`，正文保留不删；
  - 勘误（错别字、链接、格式）：可原位修订，并在文末「修订记录」注明。
- **新增流程**：复制 [`template.md`](./template.md) → 取当前最大编号 +1 → 起草 →
  评审通过后更新本文索引与文档状态。
- **编号空间**：seedj 的 ADR 编号独立于 coord 上游的 ADR 体系；引用时写明
  “seedj ADR-XXXX”或“coord ADR-XXXX”以免混淆（仓库中已有对 coord ADR-0001
  「服务默认关」的引用）。
