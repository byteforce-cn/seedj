# seedj 文档索引

本页是仓库文档的唯一入口，用于回答三个问题：**规范从哪里来、当前实现是什么、验收证据在哪里**。
文档与代码同等受门禁约束：`DocLinkTest` 要求本页及 `docs/**` 下所有相对链接都能解析。

## 权威来源（按优先级）

1. **规范仓库**（EIS v1.0.0）：外部只读输入，本仓库不做副本判定。
2. **ADR**：[ADR-0001…0010](adr/) 记录已接受的架构决策；新决策按 [ADR 模板](adr/ADR-template.md) 追加，编号只增不改。
3. **设计/计划**：[specs](superpowers/specs/) 是每轮交付的设计，[plans](superpowers/plans/) 是执行分解。
4. **差距评估**：[assessment](assessment/) 记录评估当时的实证结论，是对外承诺的对照基线。

## 主题入口

| 主题 | 文档 |
|---|---|
| 架构决策与模块拓扑 | [ADR 索引与模板](adr/)、[ADR-0008 模块拓扑](adr/ADR-0008-module-topology.md)、[ADR-0009 包拓扑与 API 收敛](adr/ADR-0009-package-topology-and-api-convergence.md) |
| 契约一致性设计与分期验收 | [契约一致性设计](superpowers/specs/2026-11-05-contract-conformance-design.md)、[分期验收记录](conformance/runs/) |
| 兼容性规则与门禁工具 | [compatibility.rules.yaml](../config/conformance/compatibility.rules.yaml)、[兼容性门禁说明](../config/conformance/README.md) |
| 契约 schema 与基线 | [schema](conformance/schema/)、[baseline](conformance/baseline/)、[api](conformance/api/) |
| 弃用与变更流程 | [弃用策略](conformance/deprecation-policy.md)、[变更清单模板](conformance/change-list-template.md) |
| 业务接入 | [biz starter](biz/README.md)、[IAM starter](iam/README.md)、[基础设施 SPI 宪法](spi/README.md) |
| 发布与兼容性 | [发布流程](releasing.md)、[API 兼容性规则](api-compatibility.md) |
| 开发指南 | [quickstart](quickstart.md)、[决策指南](decision-guide.md)、[命令契约](command-contract.md)、[读模型](read-model.md)、[安全](security.md)、[测试](testing.md) |

## 维护约定

- 新增页面必须在本页挂上入口，否则视为不可发现文档。
- 引用其他页面一律写相对 Markdown 链接（不要只写反引号路径），这样 `DocLinkTest` 才能守住。
- 失效链接的处理只有两种：修好目标，或删除引用；不允许空文件占位。
