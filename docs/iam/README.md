# IAM Starter

一个依赖、一个注解启动完整 IAM：

```xml
<dependency>
  <groupId>cn.byteforce.seedj</groupId>
  <artifactId>seedj-iam-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

```java
@SpringBootApplication
@EnableIamServer
public class IamApplication {
    public static void main(String[] args) {
        SpringApplication.run(IamApplication.class, args);
    }
}
```

```yaml
seedj:
  iam:
    enabled: true
    issuer: https://iam.example.com
    store: jdbc
    secret:
      key: ${IAM_SECRET_KEY}          # base64 32 bytes；或启用 seedj.transit
    admin:
      bootstrap-username: admin
      bootstrap-password: ${IAM_ADMIN_PASSWORD}
    token:
      access-ttl: 15m
      refresh-ttl: 30d
      include-permissions: true
    mfa:
      totp: { enabled: true, issuer: Seedj, digits: 6, period: 30s, window: 1 }
    native-auth:
      enabled: true                  # 仅第一方 App；默认关闭
    scim: { enabled: true, base-path: /scim/v2 }
    rbac: { enabled: true }
    abac: { enabled: true }
    clients:
      - client-id: web
        client-secret: ${WEB_CLIENT_SECRET}
        redirect-uris: [https://app.example.com/login/oauth2/code/web]
        scopes: [openid, profile, read]
        grant-types: [authorization_code, refresh_token]
```

## 端点

| 路径 | 认证 | 说明 |
|---|---|---|
| `/oauth2/**`, `/.well-known/**`, `/userinfo` | SAS | OIDC / OAuth2 |
| `/scim/v2/**` | Bearer + `scim.*` scope | SCIM 2.0 User/Group |
| `/iam/mfa/totp/**` | session 或 Bearer | TOTP 注册/确认/状态/禁用 |
| `/iam/admin/**` | `PERM_iam:admin` | 角色、权限、策略管理 |
| `/actuator/**` | Basic + ADMIN/iam-admin | 运维 |

## 生产检查

- 不要开启 `seedj.iam.demo.enabled`；demo client 只用于测试。
- `store=jdbc` 时配置稳定的 `seedj.iam.secret.key` 或启用 Transit，否则 JWK/TOTP 密文
  无法跨重启解密。
- 多资源服务器时设置 `seedj.iam.token.resource-audience`，并在各资源服务器校验 `aud`。
- native-auth 只对第一方 App 开放，并配合 App 完整性证明、限流、锁定、设备绑定。
