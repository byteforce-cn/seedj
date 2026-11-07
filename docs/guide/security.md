# 安全

业务代码只接触 `Caller`、`ScopeContext` 与权限字符串；认证、请求事实、数据范围与
字段隐私的默认值都在 core/starter，不在控制器里各写一套。

## Caller 与权限

```java
Caller caller = callers.find().orElseThrow();   // 未认证时为空
if (!caller.hasPermission("hr.employee.read")) {
    throw new ContractException(ContractErrorCode.FORBIDDEN, "missing permission");
}
```

- `Caller` 由 `CallerProvider` 提供；测试可注入固定调用方。
- 命令权限在 `ObjectModuleConfigurer` 的 command 声明上；对象读权限在 `.readPermission`。
- `ReadGateway` 在网关内程序化执行对象读权限；生成的查询端点不带 `@PreAuthorize`。
- 无认证 → 401 `EIS-401-001`；有认证但缺权限 / 写范围越界 → 403 `EIS-403-001`。
- 业务拒绝不是 403；用 400 `EIS-400-002` 或领域错误码。

## 请求事实与数据范围

```java
public interface ContextWriter {
    default int order() { return 0; }
    void write(ContextSink sink, Caller caller);
}

public interface DataScopeResolver {
    boolean supports(String objectType);
    ScopePredicate resolve(String objectType, Caller caller, ScopeContext context);
}
```

- `ScopeContextFilter` 在认证之后打开作用域，`finally` 关闭；异步边界用
  `RequestContextDecorator` 同时传播 `Caller` 与 `ScopeContext`。
- `ContextSink` 拒绝重复 key；同一事实写两次是错误，不是后者覆盖。
- 行范围只能收窄：多个 `DataScopeResolver` 的谓词 AND；没有 resolver → 403。
- 谓词字段必须是 schema 声明字段；范围可以使用不可过滤字段，但不能使用未知字段。
- `IN` 上限 1000；`Not` 是二值逻辑，SQL 编译为 `(p) IS NOT TRUE`。
- 单租户：不建 tenant 列、不做租户过滤；`Caller.tenantId` 固定 `default`。

## 字段隐私

- 字段声明 `privacy` 与 `masker`；默认 `PUBLIC/INTERNAL → FULL`，
  `SENSITIVE/RESTRICTED → HIDDEN`。
- IAM 字段策略按字段判定 `read` / `read.masked`，映射为 FULL/MASKED/HIDDEN；
  显式 DENY 优先。`FieldAccess.policyId` 进入读审计。
- `writeOnly` 永远不输出，优先于任何策略。

## 策略引擎

`seedj-iam-starter` 的 `PolicyScopeCompiler` 把 ALLOW/DENY 策略编译为谓词：

- `subject.*` / `environment.*` 折叠为常量；`resource.<field>` 保留。
- 字段对字段比较、未知字段、未声明 context key、不支持的算子都在启动期失败。
- ALLOW 之间 OR，DENY 取反后 AND；没有 ALLOW → `None`（拒绝全部）。
- 字段策略复用同一个 PDP，不改协议。

## 默认安全姿态

- 生产 profile 禁止 `test-only-key`、禁止默认 `CORS *`、禁止无权限执行命令。
- CORS 必须显式配置 `seedj.biz.cors.allowed-origins`；prod 为空时启动失败。
- `seedj.command.degraded-mode=true` 与 `seedj.biz.dev-auth.enabled=true` 只允许
  local/test；`DegradedModeGuard` 在其它 profile 直接拒绝启动。
- 派生字段密钥：生产必须注入 `SEEDJ_FIELD_HASH_KEYS`；缺失时拒绝启动。
- 审计与安全响应不返回堆栈、内部路径或敏感字段全文。

## dev auth（仅本地）

`local` profile 通过 dev-auth 注入固定调用方（默认 `local-admin`，权限
`hr.employee.write` / `hr.employee.read`，组织范围 `D01`）。它绕过认证但不绕过授权
与数据范围；生产 profile 的守卫会让应用直接启动失败。

## 相关文档

- [命令契约](command-contract.md)
- [读模型](read-model.md)
- [错误矩阵](errors.md)
