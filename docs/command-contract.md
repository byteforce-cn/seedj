# 命令契约

所有写操作走 `POST /commands/{type}`。命令类型、权限、对象类型、校验器与脱敏器由
`CommandDescriptor` 一次声明，HTTP、权限、审计与事件共用同一份声明。

## 一个命令的组成

```java
CommandDescriptor<HrEmployeeCreate> descriptor = CommandDescriptor
        .<HrEmployeeCreate>builder("hr.employee.create")
        .permission("hr.employee.write")
        .objectType("hr.employee")
        .eventType("hr.employee.created")
        .build();
```

| 字段 | 作用 |
|---|---|
| `type` | 线格式命令名；唯一，启动时由 `CommandCatalog` 检查重复 |
| `permission` | 命令级权限；缺失返回 403，不依赖控制器注解 |
| `objectType` | 对象类型；用于时间轴写入与 `/objects/{type}` 读取 |
| `eventTypes` | 产生的领域事件类型；由 schema 的 `eventFields()` 派生载荷 |
| `validator` | 在写事务前执行；失败映射 400 `EIS-400-001` |
| `redactor` | 命令载荷落审计/事件前脱敏；`writeOnly` 字段必须在此清除 |

## 运行时顺序

1. 解析请求体与 `Caller`；无认证返回 401 `EIS-401-001`。
2. `CommandCatalog` 查 descriptor；未知类型 400，未登记权限 403。
3. 幂等键检查：同一 `Idempotency-Key` 直接返回首次结果。
4. 取锁（聚合 ID）→ 事务开始。
5. 校验器 → 读取当前对象 → 业务规则。
6. 写对象行 + 领域事件（outbox）→ 审计 → 命令终态。
7. 提交事务；返回 `object_id`、`event_ids`、`version`。

锁、事务或 outbox 缺失时启动失败；只有显式
`seedj.command.degraded-mode=true`（local/test）才允许降级，并打印 WARN。

## 错误矩阵

| 场景 | HTTP | code |
|---|---|---|
| 载荷结构/类型错误 | 400 | `EIS-400-001` |
| 业务校验拒绝 | 400 | `EIS-400-002` |
| 时间契约冲突 | 400 | `EIS-400-003` |
| 未认证 / 令牌无效 | 401 | `EIS-401-001` |
| 缺少权限 | 403 | `EIS-403-001` |
| 调用方类型不允许 | 403 | `EIS-403-002` |
| 对象不存在 | 404 | `EIS-404-001` |
| 版本/并发冲突 | 409 | `EIS-409-001` |
| 不支持的媒体类型 | 415 | `EIS-415-001` |
| 依赖不可用 | 503 | `EIS-503-001` |

错误信封固定为：

```json
{ "code": "EIS-403-001", "message": "forbidden", "details": { "reason_code": "EIS-403-001" } }
```

业务拒绝不是 503；不要用 500 表达可预期失败。完整映射见 [错误矩阵](errors.md)。

## 幂等与并发

- 客户端必须带 `Idempotency-Key`；重复键返回首次结果，不重复产生事件。
- 同一聚合的命令由 `LockProvider` 串行化；多实例同时创建同一 ID 时，唯一的约束保证
  一个成功、另一个得到 409/400，不得 500。
- 事件只有在 outbox 写入成功后才返回 `event_ids`；没有 outbox 时显式失败或
  `OutboxWriter.discard()`。

## 相关文档

- [读模型](read-model.md)
- [安全](security.md)
- [ADR-0005 CQRS 与身份契约](adr/ADR-0005-cqrs-and-identity-contract.md)
- [错误矩阵](errors.md)
