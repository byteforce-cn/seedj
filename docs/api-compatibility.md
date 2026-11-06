# API 兼容性规则

本页定义 `1.x` 线上公共 API 的兼容性承诺与门禁口径。**判定权威是根 POM 的 `compat` profile 与
[compatibility.rules.yaml](../config/conformance/compatibility.rules.yaml)**；本页只解释为什么这样判定、
以及出现变更时该怎么走流程。规则细节（字段级 diff 语义）以规范仓库 06-evolution-contract 为准。

## 基线（baseline）

- 门禁为 opt-in：`mvn -Pcompat -DskipTests verify`，基线版本由 `-Dseedj.compat.baseline=<x.y.z>` 指定
  （默认 `1.0.0`，即首个正式版本）。
- 基线制品必须能从本地仓库或私服解析；解析不到时构建**失败**，不静默跳过。
  实现上由 `config/ci/check-compat.sh` 先用 `dependency:get` 逐个确认基线制品，再调用
  `-Pcompat`（japicmp 0.26.3 自身在基线缺失时只告警，不能依赖它）。
  首次发布（还没有任何基线）必须显式设置 `SEEDJ_FIRST_RELEASE=true`。
- 二进制不兼容默认**阻断**；源码不兼容当前只报告（`breakBuildOnSourceIncompatibleModifications=false`），
  因为有意的变更需要 ADR + 变更清单，而不是让门禁替人做决定。
- `seedj-it` 是测试模块，在自身 POM 声明 `seedj.compat.skip=true`。
- 1.0.0 发布前的包拓扑调整（T3.1–T3.4）按
  [ADR-0009](adr/ADR-0009-package-topology-and-api-convergence.md) 与
  [变更清单](conformance/change-list-2026-11-06-architecture-remediation.md) 登记；
  发布后才进入 `-Pcompat` 的二进制比较。

## 允许的变更（不需要 ADR）

| 变更 | 判定 |
|---|---|
| 新增类、方法、字段、枚举常量 | 兼容 |
| 新增可选字段（JSON 请求/响应） | 兼容：既有调用方不传也能工作 |
| 放宽参数约束（非空 → 可空、枚举加值） | 兼容，但需评估消费方 `switch` 的兜底 |
| 修正 Javadoc、重命名参数名 | 兼容 |

## 禁止的变更（需要 ADR + 变更清单）

- 删除/重命名公共类型、方法、常量；修改方法签名或返回类型。
- 收紧必填字段、删除响应字段、改变既有字段语义或错误码含义。
- 修改返回类型的泛型/集合形状，例如 `List<T>` → `Collection<T>`：源码可编译，二进制不兼容。
- 改变有线格式：`/commands/{type}`、`/objects/{type}/{id}`、错误信封 `{code,message,details}`。
  新字段只能是可选字段。

流程：

1. 在 [ADR 目录](adr/) 新增 ADR（`ADR-000N-*.md`，按 [模板](adr/ADR-template.md)），说明动机与迁移路径。
2. 在 [变更清单模板](conformance/change-list-template.md) 的副本里登记变更，遵循
   [弃用策略](conformance/deprecation-policy.md) 给出的窗口期。
3. 更新兼容性 schema：先改 `docs/conformance/schema/`，`baseline/` 冻结不动；
   发布冻结后才把 `schema/` 提升为新的 `baseline/`。
4. 公共 API 清单（`config/public-api.txt`，T0.3 引入）随变更同步更新——它是 allowlist，不是文档。

## 公共 API 清单的维护

`config/public-api.txt`（T0.3 引入）由公共 API 门禁测试生成并比对：新增类型必须被门禁显式接受，
删除类型必须先走上面的 ADR 流程。清单变化与代码变化在同一个提交里，评审时一并看。

## 相关入口

- 兼容性门禁实现：[config/ci/check-compat.sh](../config/ci/check-compat.sh)、
  [config/conformance/README.md](../config/conformance/README.md)
- 契约一致性设计与分期验收：[设计](superpowers/specs/2026-11-05-contract-conformance-design.md)、
  [验收记录](conformance/runs/)
- 发布流程：[releasing.md](releasing.md)
