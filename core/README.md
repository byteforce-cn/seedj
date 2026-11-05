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
| `BaseException` | 异常体系基类：`code`（进入响应体）+ `httpStatus`（默认 200，见 ADR-0002） |
| `BizException` | 业务规则失败（默认 HTTP 200）；支持以 `ResponseCode` 等错误码枚举构造，消息缺省回落 label |
| `RemoteException` | 依赖调用失败（默认 HTTP 503；可显式指定 504 等） |
| `RequestContext` | 请求上下文：traceId / 用户 / 租户 / 用户名；MDC 键常量；`wrap(Runnable/Callable)` 异步传播 |

## 示例

```java
throw new BizException(ResponseCode.BIZ_ERROR, "库存不足");

Optional<Long> tenantId = RequestContext.tenantId();
executor.submit(RequestContext.wrap(() -> doWork()));
```

## 边界

- 零第三方依赖（仅 JDK）；
- 上下文写入/清理责任在过滤器（starter-web）；后台任务需自行 `RequestContext.setTenantId(...)`；
- 异常 `message` 面向调用方，禁止携带堆栈/内部细节。

## 测试

`mvn -pl common/core -am verify`（common 域覆盖率门槛 ≥ 80%）。
