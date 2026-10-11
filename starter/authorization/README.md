# starter-authorization

授权服务 Starter：**Spring Boot 原生 OAuth2 授权服务器集成 + seedj 组织缺省**。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-authorization</artifactId>
</dependency>
```

## 职责边界（v1）

- **协议面全部沿用 Boot 原生自动配置**（端点、客户端注册、令牌、OIDC 等），客户端使用原生属性
  配置：`spring.security.oauth2.authorizationserver.client.*`；
- 本模块只补充两件事：
  1. `byteforce.security.authorization.issuer` 非空时注册缺省 `AuthorizationServerSettings`（可被应用覆盖）；
  2. `SeedjJwtClaimsCustomizer`：认证主体为 `CustomUserDetails` 时把 `userId` / `tenantId` /
     `username` / `authorities`（claim 名可配）注入访问令牌——与 `starter-resource` 的解析配置
     对齐形成闭环（权限串取自主体的权限集合，`starter-resource` 的默认链直接消费）。

## 应用需要提供

| Bean | 说明 |
| --- | --- |
| `JWKSource<SecurityContext>` | 签名密钥（生产环境走持久化密钥库） |
| `UserDetailsService` | 用户体系；建议返回 `CustomUserDetails` 以启用身份 claim 注入 |

## 配置

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.security.authorization.enabled` | `true` | 缺省装配开关 |
| `issuer` | — | 授权服务器 issuer；设置后注册缺省 settings |
| `claims.user-id` / `tenant-id` / `username` / `authorities` | `userId` / `tenantId` / `preferred_username` / `authorities` | 令牌身份 claim 名称（权限串取自主体的权限集合；留空则不注入） |

## 验证（冒烟实证）

`mvn -pl starter/authorization -am verify`：测试应用提供内存 `JWKSource` + 原生属性注册
client_credentials 客户端，断言令牌签发 200 与错误凭据 401。

## 边界

- v1 不含：客户端动态注册（DCR）、JDBC 化令牌/客户端存储的封装、与 coord CCT 的联动（后续项）；
- 生产多实例部署需将授权状态（授权/客户端）切换为共享存储（应用侧提供相应 Service Bean）。
