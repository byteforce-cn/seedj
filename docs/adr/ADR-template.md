# ADR-XXXX: <标题>

- 状态：提议 / 已接受 / 已废弃 / 已取代
- 日期：YYYY-MM-DD
- 决策者：
- 关联：规范条目、兼容规则条目、变更清单

## 背景

描述问题、约束与不做的后果。

## 决策

明确选择，不写“可以考虑”。

## 兼容性分析（契约变更必填）

- 兼容规则：`config/conformance/compatibility.rules.yaml` → `<data|operation|event>` →
  `<规则条目>`
- 判定级别：PATCH / MINOR / MAJOR
- `requires` 逐项完成情况：
  - [ ] migration
  - [ ] rollback
  - [ ] dual_write
  - [ ] backfill
  - [ ] deprecation_window
  - [ ] migration_guide
  - [ ] consumer_migration
- 弃用窗口（MAJOR）：公告版本/日期、计划删除版本、≥2 MINOR 且 ≥90 天的证据
- 迁移/回滚：脚本路径与演练记录

## 后果

正面、负面、后续动作。负面后果必须写，不能只写收益。

## 备选方案

每个备选方案一句话说明为什么未采用。
