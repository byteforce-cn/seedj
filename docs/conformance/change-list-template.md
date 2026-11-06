# 契约变更清单模板

- 变更 ID：
- 日期：
- 负责人：
- 关联 ADR：
- 规范版本：

## 1. 变更内容

| 工件 | 变更 | 旧值 | 新值 |
|---|---|---|---|
| Schema / OpenAPI / AsyncAPI |  |  |  |

## 2. 兼容判定

- 兼容规则条目（`config/conformance/compatibility.rules.yaml`）：
- 级别：PATCH / MINOR / MAJOR
- `requires` 完成情况：
  - [ ] migration
  - [ ] rollback
  - [ ] dual_write
  - [ ] backfill
  - [ ] deprecation_window
  - [ ] migration_guide
  - [ ] consumer_migration

## 3. 弃用窗口（MAJOR 必填）

- 公告版本 / 日期：
- 计划删除版本：
- 已持续时间：
- 旧能力调用量证据：

## 4. 迁移与回滚

- 迁移脚本：
- 回滚脚本：
- 演练记录（命令、结果、耗时）：

## 5. 验证

- [ ] `mvn -o -B verify`
- [ ] `mvn -o -B -Pconformance -DskipTests verify`
- [ ] 迁移/回滚演练
- [ ] 变更清单归档到 `docs/conformance/runs/`
