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

## 生产可用性边界

IAM 走自研路线，但**未达以下退出标准前，不得在 README、决策指南或发布说明中宣称
「生产可用 IAM」**。覆盖率不能替代 RFC 一致性，demo 不能替代安全评审。

| 退出标准 | 当前状态 | 证据位置 |
|---|---|---|
| 模块覆盖率 ≥ 85%（行覆盖）；分支阈值只升不降 | 已达标：line 0.981 / branch 0.878（2026-11-07） | `seedj-iam-starter/target/site/jacoco/` |
| OIDC Core / Discovery / PKCE / 令牌撤销 | 授权请求前置校验与 PKCE / 端点参数 / 令牌类型边界已有单元测试；Discovery 与撤销仍是 IT | `seedj-iam-starter/src/test/java/cn/byteforce/seedj/starter/iam/web/`、`OidcIT` |
| SCIM 2.0 filter / PATCH | 有解析与端点测试 | `ScimFilterParserTest`、SCIM 端点测试 |
| TOTP RFC 6238 | 有测试 | `TotpGeneratorTest` |
| 密钥轮换、令牌撤销、MFA 限流、SCIM 过滤可复现 | 部分 | 按能力逐个补 |
| 独立安全评审 | 未进行 | 评审记录归档到本仓 |

差距计划（按阶段）：

1. **覆盖率**：✅ 已完成。`iam/web`、`scim`、`identity`、`config`、`token`、`security`、
   `abac`、`rbac`、`read`、`nativeauth`、`authz` 全部补齐，模块 line 0.981 / branch 0.878；
   基线已收紧到 0.96/0.86。
2. **RFC 套件**：把现有 OIDC/SCIM/TOTP 测试收拢成可一键执行的套件，缺失项（令牌撤销、
   PKCE 负例、Discovery 字段）补测试。
3. **安全证据**：密钥轮换、令牌撤销、MFA 限流、SCIM 注入负例各留可复现脚本/测试。
4. **独立评审**：达标后安排一次外部评审，结论写回本仓。
