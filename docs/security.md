# 安全

业务代码只接触 `Caller` 与权限字符串；认证、CORS、派生密钥与 dev auth 的默认值都在
starter/宿主，不在控制器里各写一套。

## Caller 与权限

```java
Caller caller = callers.find().orElseThrow();   // 未认证时为空
if (!caller.hasPermission("hr.employee.write")) {
    throw new ContractException(ContractErrorCode.FORBIDDEN, "missing permission");
}
```

- `Caller` 由 `CallerProvider` 提供；测试可注入固定调用方。
- 权限在命令 descriptor 与端点两处声明，二者必须一致（`CommandCatalog` 启动时检查）。
- 无认证 → 401 `EIS-401-001`；有认证但缺权限 → 403 `EIS-403-001`。
- 业务拒绝不是 403；用 400 `EIS-400-002` 或领域错误码。

## 默认安全姿态

- 生产 profile 禁止 `test-only-key`、禁止默认 `CORS *`、禁止无权限执行命令。
- CORS 必须显式配置 `seedj.biz.cors.allowed-origins`；prod 为空时启动失败。
- `seedj.biz.dev-auth.enabled=true` 只允许 local/test；prod 启动失败。
- 派生字段密钥：生产必须注入 `SEEDJ_FIELD_HASH_KEYS`；缺失时拒绝启动，不回落到测试密钥。
- 审计与安全响应不返回堆栈、内部路径或敏感字段全文。

## dev auth（仅本地）

`local` profile 通过 dev-auth 注入固定调用方（默认 `local-admin`，权限
`hr.employee.write` / `hr.employee.read`）。它绕过认证但不绕过授权检查；生产 profile
的守卫会让应用直接启动失败。

## 相关文档

- [命令契约](command-contract.md)
- [ADR-0007 biz starter 与安全契约](adr/ADR-0007-biz-starter-and-security-contract.md)
- [错误矩阵](errors.md)
