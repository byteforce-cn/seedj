# ADR-0002：Workflow 与 approval 的分层边界

- 状态：已接受
- 日期：2026-11-07
- 决策者：Seedj 维护者

## 背景

规范仓库的 approval 扩展定义审批单、会签、加签、转办、撤回等语义；seedj 的
`WorkflowProvider` SPI 定义通用的流程引擎边界（定义、实例、任务、历史、定时器）。
两者都叫“审批”，但职责不同。

## 决策

采用 **B：两个层次，不互相替代**。

1. `WorkflowProvider` 是通用流程执行层：它只负责步骤推进、人工任务、超时与历史，
   不知道“采购申请”“报销单”等业务单据。
2. approval 扩展是业务审批层：审批单到流程实例的映射、业务字段到任务表单的映射、
   审批通过后的业务副作用，由应用（或未来的 approval 扩展适配器）实现。
3. Java 侧本期只交付：
   - 工作流表结构与迁移能力（`WorkflowSchemaCapability`）；
   - 到期定时器扫描（`WorkflowTimerPoller`）；
   - 本文档作为边界说明。
4. 不在 Java core 里硬编码 approval 的字段/状态；需要桥接时新增独立适配器模块，
   不修改 `WorkflowProvider` 的冻结契约。

## 后果

- 业务团队可以先把 approval 当状态机在应用层实现，再迁移到 WorkflowProvider；
- WorkflowProvider 的 JDBC 完整状态机实现（定义/实例/任务/历史的读写）作为
  M5 的后续工作，不影响 1.0 的 M0–M3 门槛；
- 定时器扫描已经可以独立运行，不依赖完整 Provider。
