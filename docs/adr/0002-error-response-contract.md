# ADR-0002：错误响应契约（统一错误体 / HTTP 状态 / 异常体系）

| 项 | 值 |
| --- | --- |
| 状态 | Accepted（已采纳；2026-10-07 落地轮，决策与实现同批） |
| 日期 | 2026-10-07 |
| 决策人 | （待填写） |
| 关联 | [`common/web-core`](../../common/web-core/)（`ApiResponse` / `ResponseCode`）；[`common/core`](../../common/core/)（异常体系 / `RequestContext`）；[`starter/web`](../../starter/web/)（全局异常处理 / traceId）；[`starter/resource`](../../starter/resource/)（401/403 输出 / 身份上下文）；评估报告 §3.1 / §3.3 / §3.6 |

## 背景与问题

1. **现状（评估实证，2026-10-07）**：统一返回体 `ApiResponse` 无字段、构造器丢弃入参，实测序列化为 `{}`（P0）；
   全仓无异常体系、无全局异常处理、无请求上下文（traceId），错误输出无契约——业务服务开工即需要的一课缺位。
2. **约束**：
   - 已发布 API 需向后兼容，错误体格式属于对外契约，必须先定后写；
   - 组织内既有前端习惯按 **body.code** 分流业务错误；
   - 网关/监控/探测按 **HTTP 状态** 识别基础设施态（401/404/5xx）；
   - 安全协议（OAuth2 Bearer）要求 401 附带 `WWW-Authenticate`;
   - 技术基线 Spring Boot 4.1.1 / Spring Security 7 / Java 21。

## 决策

### D1 错误响应体（所有错误场景统一）

错误响应体固定为 `ApiResponse<T>`：

```json
{ "code": 10007, "message": "库存不足", "data": null, "traceId": "4f9c…" }
```

- `code`：业务错误码（`0` 仅用于成功）；**业务分支一律以 body.code 为准**；
- `message`：面向调用方的可读消息（非堆栈、不含内部细节）；
- `data`：错误响应恒为 `null`；
- `traceId`：链路追踪 ID（由 `RequestContext` 注入；缺失时为 `null`）。

### D2 HTTP 状态映射（固定表）

| 场景 | HTTP | code |
| --- | --- | --- |
| 业务失败（`BizException` 默认） | **200** | 1xxxx 业务段 |
| 参数校验失败 / 请求体不可读 / 参数缺失 / 类型不匹配 | **400** | 10001 BAD_REQUEST |
| 未认证 | **401** | 10002 UNAUTHORIZED（带 `WWW-Authenticate: Bearer`） |
| 已认证无权限 | **403** | 10003 FORBIDDEN |
| 资源不存在 | **404** | 10004 NOT_FOUND |
| 请求方法不支持 | **405** | 10005 METHOD_NOT_ALLOWED |
| 媒体类型不支持 | **415** | 10009 UNSUPPORTED_MEDIA_TYPE |
| 依赖服务不可用（`RemoteException` 默认） | **503** | 20001 SERVICE_UNAVAILABLE |
| 未预期异常（兜底） | **500** | 20000 INTERNAL_SERVER_ERROR |

规则：

- `BaseException#getHttpStatus()` 可显式指定状态（取值 100–599，越界回落 200）；业务异常默认 200；
- HTTP 状态表达**基础设施语义**，供网关、监控、探测使用；业务分流只看 `code`；
- 错误码段位：`0` 成功；`1xxxx` 业务/客户端段；`2xxxx` 系统段。新增错误码只能追加，不得复用或改变既有语义。

### D3 异常体系（common/core）

- `BaseException(int code, String message[, int httpStatus][, Throwable cause])`：体系基类，`code` 进入响应体；
- `BizException`：业务规则失败，默认 HTTP 200；支持以错误码枚举构造（消息缺省回落枚举 label）；
- `RemoteException`：依赖调用失败，默认 HTTP 503；需要网关超时语义时可显式指定 504；
- 库对外抛出统一异常；**禁止** `throw new RuntimeException(msg)` 作为业务错误（AGENTS §3）。

### D4 消息与暴露面

- 兜底 500 消息固定为“系统内部错误”，堆栈进 error 日志，**不得**随响应外泄；
- 参数类错误消息取首个字段/参数错误（面向调用方可读）；
- 请求体不可读（含枚举未知 code）响应消息使用统一文案，细节进 debug 日志。

### D5 traceId 全链路

- 请求进入：`TraceContextFilter`（order=-150，先于安全链）解析/生成 traceId → `RequestContext` + MDC +（默认）响应头回写；
- 安全链之后：`IdentityContextFilter`（order=-90）调度身份贡献器填充用户/租户；
- 错误体统一携带 `traceId`；响应头名称与回写开关可配（`byteforce.web.trace-id-header` / `expose-trace-id`）；
- 异步传播：`RequestContext.wrap(Runnable/Callable)`；过滤器在请求结束统一清理（含 MDC），防线程复用串扰。

### D6 成功响应不做全局自动包装

- 成功由业务显式返回 `ApiResponse.success(data)`；
- **不引入** `ResponseBodyAdvice` 全局包裹（避免破坏文件下载、第三方协议端点、双重包装与契约不透明）。

### D7 i18n 暂缓

- v1 消息不做多语言（不引入 `MessageSource` 依赖链）；`message` 支持自定义覆盖，为后续 i18n 预留入口。

## 备选方案与驳回理由

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 方案 A：全部 HTTP 200 + body code | 驳回 | 网关/探针无法感知 401/5xx；OAuth2 401 协议头语义无法承载 |
| 方案 B：HTTP 状态完全镜像业务 code | 驳回 | 1xxxx 无标准状态可映射；且与“业务分流只看 code”口径冲突，脆弱 |
| 方案 C：RFC7807 `ProblemDetail` | 驳回 | 与既有 `ResponseCode`/`ApiResponse` 资产冲突；前端需重写分流逻辑，迁移成本大于收益 |
| 方案 D：全局 `ResponseBodyAdvice` 自动包裹成功响应 | 驳回 | 破坏非 JSON 端点与流式响应；双重包装风险；契约不透明 |
| 方案 E：错误消息国际化随 v1 交付 | 驳回 | 需求未明确、链路成本高；预留 message 覆盖入口即可 |

## 后果

- **正面**：错误面一次定契约，全组织 Web/安全/校验/兜底输出一致；traceId 贯穿网关与业务日志，排障路径可寻址；
  成功率/错误率可按 HTTP 状态与 code 双维度监控。
- **负面 / 成本**：业务失败默认 HTTP 200 与部分 REST 直觉不同，需要文档与团队宣导；错误码段位成为长期约束。
- **风险**：历史服务若不按契约输出，网关分流规则需容忍；已通过「新增不改旧」的错误码纪律降低。

## 未决事项

- i18n 引入时机与 key 命名规范；
- `traceId` 是否进入成功响应体（当前仅错误体与响应头）；
- 字段级错误明细（`errors[]`）是否纳入错误体（当前仅取首个字段消息）。

## 参考

- [评估报告 §3.1 / §3.3 / §3.6](../assessment/2026-10-07-business-starter-gap-assessment.md)
- [ADR-0003：JSON 序列化契约](./0003-json-serialization-contract.md)
- 实现：[`GlobalExceptionHandler`](../../starter/web/src/main/java/cn/byteforce/seedj/starter/web/GlobalExceptionHandler.java)、
  [`BaseException`](../../common/core/src/main/java/cn/byteforce/seedj/core/exception/BaseException.java)、
  [`ApiResponse`](../../common/web-core/src/main/java/cn/byteforce/seedj/web/core/ApiResponse.java)

## 修订记录

| 日期 | 修订 |
| --- | --- |
| 2026-10-07 | 初稿：错误体 / HTTP 映射 / 异常体系 / traceId / 边界与备选（与实现同批落地） |
