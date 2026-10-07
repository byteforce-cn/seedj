# starter-resource

JWT 资源服务 Starter：**默认安全链 + 401/403 统一输出 + JWT 身份进入请求上下文**。
错误契约见 [ADR-0002](../../docs/adr/0002-error-response-contract.md)。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-resource</artifactId>
</dependency>
```

## 前置条件（fail-fast）

容器中必须存在 `JwtDecoder`：推荐用 Spring Boot 原生属性配置
（`spring.security.oauth2.resourceserver.jwt.jwk-set-uri` / `issuer-uri`），或自行注册 Bean。
缺失时启动即失败。

## 自动生效的能力

| 能力 | 说明 |
| --- | --- |
| 默认安全链 | 无状态会话、CSRF 关闭、`permit-paths` 放行、其余请求需认证 |
| 401 / 403 | 统一 `ApiResponse` 错误体（含 traceId）；401 保留 `WWW-Authenticate: Bearer`；方法级 `@PreAuthorize` 拒绝同样映射 |
| 身份上下文 | JWT claim（`userId`/`tenantId`/`preferred_username`，可配）写入 `RequestContext`，供业务与数据层（多租户/审计）使用 |

## 配置

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.security.resource.enabled` | `true` | 整体开关 |
| `permit-paths` | `/error`、`/actuator/health`、`/actuator/health/**`、`/actuator/info` | 免认证路径 |
| `claims.user-id` | `userId` | 用户 ID claim |
| `claims.tenant-id` | `tenantId` | 租户 ID claim |
| `claims.username` | `preferred_username` | 用户名 claim（缺失回落 `sub`） |
| `claims.authorities` | `authorities` | 自定义权限 claim（与 scope/scp 标准权限合并） |

## 自定义安全策略

注册自己的 `SecurityFilterChain`（`@Order` 更小者优先）即可优先于默认链；`EnableResourceServer`
注解保留用于显式导入场景（幂等）。

## 边界

- v1 不处理不透明令牌（opaque token）与 DPoP；
- 服务间凭据（CCT）与 coord 联动为后续项（见 coord ROADMAP）。

## 测试

`mvn -pl starter/resource -am verify`（MockMvc：401/403 契约、permit 路径、claim 映射与回退）。
