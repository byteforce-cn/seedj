# core

seedj 最底层的零 Spring 模块：**领域契约、读路径、命令 seam 与请求事实**。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>core</artifactId>
</dependency>
```

## 能力

| 类型 | 说明 |
| --- | --- |
| `CodeEnum<C>` / `CodeEnums` | 「业务 code ↔ 枚举」契约与工具（`of` / `ofRequired` / `toMap`）；供 codegen、Jackson、MyBatis 三处联动 |
| `ContractException` / `ContractErrorCode` | 契约面错误（命令/查询/授权）：冻结线格式错误码，由 Web 边界转成 `ApiError` |
| `InfrastructureException` / 各能力 `*ErrorCode` | 技术错误与能力错误码；映射在 Web 边界完成 |
| `ScopePredicate` / `ScopePredicates` | 受限行范围谓词 AST 与内存求值；`Not` 是二值逻辑 |
| `ScopeContext` / `ScopeContextHolder` | 不可变请求事实快照与 ThreadLocal 作用域；`ContextWriter` / `ContextResolver` 扩展 |
| `FieldDefinition` / `Privacy` / `Maskers` | 字段声明、隐私层级与默认打码 |
| `ReadGateway` / `FieldPolicy` | 唯一读入口：对象权限、行范围、字段策略与读审计 |
| `ObjectModules` / `CommandHandler` | 实体声明 DSL 与显式命令处理器 seam |

## 示例

```java
throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "库存不足");

modules.object("employee", "hr_employee")
        .readPermission("hr.employee.read")
        .field("name", FieldType.TEXT).required()
        .command("hr.employee.create", HrEmployeeCreate.class)
            .permission("hr.employee.write")
            .handler(createHandler);

try (ScopeContextHolder.Scope ignored = ScopeContextHolder.open(context)) {
    gateway.search("employee", search, caller);
}
```

## 边界

- 零 Spring / JDBC 依赖；Jackson 3 仅用于线格式且必须 `provided`（见 [ADR-0010](../docs/adr/ADR-0010-core-provided-jackson.md)）；
- 请求事实由 `ContextWriter` 写入、`ScopeContextFilter` 打开；后台任务自行
  `ScopeContextHolder.open(...)`；
- 业务读只能经 `ReadGateway`；`TemporalStore.current` 只供写路径使用；
- 异常 `message` 面向调用方，禁止携带堆栈/内部细节。

## 测试

`./mvnw -o -B -pl core test`（覆盖率门槛见 `core/pom.xml`）。
