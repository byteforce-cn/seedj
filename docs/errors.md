# 错误码与 HTTP 状态

所有错误响应固定 `{code,message,details}`；`details.reason_code` 提供细分原因（缺省回落到
`code`）。映射只有一处：`seedj-biz-starter` 的 `ContractErrorHandler`。

## 契约错误（ContractException）

| 异常 | HTTP | code | 触发条件 | 调用方动作 |
|---|---|---|---|---|
| `INVALID_PAYLOAD` | 400 | `EIS-400-001` | 请求体/参数结构、类型、日期格式错误；未知命令类型；未知过滤字段 | 修正请求，不要重试 |
| `COMMAND_REJECTED` | 400 | `EIS-400-002` | 业务规则拒绝（CQRS 事件溯源路径） | 按 `message` 修正 |
| `TEMPORAL_VIOLATION` | 400 | `EIS-400-003` | 时间区间非法、系统时间轴回改 | 修正业务时间 |
| `UNAUTHENTICATED` | 401 | `EIS-401-001` | 无凭证、调用方 ID 无法编码为 actor | 重新认证 / 配置 `CallerIdCodec` |
| `FORBIDDEN` | 403 | `EIS-403-001` | 命令权限缺失（descriptor.permission） | 申请权限 |
| `CALLER_NOT_ALLOWED` | 403 | `EIS-403-002` | 主体类型不在契约声明内 | 使用允许的主体类型 |
| `OBJECT_NOT_FOUND` | 404 | `EIS-404-001` | 对象/命令不存在；更新不存在的对象 | 检查标识 |
| `IDEMPOTENCY_CONFLICT` | 409 | `EIS-409-001` | 同一幂等键 + 不同载荷 | 换键或复用原载荷 |
| `VERSION_CONFLICT` | 409 | `EIS-409-002` | 乐观并发冲突（CQRS `CONCURRENCY_CONFLICT`） | 重新加载后重试 |
| `READ_MODEL_NOT_READY` | 409 | `EIS-409-003` | 读模型未追平 `min_version`；响应带 `Retry-After` | 稍后重试 |
| `INTERNAL_ERROR` | 500 | `EIS-500-001` | 未预期内部错误；响应只带 `traceId` | 联系服务方，勿重试 |
| `UNAVAILABLE` | 503 | `EIS-503-001` | 存储/事件通道不可用（CQRS `STORE_UNAVAILABLE`）；命令未被接受 | 可安全重试 |

## 非契约错误

| 异常 | HTTP | code | 说明 |
|---|---|---|---|
| `CqrsException(COMMAND_REJECTED)` | 400 | `EIS-400-002` | 事件溯源业务拒绝；`details.reason_code=CQRS-COMMAND_REJECTED` |
| `CqrsException(CONCURRENCY_CONFLICT)` | 409 | `EIS-409-002` | 流版本已变化 |
| `CqrsException(INVALID_STREAM_ID/INVALID_ID)` | 400 | `EIS-400-001` | 标识/流不合法 |
| `CqrsException(STORE_UNAVAILABLE)` | 503 | `EIS-503-001` | 事件存储未装配 |
| `CqrsException(其它)` | 500 | `EIS-500-001` | 带 `traceId`，不泄露内部细节 |
| `AggregateNotFoundException` | 404 | `EIS-404-001` | `details.repository` / `details.aggregate_id` |
| `InfrastructureException` / `DataAccessException` | 503 | `EIS-503-001` | 依赖不可用 |
| 非法 JSON / 参数类型不匹配 | 400 | `EIS-400-001` | 不泄露解析细节 |

## 命令边界的三条硬规则

1. **未授权是 403，不是 401/500**：身份已认证但缺少 `descriptor.permission()`。
2. **业务拒绝不是 503**：`CqrsException(COMMAND_REJECTED)` → 400；只有依赖不可用才 503。
3. **一个 code 只对应一个状态**：`OBJECT_NOT_FOUND` 恒为 404（读路径与命令路径一致）。

## 测试证据

- `ContractErrorHandlerTest`：上面每一行的状态码与 `code`。
- `CommandControllerTest`：403 / 400 / 409 / 未知类型 / 非 UUID 调用方。
- `HrEmployeeHttpTest`、`HrServerPostgresIT`：真实 Spring 上下文下的错误矩阵。
