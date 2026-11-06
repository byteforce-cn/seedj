# 契约变更清单：2026-11-06 架构整改

- 变更 ID：ARCH-REMEDIATION-2026-11-06
- 日期：2026-11-06
- 负责人：seedj 架构整改
- 关联 ADR：`docs/adr/ADR-0009-package-topology-and-api-convergence.md`
- 规范版本：1.0.0-SNAPSHOT（未发布）

## 1. 变更内容

| 工件 | 变更 | 旧值 | 新值 |
|---|---|---|---|
| Java API | 实现包迁移 | `cn.byteforce.seedj.id` / `pki` / `transit` / `*.memory` | `cn.byteforce.seedj.infra.local.<cap>` |
| Java API | JDBC 适配器迁移 | `cn.byteforce.seedj.<cap>.jdbc` / `cn.byteforce.seedj.jdbc` | `cn.byteforce.seedj.infra.jdbc.<cap>` / `cn.byteforce.seedj.infra.jdbc` |
| Java API | 装配包迁移 | `cn.byteforce.seedj.<cap>.wiring` / `cqrs.web` | `cn.byteforce.seedj.starter.<area>` |
| Java API | IAM 装配迁移 | `cn.byteforce.seedj.iam.**`（starter） | `cn.byteforce.seedj.starter.iam.**` |
| Java API | 删除异常类型 | `BaseException` / `BizException` / `RemoteException` | `ContractException` / `InfrastructureException` |
| Java API | 删除兼容构造器 | `CommandRuntime.Options` 8 参 | 9 参全参 / `forTests` |
| Schema / OpenAPI / AsyncAPI | 无变更 | — | — |

## 2. 兼容判定

- 兼容规则条目（`config/conformance/compatibility.rules.yaml`）：无（线格式未变）
- 级别：MAJOR（Java API）；线格式等价于 PATCH
- `requires` 完成情况：
  - [x] migration_guide（ADR-0009 的包映射表）
  - [x] consumer_migration（仓库内调用方已迁移，`mvn verify` 通过）
  - [ ] migration / rollback / dual_write / backfill（不涉及数据迁移）
  - [ ] deprecation_window（1.0.0 未发布，无外部消费方）

## 3. 弃用窗口（MAJOR 必填）

- 公告版本 / 日期：不适用（首个公开发布前）
- 计划删除版本：1.0.0
- 已持续时间：不适用
- 旧能力调用量证据：无外部消费方；仓库内调用量在本次提交中清零

## 4. 迁移与回滚

- 迁移脚本：不涉及
- 回滚脚本：不涉及
- 演练记录：`git revert` 本次整改提交即可恢复；`mvn verify` 与 `-Pconformance` 兼容门禁
  不依赖旧包名

## 5. 验证

- `config/ci/verify.sh` → `verify: OK`
- `SplitPackageTest` / `PackageCycleTest` / `PublicApiSurfaceTest` / `DocLinkTest` 全绿
- `config/public-api.txt` 与本清单同批提交
