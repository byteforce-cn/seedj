# ADR-0001：单一授权路径

- 状态：已接受
- 日期：2026-11-07
- 决策者：Seedj 维护者

## 背景

命令路径、读路径与 IAM `/check` 曾各自判断权限：

- `CommandPolicy.permissions()` 只读 JWT 的 `permissions` claim；
- `ReadGateway.requireReadable()` 同样只看 `caller.hasPermission(...)`；
- `IamAuthorizationController` 的 SoD/ABAC/`as_of` 逻辑只挂在 HTTP `/check` 上；
- `iam.policy` 的 PDP 是第三条入口。

结果：同一个用户在三条路径上可能得到不同结论；SoD 与属性条件在真实命令上不生效。

## 决策

1. **一个外部决策点**：`cn.byteforce.seedj.security.AuthorizationDecisionPoint` 是命令、
   读与 `/check` 的唯一授权入口。调用方只构造 `AuthorizationRequest`，不得自行解释
   JWT claim。
2. **内部实现**：IAM starter 的 `CompositeAuthorizationDecisionPoint` 组合
   - `IamContractAuthorizationService`：RBAC、SoD、属性条件（04 授权契约）；
   - `IamPolicyDecisionPoint`：ABAC/字段级策略。
   两个引擎都是内部实现，不再作为 `CommandPolicy`/`ReadGateway` 的直接依赖。
3. **`/check` 的边界**：只接受 permission、`as_of` 与可选 resource 上下文
   （`resource_type`/`resource_id`/`resource_attributes`）。调用方不得传入条件
   （ATR-009）；条件只来自声明的策略。
4. **失败模式**：生产装配没有 `AuthorizationDecisionPoint` 时启动失败；只有
   `local`/`test` profile 显式提供 permit-all 决策点。
5. **字段级读取**：`FieldPolicyResolver` 仍负责逐字段判定，但判定调用组合决策点；
   `read`/`read.masked` 的语义不变，writeOnly 永远不参与。

## 后果

- 新增授权能力只改决策点实现（或组合），命令/读路径无需改动。
- `IamPolicyDecisionPoint` 不再是 `@Primary` 的并行入口；需要它的内部组件显式限定
  bean 名 `iamPolicyDecisionPoint`。
- 旧构造函数保留给隔离测试，但生产装配必须传真实决策点；没有静默回退。
- SoD/ABAC 的负例由 `CommandAuthorizationIT`、`FieldMaskingIT` 与
  `AuthorizationWiringTest` 固定。
