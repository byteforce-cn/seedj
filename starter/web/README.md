# starter-web

Web 运行时 Starter：**全局异常处理（统一错误响应契约）与请求上下文（traceId / 用户 / 租户，MDC 联动）**。
契约见 [ADR-0002](../../docs/adr/0002-error-response-contract.md)。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-web</artifactId>
</dependency>
```

## 自动生效的能力

| 能力 | 说明 |
| --- | --- |
| 全局异常处理 | `BaseException`（业务 200 / 依赖 503）、校验 400、404/405/415、兜底 500；错误体统一 `ApiResponse{code,message,data:null,traceId}` |
| 校验基线 | 引入 `spring-boot-starter-validation`；`@Valid` body / 方法级校验 / jakarta 约束违例统一 400 |
| traceId | 请求头（默认 `X-Trace-Id`）进入或生成；`RequestContext` + MDC + 响应头回写；非法值清洗、限长 64 |
| 身份上下文 | 安全链后调度 `RequestContextContributor`（如 starter-resource 的 JWT 解析），MDC 同步；贡献器异常隔离 |

## 配置

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.web.enabled` | `true` | 整体开关 |
| `byteforce.web.error-handling-enabled` | `true` | 全局异常处理开关 |
| `byteforce.web.request-context-enabled` | `true` | 请求上下文过滤器开关 |
| `byteforce.web.trace-id-header` | `X-Trace-Id` | 读取/回写的请求头 |
| `byteforce.web.expose-trace-id` | `true` | 是否回写响应头 |

## 边界

- 成功响应**不做**全局自动包装（业务显式 `ApiResponse.success`）；
- `AccessDeniedException` / `AuthenticationException` 的 MVC 侧映射在 `starter-resource`（安全模块），避免本模块依赖安全栈；
- 过滤器在请求结束统一清理上下文与 MDC；异步任务使用 `RequestContext.wrap(...)` 传播。

## 测试

`mvn -pl starter/web -am verify`（MockMvc 端到端覆盖错误矩阵、traceId 与身份注入）。
