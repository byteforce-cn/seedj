# 命令契约

所有写操作走 `POST /commands/{type}`。命令类型、权限、载荷、校验与脱敏由
`ObjectModuleConfigurer` 一次声明；`CommandCatalog` 从模块注册表构造，HTTP、
授权、审计与事件共用同一份声明。

## 一个命令的组成

```java
@Configuration(proxyBeanMethods = false)
public class HrEmployeeModule implements ObjectModuleConfigurer {

    private final HrEmployeeCreateHandler create;
    private final CommandPayloadRedactor redactor;

    @Override
    public void configure(ObjectModules modules) {
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", FieldType.TEXT).required()
                .field("name", FieldType.TEXT).required()
                .command("hr.employee.create", HrEmployeeCreate.class)
                    .permission("hr.employee.write")
                    .creates()
                    .handler(create)
                    .emits("hr.employee.created")
                    .redactor(redactor)
                    .commandField("id_number");
    }
}
```

`CommandHandler` 是业务规则的唯一落点：

```java
public interface CommandHandler<C> {
    UUID targetId(C command);
    AppendRequest decide(C command, CommandContext ctx);
    default List<CommandEvent> events(C command, AppendResult result, CommandContext ctx) {
        return List.of();
    }
}
```

- `targetId` 必须是纯函数；运行时在幂等检查**之后**才调用。
- `decide` 在锁内、事务内执行，能看到 `current`；状态机、字段合并与拒绝都放这里。
- `ctx.event(type, payload)` 自动生成事件 id 与幂等键
  `commandId:type:index`（index 从 0 递增）。
- `AbstractCommandHandler` 提供 `requireCurrent`、`merge`、`create`、`correct`、
  `reject`；它只做动作，不替业务做决定。

## 运行时顺序

1. 解析请求体与 `Caller`；无认证返回 401 `EIS-401-001`。
2. `CommandCatalog` 查注册项；未知类型 400，未登记权限 403。
3. 幂等键检查：按 `(issuer, idempotency_key)` 回放；命中时不解码、不调用
   `targetId`，直接返回首次结果。
4. 解码 → 校验 → `targetId`。
5. 写入 `PENDING` 命令记录（带 `leaseUntil`）。
6. 取锁（对象类型 + 目标 ID）→ 事务开始。
7. 读当前行 → 写范围检查 → `decide` → 对将要写入的字段做第二次写范围检查。
8. 写对象行 + 事件（outbox）→ 审计 → 命令终态。
9. 提交事务；返回 `command_id`、`status`、`event_ids`、`locator` **或** `target_ref`、`version`。

锁、事务或 outbox 缺失时启动失败；只有显式
`seedj.command.degraded-mode=true`（local/test）才允许降级，并打印 WARN。
`DegradedModeGuard` 在非 local/test 直接拒绝启动。

## 写范围

写路径与读路径共用 `ScopePredicate`：

- `decide` 前：`ScopePredicates.matches(current, writeScope, schema)`，旧行越界即 403。
- `decide` 后：用 `AppendRequest.fields()` 构造合成行再做一次，关闭“创建一条自己看不见的行”。
- 没有 `DataScopeResolver` 支持该对象类型时写范围解析失败，命令被拒；不默认放行。

## 事件溯源命令（advanced）

同一个 `POST /commands/{type}` 也服务事件溯源写路径。声明时把 `.handler(...)` 换成
`.agent(...)`，两者恰好一个：

```java
modules.object("hr_employee", "hr_employee")
        .readPermission("hr.employee.read")
        .field("employee_no", FieldType.TEXT).required()
        .field("name", FieldType.TEXT).required()
        .field("hire_date", FieldType.DATE).required()
        .command("hr.employee.onboard", OnboardEmployeeCommand.class)
            .permission("hr.employee.write")
            .agent(onboardingAgent)          // CommandAgent<...>
            .commandField("employee_id")     // 聚合标识在载荷里，不在 schema 里
            .emits("hr.employee.onboarded");
```

- 载荷 record 必须就是 agent 解析出的命令类型；聚合标识由
  `command.aggregateId()` 提供，经 `IdCodec` 编码为 `target_ref`。
- `.creates()` 可省略：省略时取 `agent.isCreateCommand()`；显式声明必须与 agent 一致。
- 请求里的 `targets` 对 advanced 命令必须为空；目标来自载荷。
- 执行顺序仍是锁 → 事务 → 加载/创建 → scope → handle → 事件白名单 → 事件可见性 →
  save → publish → audit → 命令终态。任一步失败都回滚事件流。
- 写范围：谓词引用的 schema 字段必须有聚合访问器（`department()` /
  `getDepartment()` / `isDepartment()`）；缺失即 500，不降级放行。
- 事件可见性：事件属性落在 schema 且 `eventVisible=false`（`writeOnly` / sensitive）时
  500，不静默剔除。
- 接受回执：`locator` 为空，`target_ref` + `version` 非空；`event_ids` 非空。
  agent 在事务内为每个事件分配 UUID，`EventPublisher.publish(event, eventId)` 用同一个
  ID 写 outbox/transport，因此回执、命令记录与 outbox 的 `message_id` 一致；
  幂等重放返回同一组 `event_ids`。
- 生产事件存储：`PostgresEventStore`（`event_stream` 表，版本 CAS + 唯一约束裁决并发，
  payload 由 `EventPayloadSerializer` 编码）。`EventSourcedRepository` 会在加载前把
  聚合 `apply` 覆盖的事件类型注册进 `EventTypeRegistry`，重启后第一次读取也能解析；
  sealed 事件族会连同 permitted 子类型一起注册。**不要**在业务里用 `Class.forName`
  或手写事件类型映射。
- 装配：`new PostgresEventStore<>(jdbcTemplate, idCodec, new EventTypeRegistry())`；
  starter 在 `seedj.event-store.enabled=true`（默认）且 `provider=postgres`（默认）时
  自动注册 `EventSchemaCapability`（迁移 scope `event`）。事件流写入加入命令事务，
  与 outbox、审计和命令终态同生共死。
- `CommandAgent.execute(C)` 已删除；业务与测试只能经 `CommandCatalog` /
  `CommandRuntime` 进入。

## 幂等与并发

- 请求体带 `idempotency_key`（不是 HTTP header）；同一键只在同一 issuer 内回放。
- 同键同载荷 → 返回原 `command_id`；同键不同载荷 → 409 `EIS-409-001`。
- 同一对象的命令由 `LockProvider` 串行化；并发创建由唯一约束裁决。
- 技术故障释放 PENDING 并删除命令记录；客户端可安全重试。
- PENDING 超过 `seedj.command.pending-lease`（默认 60s）后由 `CommandReaper`
  删除，幂等键重新可用；若执行中的命令被回收，`complete` 更新 0 行会让事务回滚。

## 错误矩阵

| 场景 | HTTP | code |
|---|---|---|
| 载荷结构/类型错误 | 400 | `EIS-400-001` |
| 业务校验拒绝 | 400 | `EIS-400-002` |
| 时间契约冲突 | 400 | `EIS-400-003` |
| 未认证 / 令牌无效 | 401 | `EIS-401-001` |
| 缺少权限 / 写范围越界 | 403 | `EIS-403-001` |
| 对象不存在 | 404 | `EIS-404-001` |
| 幂等冲突 | 409 | `EIS-409-001` |
| 版本/并发冲突 | 409 | `EIS-409-002` |
| 依赖不可用 | 503 | `EIS-503-001` |

错误信封固定为：

```json
{ "code": "EIS-403-001", "message": "forbidden", "details": { "reason_code": "EIS-403-001" } }
```

业务拒绝不是 503；不要用 500 表达可预期失败。完整映射见 [错误矩阵](errors.md)。

## 相关文档

- [读模型](read-model.md)
- [安全](security.md)
- [错误矩阵](errors.md)
