# common/core

seedj 最底层的零依赖模块：**code 枚举契约、异常体系与请求上下文**。

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
| `RequestContext` | 请求上下文：traceId / 用户 / 租户 / 用户名；MDC 键常量；`wrap(Runnable/Callable)` 异步传播 |

## 示例

```java
throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "库存不足");

Optional<Long> tenantId = RequestContext.tenantId();
executor.submit(RequestContext.wrap(() -> doWork()));
```

## 边界

- 零第三方依赖（仅 JDK）；
- 上下文写入/清理责任在过滤器（starter-web）；后台任务需自行 `RequestContext.setTenantId(...)`；
- 异常 `message` 面向调用方，禁止携带堆栈/内部细节；
- Jackson 3 是唯一允许的第三方线格式依赖，且必须 `provided`（见 [ADR-0010](../docs/adr/ADR-0010-core-provided-jackson.md)）。

## 测试

`mvn -pl common/core -am verify`（common 域覆盖率门槛 ≥ 80%）。
