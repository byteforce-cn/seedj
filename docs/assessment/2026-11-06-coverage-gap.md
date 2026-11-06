# 覆盖率差距记录（2026-11-06）

本次架构整改把覆盖率门禁从“假绿”改成真实 ratchet，但**没有**在 T4.3 达到目标终值。
本页保留实测证据与差距，避免用口径调整冒充达标。

## 目标与实测

T4.3 目标（`docs/superpowers/plans/2026-11-06-architecture-remediation.md`）：

| 模块 | 目标 line | 目标 branch | 实测 line | 实测 branch | 差距 |
|---|---|---|---|---|---|
| core | 0.80 | 0.60 | 0.528 | 0.428 | −27.2pp / −17.2pp |
| seedj-biz-starter | 0.80 | 0.60 | 0.601 | 0.513 | −19.9pp / −8.7pp |
| seedj-infra-local | 0.78 | 0.60 | 0.722 | 0.530 | −5.8pp / −7.0pp |
| seedj-jdbc-postgresql | 0.78 | 0.60 | 0.730 | 0.554 | −5.0pp / −4.6pp |
| seedj-iam-starter | 0.75 | 0.55 | 0.536 | 0.343 | −21.4pp / −20.7pp |

数据来源：`target/site/jacoco/jacoco.xml`（`bash config/ci/coverage-report.sh`）。
门禁阈值上调到**不高于实测的最近安全值**（只升不降），`config/coverage-baseline.json` 同步：
core 0.52/0.42、biz 0.60/0.51、infra 0.72/0.52、jdbc 0.73/0.55、iam 0.53/0.34。
infra 的 branch 实测 0.5297，若按四舍五入写成 0.53 会让门禁在 0.0003 的差距上误报，
因此保持 0.52。

## 为什么没有强行达标

- core 与 iam 的缺口集中在 IAM 安全分支、SCIM 解析器、时间轴边界与异常映射；这些场景
  需要为每个分支写独立用例，不能靠参数化刷行数。
- 规范明确要求“未达到则记录差距并延长 milestone，不得伪造”；本轮选择先把
  ArchUnit / SplitPackage / PackageCycle / PublicApi / FileSize / PackageInfo / DocLink
  门禁转绿，并保持覆盖率只升不降。

## 后续动作

1. 按 `target/coverage-hotspots.md` 的包级排序补测试：core `cqrs`、core `domain`、
   core `audit`、iam `scim`/`authz`。
2. 每完成一个包，将 `config/coverage-baseline.json` 与对应模块 POM 阈值上调到
   “实测 − 1%”，不允许一次性跳到目标。
3. 达到目标后，把本页标记为 closed，并在 `docs/testing.md` 更新终值。
