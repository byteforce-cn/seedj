# IAM 全能力就绪度评估：SCIM 2.0 / TOTP / RBAC+ABAC / native-auth / PostgreSQL

- 日期：2026-11-04
- 范围：`starter`（IAM / Biz）、`core`、`domain-infra-memory`、`domain-jdbc-postgresql`、`docs/spi`、`docs/adr`
- 方法：代码审查 + Maven 依赖面分析 + Spring Authorization Server（SAS）7.1.1 API 核对 + 现有 OIDC 集成测试清单比对
- 结论：**OIDC 基线很强，但 IAM 仍是“OIDC 演示壳”**。SCIM 2.0、TOTP、RBAC、ABAC、native-auth、IAM JDBC 持久化六项均为 0；当前“一依赖 + 一注解”只能启动一个带内存 client/authorization 的授权服务器，不能启动可用的 IAM。SPI 的纵向依赖方向是干净的，但存在 5 类依赖污染（横向/隐藏/装配形状/API/治理），其中迁移器形状与非 PostgreSQL 误装配必须在 IAM JDBC 落地前修掉。

---

## 0. 结论摘要

| 能力 | 当前实现 | 差距 | 结论 |
|---|---|---|---|
| OIDC / OAuth 2.0 | SAS 7.1.1，授权码 + PKCE + refresh + mTLS + introspection + revocation + logout；`OidcIT` 实际 **100** 个用例全绿 | 生产持久化、用户存储、角色/权限 claim、`aud`/`typ`、JWK 轮换、撤销传播 | **可复用基线，不要重写** |
| SCIM 2.0 | 无 | 契约、表、User/Group、PATCH、filter、分页、ETag、错误模型、SCIM 客户端认证 | 新增；建议先做核心 User/Group，再评估 bulk/EnterpriseUser |
| TOTP | 无 | RFC 6238/4648、注册/确认/校验/恢复码、密钥加密、重放防护、MFA 流程 | 新增；浏览器与 native-auth 共用校验器 |
| RBAC | 无；Biz 只把 `scope` 映射成 `SCOPE_*` | 角色/权限/层级/组、claim、管理 API、资源服务器强制 | 新增；ABAC 的前置 |
| ABAC | 无 | 策略模型、PDP、资源属性解析器、拒绝优先、审计/缓存 | 新增；不要自造通用脚本语言 |
| native-auth grant | 无；只有 `authorization_code`/`refresh_token` | 自定义 grant、converter/provider、token generator、MFA challenge、设备绑定、风控 | 新增；非标准，建议只对第一方 App 开放 |
| IAM JDBC 持久化 | 只有 eventbus outbox；SAS 的 JDBC 仓库类在 classpath 中但未使用 | IAM/SAS 表、迁移 scope、`InfrastructureCapability`、SAS JDBC repository、JWK 持久化 | 新增；这是所有能力的底座 |
| 一依赖 + 一注解 | `starter` + `@EnableIamServer` 形态存在 | 注解带 `@SpringBootApplication`；无用户存储；强依赖 `PkiProvider`/issuer；硬编码 demo client；starter 过胖 | **形态对，内容缺，需要重构** |

一句话：**OIDC 已经“能跑”，IAM 还“不能用”**。新增能力不是往 `IamServerAutoConfiguration` 里继续堆 bean，而是先补领域契约、用户存储、JDBC 持久化和装配边界。

---

## 1. 现状架构

### 1.1 模块依赖

```text
core（契约，零 Spring）
 ├── domain-infra-memory      PKI / Transit / EventBus 本地队列 / ID
 ├── domain-jdbc-postgresql   outbox + 版本化迁移
 ├── workflow-infra-memory    工作流内存实现
 └── starter                  Biz 资源服务器 + IAM 授权服务器 + EventBus 装配
```

Maven 依赖方向是正确的：`core ← 实现模块 ← starter`，`core` 没有 Spring 依赖（`core/pom.xml:18-38`）。`starter/src/main` 只 import `core` 契约和同模块类，没有 import 任何 `domain-*`/`workflow-*` 实现类（grep 证据见附录 A）。这是本仓库最值得保留的部分。

### 1.2 IAM 运行时链路

```text
@EnableIamServer
  ├── @SpringBootApplication          ← 注意：注解本身带启动器语义
  └── @Import(IamServerAutoConfiguration)

IamServerAutoConfiguration（@Configuration + @EnableWebSecurity）
  ├── SecurityFilterChain #1  OAuth2/OIDC 端点
  │     ├── AuthorizationRequestConformanceFilter
  │     ├── EndpointParameterConformanceFilter
  │     ├── IdTokenIntrospectionGuard
  │     └── oauth2ResourceServer(jwt)  ← userinfo 用，JwtDecoder 由 Boot AS JWT auto-config 提供
  ├── SecurityFilterChain #3  默认 formLogin + ReauthAwareAuthenticationSuccessHandler
  ├── SecurityFilterChain #2  actuator
  ├── InMemoryRegisteredClientRepository（default-client / tls-client）
  ├── InMemoryOAuth2AuthorizationService
  ├── InMemoryOAuth2AuthorizationConsentService
  └── JWKSource ← PkiProvider.signingIdentity("iam-jwt-signer")
```

关键事实：

- 生产代码**没有** `UserDetailsService`、`PasswordEncoder`、`OAuth2TokenCustomizer`、`OidcUserInfoMapper`。
- `starter/src/test/java/cn/byteforce/seedj/iam/IamServer.java` 是测试夹具，自己提供 `InMemoryUserDetailsManager`；它不是 starter 的能力。
- 默认 client 是硬编码的 `default-client`（`{noop}secret`）和 `tls-client`，redirect 固定为 `127.0.0.1:8080`。
- IAM 的客户端、授权、同意全部在内存；重启即丢失。
- `OidcIT` 实际 100 个用例（`starter/target/failsafe-reports/cn.byteforce.seedj.test.OidcIT.txt`），不是旧评估文档写的 93；旧文档的路径也仍是 `starter/src/test/java/cn/byteforce/seedj/test/OidcIT.java`，实际包名是 `cn.byteforce.seedj.test`、文件在 `.../it/OidcIT.java`。这是文档漂移，不影响本次结论。

### 1.3 “一依赖 + 一注解”今天实际发生了什么

业务方按 README 写：

```java
@EnableIamServer
public class IamApplication {
    public static void main(String[] args) {
        SpringApplication.run(IamApplication.class, args);
    }
}
```

启动后：

1. `@EnableIamServer` 自带 `@SpringBootApplication`，所以它既是“功能开关”，又是“应用启动器”；业务方不能再把它放到普通 `@Configuration` 上而不产生组件扫描/自动配置副作用。
2. `IamServerAutoConfiguration` 需要 `spring.security.oauth2.authorizationserver.issuer`（`IamServerAutoConfiguration.java:318-319`），缺失直接启动失败。
3. `JWKSource` 需要 `PkiProvider`（`:303`）；如果 `seedj.pki.enabled=false` 或换 JDBC 密钥库，IAM 启动失败。
4. 没有 `UserDetailsService` 时，Boot 的 `UserDetailsServiceAutoConfiguration` 可能生成一个随机 `user`；业务方没有可用的用户体系。
5. 没有 JDBC client/authorization/consent；默认 client 是 demo 配置。
6. 新增一个业务 client 必须自己写 `RegisteredClientRepository` bean，Boot 标准 `spring.security.oauth2.authorizationserver.client.*` 属性被硬编码仓库覆盖。

所以当前形态满足“一个依赖 + 一个注解能启动”，但不满足“启动一个全功能 IAM”。

---

## 2. 能力评估

### 2.1 OIDC / OAuth 2.0 基线

**已有：**

- SAS 7.1.1；授权码 + PKCE S256、refresh 轮换、OIDC ID Token、UserInfo、introspection、revocation、RP-Initiated Logout。
- mTLS `tls_client_auth`；`X509ClientCertificateAuthenticationConverter` 顺序修正。
- 授权端点/token/introspection/revocation 的 RFC 语义过滤器。
- `OidcIT` 100 个用例覆盖 RFC 8414/6749/6750/7009/7636/7662/8414、OIDC Core、token type confusion、mTLS。

**缺口（与本轮 IAM 直接相关）：**

| 缺口 | 影响 | 建议 |
|---|---|---|
| Access Token `aud=client`，无 `typ=at+jwt` | 多资源服务器/多 client 时 token 边界不清；现有 Biz 只用“有 scope”阻断 ID Token | 走 ADR 引入 RFC 9068；AT `typ=at+jwt`，`aud=resource`，RS 校验 `typ`/`aud` |
| 无 token customizer | 无法签发 `roles`/`permissions`/`groups`/`amr`/`acr`/`tenant_id` | 新增 `IamTokenCustomizer`，按 token type/grant type 注入 |
| 无 `OidcUserInfoMapper` | UserInfo 只有默认 claim，SCIM/RBAC 属性无法映射 | 新增 `IamUserInfoMapper`，与 SCIM/OIDC claim 映射共用 |
| JWK 单密钥、无轮换 | 重启/换 key 后旧 token 失效；无法平滑轮换 | `iam_jwk` 表存 active/retired 公钥；JWKS 发布全部公钥，签名只用 active |
| 撤销不传播到 RS | revoke 后 AT 在 exp 前仍可用 | 明确语义；需要立即生效时 RS 接 introspection 或 denylist |
| 默认 client / `{noop}` | 生产后门 | 默认关闭 demo client；client 从 JDBC/properties 加载 |
| 元数据静态 | `scopes_supported`/`claims_supported` 不含新能力 | 从 repository 聚合，至少把 SCIM/roles/permissions/amr/acr 加入 |

**结论：** OIDC 部分应作为回归基线保留；本轮只做“加 claim、加用户存储、加持久化、修 token 边界”，不要重写协议层。

### 2.2 SCIM 2.0

**当前：** 0。没有契约、表、端点、过滤器、错误模型。

**需要的最小闭环（RFC 7643/7644）：**

| 面 | 必须支持 | 可后置 |
|---|---|---|
| 发现 | `/scim/v2/ServiceProviderConfig`、`/Schemas`、`/ResourceTypes` | — |
| User | `GET/POST/PUT/PATCH/DELETE /Users`，`userName` 唯一，`active`，`externalId`，`password` 只写，`groups` 只读 | EnterpriseUser 扩展、profile 图片 |
| Group | `GET/POST/PUT/PATCH/DELETE /Groups`，`displayName`，`members` | 嵌套组、动态组 |
| Filter | `eq/ne/co/sw/ew/pr/and/or/not` + 括号；至少 `userName`、`externalId`、`emails.value`、`active`、`members.value` | 全量排序语法 |
| PATCH | `add/remove/replace`，支持 `path` 与 `members[value eq "..."]` | 复杂多值路径 |
| 分页 | `startIndex`（1-based）、`count`、`totalResults`、`itemsPerPage` | 游标分页 |
| 并发 | `meta.version` + `If-Match`，冲突 412 | — |
| 错误 | `urn:ietf:params:scim:api:messages:2.0:Error` + `scimType` | — |
| 认证 | OAuth2 bearer，`scim.users.read/write`、`scim.groups.read/write` | 独立 SCIM API key |

**设计建议：**

- 路径固定 `/scim/v2`，Content-Type `application/scim+json`。
- SCIM 是 provisioning API，不是第二套用户模型：直接复用 `IamUserRepository` / `IamGroupRepository`；`password` 走 `PasswordEncoder`；`active=false` 同时禁用登录并撤销 token。
- Group 是 SCIM 的 canonical 组；角色分配单独放 `iam_group_role`，不要让 SCIM Group 直接等于 RBAC Role，否则角色层级和权限无法表达。
- SCIM 客户端用 `client_credentials` 注册为普通 OAuth2 client，token 无用户 principal，映射为 `ROLE_SCIM_CLIENT` + scope authority；为 `/scim/**` 单独建 stateless `SecurityFilterChain`，不要落到默认 form-login 链。
- Filter 解析器必须把 AST 映射到**白名单列 + 参数化 SQL**，禁止把 filter 字符串拼进 SQL。`PATCH` 的 `path` 同样要白名单。
- 不建议一上来引入 SCIMple 等重框架；先实现核心 User/Group + 上面的子集，用 conformance 测试锁定行为。若后续必须 bulk/完整 EnterpriseUser，再评估 SCIMple。

**验收：** CRUD、PATCH、filter、分页、ETag、错误 `scimType`、SCIM client 认证、`active=false` 撤销 token、SCIM 创建的用户可走 OIDC 登录。

### 2.3 TOTP

**当前：** 0。

**设计：**

- RFC 6238 TOTP：HMAC-SHA1（默认）/SHA256/SHA512，6 位，30 秒，允许 ±1 步；Base32 按 RFC 4648，自己实现约 60 行，避免引第三方 TOTP 库。
- 注册：
  - `POST /iam/mfa/totp/enroll` → 生成 20 字节随机 secret，加密存 `iam_totp`（pending），返回 `otpauth://totp/{issuer}:{username}?secret=...&issuer=...&algorithm=SHA1&digits=6&period=30`。
  - `POST /iam/mfa/totp/confirm` → 校验 code，激活，生成 10 个恢复码（只返回一次，BCrypt 存哈希）。
  - `POST /iam/mfa/totp/disable` → 要求密码或有效 TOTP；删 secret/恢复码；撤销会话/refresh token（按产品语义）。
  - `GET /iam/mfa/totp/status` → 是否启用、恢复码剩余数。
- 校验：
  - 用常量时间比较；校验通过后原子更新 `last_used_step`（`UPDATE ... WHERE last_used_step < ?`），拒绝同一 step 重放。
  - 失败计数 + 锁定/退避；不要泄露“用户是否存在/是否启用 TOTP”。
- 密钥保护：
  - `TotpSecretProtector` 端口；默认实现用 `TransitProvider`（AES-GCM，context=userId 作为 AAD），存 `key_name` + `ciphertext`；多实例共享 Transit 状态文件或换 KMS。
  - 绝不明文存 secret，不打印 secret/OTP。
- 浏览器 MFA：
  - 密码认证成功后，如果用户启用 TOTP，不直接给完整 `Authentication`；在 session 存 `MfaPendingAuthentication`（principal、authorities、auth_time、SavedRequest），重定向 `/mfa`。
  - `/mfa` 校验通过后升级为完整认证，轮换 session id，继续 `SavedRequest` 或 `ReauthAwareAuthenticationSuccessHandler`。
  - `prompt=login`/`max_age` 强制重认证时，MFA 必须一起重做。
- native-auth MFA：
  - 两阶段：第一次 `native-auth` 返回 `mfa_required` + 短期 `mfa_token`；第二次带 `mfa_token` + `otp` 换 token。
  - `mfa_token` 用不透明随机值，哈希存 `iam_mfa_challenge`，绑定 `user_id/client_id/device_id`，5 分钟 TTL，单次使用，限制尝试次数。
- Claim：ID Token/AT 增加 `amr=["pwd","totp"]`、`acr="urn:seedj:acr:totp"`、`auth_time`；恢复码登录时 `amr=["pwd","recovery"]`。

**验收：** RFC 6238 附录 B 向量、±1 窗口、重放拒绝、错误码、恢复码一次性、禁用后登录不再要求 TOTP、浏览器/native 两条链路。

### 2.4 RBAC

**当前：** 0。`BizServerAutoConfiguration` 只把 `scope` 映射为 `SCOPE_*`（`BizServerAutoConfiguration.java:113-121`），注释里提到 roles/permissions 但未实现。

**模型：**

```text
iam_role(id, tenant_id, code, name, description, parent_id, built_in, version)
iam_permission(id, code, resource, action, description)
iam_role_permission(role_id, permission_id)
iam_user_role(user_id, role_id, assigned_at, assigned_by)
iam_group(id, tenant_id, display_name, external_id, ...)
iam_group_member(group_id, user_id)
iam_group_role(group_id, role_id)
```

- 权限码约定：`order:read`、`order:write`、`iam:user:manage`；资源/动作分开存，便于 ABAC 复用。
- 有效权限 = 直接角色 ∪ 组角色 ∪ 角色继承；PostgreSQL 用 recursive CTE 一次算完。
- `roles`、`permissions`、`groups`、`tenant_id` 注入 AT/ID Token；权限数量大时提供开关（`include-permissions=false`）并走 introspection/entitlements。
- 资源服务器：`JwtAuthenticationConverter` 同时映射 `roles → ROLE_*`、`permissions → PERM_*`、`groups → GROUP_*`；开启 `@EnableMethodSecurity`。
- 管理 API：`/iam/admin/roles`、`/iam/admin/permissions`、`/iam/admin/users/{id}/roles`；用 `iam:admin` 保护。SCIM 负责用户/组，RBAC 管理 API 负责角色/权限，不要混在一个端点。
- 缓存：按 `userId + authzVersion` 缓存有效权限，TTL 30–60 秒；角色/权限变更递增 `iam_authz_epoch` 或发 EventBus 事件；多实例不能只靠本地缓存。

**验收：** 角色继承、组授权、权限并集、token claim、`@PreAuthorize("hasAuthority('PERM_order:read')")`、变更后下一次 token/刷新生效、缓存失效。

### 2.5 ABAC

**当前：** 0。

**定位：** ABAC 不是替代 RBAC，而是在 RBAC 之上做“同一权限下的细粒度条件”。没有 RBAC 的权限码，ABAC 会退化成在业务代码里写 if。

**最小模型：**

```text
IamPolicy {
  effect: ALLOW | DENY,
  priority: int,
  actions: Set<String>,        // order:read
  resources: Set<String>,      // order:*
  condition: PolicyCondition   // JSON AST
}
```

条件 AST 只支持类型化操作：`eq/ne/in/contains/startsWith/gt/lt/and/or/not/exists`；操作数来自：

- `subject.*`：部门、职级、角色、组、tenant、认证方式（amr/acr）。
- `resource.*`：ownerId、tenantId、classification、status。
- `action`：当前权限码。
- `environment.*`：时间、IP、设备可信度、客户端。

**关键 seam：**

```java
public interface IamResourceAttributeResolver {
    boolean supports(String resourceType);
    Map<String, Object> resolve(String resourceType, String resourceId, IamSubject subject);
}
```

没有这个端口，ABAC 只能看 token 里的属性，无法判断“资源属于当前部门/本人”。业务方为自己的资源实现 resolver；默认拒绝。

**PDP：**

```java
public interface IamPolicyDecisionPoint {
    IamAuthorizationDecision decide(IamAuthorizationRequest request);
}
```

- 默认实现：DENY 优先、默认拒绝、按 priority 排序、短路；决策缓存 key = `(subjectVersion, resourceType, resourceId, action)`，短 TTL。
- 复杂策略不要自造 SpEL/JS 解释器；内置 DSL 覆盖 80%，剩余用 `IamPolicyDecisionPoint` 接 OPA/Cedar 适配器。
- 强制点：`@PreAuthorize("@iam.authorize(authentication, 'order', #id, 'read')")`，或 `AuthorizationManager` 用于 Web 端点。业务代码不直接查策略表。
- 审计：每次 DENY 记录 subject/resource/action/reason（脱敏），用于排障和合规。

**验收：** 允许/拒绝/默认拒绝、DENY 优先、资源属性解析、缓存失效、越权访问审计、策略管理 API 的 JSON 校验。

### 2.6 native-auth grant

**当前：** 0。SAS 默认只有 `authorization_code`、`refresh_token`、`client_credentials` 等；没有自定义 grant。

**可行性（已核对 SAS 7.1.1）：**

- `OAuth2AuthorizationServerConfigurer.tokenEndpoint(...)` 支持 `accessTokenRequestConverter(...)`、`authenticationProvider(...)`（`javap` 已验证）。
- `OAuth2TokenContext`/`DefaultOAuth2TokenContext`/`JwtGenerator` 可用于自定义签发。
- `OAuth2AuthorizationServerMetadata.Builder.grantType(String)` 可发布自定义 grant type。
- `OAuth2Authorization.Builder` 可保存 `principalName`、grant type、scopes、tokens、attributes。

**建议协议：**

```text
grant_type = urn:seedj:params:oauth:grant-type:native-auth
client_id  = mobile-app          （public client，无 secret）
username / password
device_id / device_name / app_version
scope = openid profile offline_access
```

如果启用 MFA：

```text
第一次：username + password
  → 400 { "error": "mfa_required", "mfa_token": "...", "mfa_methods": ["totp"] }

第二次：mfa_token + otp
  → 200 access_token + refresh_token + id_token
```

**实现要点：**

- `NativeAuthAuthenticationConverter` 只在 `grant_type` 匹配时返回自定义 token；其余返回 null，让 SAS 原有 converter 继续。
- `NativeAuthAuthenticationProvider`：
  - 校验 client 是否注册了该 grant type；public client 必须有 `client_id`，confidential client 走 secret/basic。
  - 用 `IamIdentityService` 校验密码（未知用户也做一次 dummy hash，防时序枚举）。
  - 如果 TOTP 启用：校验 `mfa_token` + OTP；否则直接通过。
  - 风控：按 username/IP/device 限流、失败计数、锁定；设备黑名单。
  - 生成 `UsernamePasswordAuthenticationToken`（authorities 来自 RBAC），用 `DefaultOAuth2TokenContext` + `JwtGenerator` 签发 AT/refresh/ID Token。
  - 保存 `OAuth2Authorization`（JDBC 时落 `oauth2_authorization`），attributes 只放简单字符串/Map（device_id、amr、acr），不要放自定义 `Authentication` 对象，避免 JDBC Jackson 反序列化问题。
- 刷新：复用 SAS `OAuth2RefreshTokenAuthenticationProvider`；refresh token 轮换；设备绑定校验（refresh 时比对 device_id，可选）。
- 撤销：`/oauth2/revoke` 对 refresh token 生效；用户禁用/改密时按 principal 删除 `oauth2_authorization`。
- 元数据：`.grantType(NATIVE_AUTH.getValue())`，并在 `grant_types_supported` 中出现。
- 错误：`invalid_client`、`invalid_grant`、`unsupported_grant_type`、`mfa_required`、`mfa_invalid`、`account_locked`；不要把“用户不存在”和“密码错误”分开。

**安全边界（必须写进 ADR）：**

- 这是非标准 grant，等价于把资源所有者密码凭证（ROPC）搬进 token 端点；OAuth 2.1 已不推荐。
- **默认推荐**：移动端仍用系统浏览器 + 授权码 + PKCE（RFC 8252）。native-auth 仅对第一方、可做 App 完整性证明（Play Integrity / App Attest）的 App 开放。
- 必须：TLS、限流、锁定、设备绑定、短 AT、refresh 轮换、异常登录审计、不要把 client secret 放进 App。
- 不建议：把 IAM 登录页嵌入 App 的 WebView；App 自绘登录页必须避免仿冒 IdP，且要能证明 App 未被篡改。

**验收：** 正确密码、错误密码、未知用户、MFA 两阶段、OTP 重放、refresh 轮换、撤销、限流/锁定、metadata、public/confidential client、无 secret 泄露。

### 2.7 IAM JDBC 持久化（`domain-jdbc-postgresql` + `InfrastructureCapability`）

**当前：** `domain-jdbc-postgresql` 只有 outbox 与 `JdbcMigrationRunner`；没有 IAM 表、没有 SAS JDBC repository、没有 IAM schema capability。

**可直接复用：**

SAS 7.1.1 自带：

- `JdbcRegisteredClientRepository`（`oauth2_registered_client`）
- `JdbcOAuth2AuthorizationService`（`oauth2_authorization`）
- `JdbcOAuth2AuthorizationConsentService`（`oauth2_authorization_consent`）

这些类已通过 `javap` 核对存在，不需要自己写 OAuth2 持久化。需要做的是：

1. 把 SAS 官方 DDL 作为版本化 `Migration` 交付；
2. 用 `Jdbc*` 替换 `IamServerAutoConfiguration` 里的 `InMemory*`；
3. 配置 Jackson 3（SAS 7.1.1 有 `Jackson3` 内部实现），确保 `tools.jackson` 在运行时可用。

**建议 schema 边界：**

| 表组 | 表 | 说明 |
|---|---|---|
| 用户 | `iam_user`、`iam_user_credential` | 用户、密码/凭证；`username_normalized` 唯一；`attributes jsonb` |
| MFA | `iam_totp`、`iam_recovery_code`、`iam_mfa_challenge` | TOTP 密文、恢复码哈希、native MFA 挑战 |
| RBAC | `iam_role`、`iam_permission`、`iam_role_permission`、`iam_user_role`、`iam_role_parent` | 角色层级、权限、分配 |
| 组 | `iam_group`、`iam_group_member`、`iam_group_role` | SCIM Group + 组授权 |
| ABAC | `iam_policy`、`iam_authz_epoch` | 策略 JSONB、缓存版本 |
| SCIM | 复用 `iam_user`/`iam_group`；`external_id` + `version` | 不另建第二套用户表 |
| 审计/风控 | `iam_audit`、`iam_login_attempt` | 登录、SCIM、角色变更、MFA |
| OAuth2 | `oauth2_registered_client`、`oauth2_authorization`、`oauth2_authorization_consent` | SAS 官方表 |
| 密钥 | `iam_jwk` | active/retired 签名密钥；可选 PKI 适配器 |
| 迁移 | `seedj_schema_history` | 已有；scope 分开 |

**迁移/装配设计：**

- 新增 `IamSchemaCapability implements InfrastructureCapability`，`NAME="postgres-iam-schema"`，`MIGRATION_SCOPE="iam"`，`getOrder()=200`。
- 新增 `OAuth2SchemaCapability`，`MIGRATION_SCOPE="oauth2"`，`getOrder()=210`；与 IAM 表分开演进。
- **不要**把 IAM DDL 塞进 `eventbus-outbox` scope；`JdbcMigrationRunner` 的 `(scope, version)` 主键就是为多能力准备的。
- `IamJdbcAutoConfiguration` 条件：
  - `@ConditionalOnClass({JdbcTemplate.class, DataSource.class})`
  - `@ConditionalOnBean(JdbcTemplate.class)`
  - `@ConditionalOnProperty(prefix="seedj.iam", name="store", havingValue="jdbc")`
  - 每个 bean `@ConditionalOnMissingBean`
- `seedj.iam.store=memory` 时用 `domain-infra-memory` 的实现；没有 DataSource 且 store=jdbc 时**快速失败**并给出明确错误，不要静默退化。
- `JdbcMigrationRunner` 需要重构（见 §4 D6）：现在是 `new JdbcMigrationRunner(dataSource, scope)`，scope 绑在构造器上；IAM 不能复用 outbox 的 runner bean，否则要么复制一个 runner，要么让 IAM 依赖 eventbus outbox。正确形状是 `JdbcMigrationRunner(DataSource)` + `migrate(scope, migrations)` / `validate(scope, migrations)`，全模块共享一个 bean。
- `iam_jwk`：JWK 不应再强依赖 `PkiProvider`。默认 JDBC 存 RSA 密钥（私钥用 Transit 加密或 KMS），JWKS 发布 active + retired 公钥；PKI 作为可选适配器。这样 IAM 可以独立于 PKI 启动。
- 撤销：用户禁用/改密时，按 `principal_name` 删除/失效 `oauth2_authorization`（JDBC SQL）；in-memory 模式提供 `IamTokenRevoker` 内存索引。不要只靠短 TTL。
- 多实例：迁移用 advisory lock（已有）；schema 初始化只跑一次；client/authorization/consent 跨实例可见；`iam_authz_epoch`/缓存版本跨实例一致。

**验收：** Testcontainers PostgreSQL；迁移幂等/checksum/未知版本/并发；SAS JDBC 重启后 client/authorization/consent 仍在；两个上下文共享 DB；用户禁用后 refresh 失效；schema capability 在无 IAM 开关时不执行。

### 2.8 “一依赖 + 一注解”

**目标形态：**

```xml
<dependency>
  <groupId>cn.byteforce.seedj</groupId>
  <artifactId>seedj-iam-starter</artifactId>
</dependency>
<!-- 生产：JDBC + PostgreSQL -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

```java
@SpringBootApplication
@EnableIamServer          // 只做 @Import，不再带 @SpringBootApplication
public class IamApplication { ... }
```

```yaml
seedj:
  iam:
    enabled: true
    issuer: https://iam.example.com
    store: jdbc                  # jdbc | memory
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
      enabled: true
      mfa-required: true
    scim: { enabled: true, base-path: /scim/v2 }
    rbac: { enabled: true }
    abac: { enabled: true }
```

**必须补齐：**

- `IamProperties`（`@ConfigurationProperties` + `@Validated`），替代 `@Value`。
- `IamUserRepository` + `UserDetailsService` adapter + `PasswordEncoder`（BCrypt/Argon2）；没有用户存储时快速失败，或 dev profile 生成一次性 admin 并 WARN。
- `RegisteredClientRepository` 从 JDBC/properties 加载；删除硬编码 `default-client`/`tls-client`，demo client 用 `seedj.iam.demo.enabled=true` 显式开启。
- `IamTokenCustomizer`、`IamUserInfoMapper`。
- `IamSchemaCapability`/`OAuth2SchemaCapability`。
- `@AutoConfiguration` + `AutoConfiguration.imports`（或专用 starter 的 imports），不要再靠 `@Import` 一个 `@Configuration`。
- `@ConditionalOnMissingBean` 覆盖所有可替换 bean；`@ConditionalOnProperty` 控制 SCIM/TOTP/native/RBAC/ABAC 子能力。
- 安全链拆分：AS、SCIM、IAM admin/MFA、actuator、default 各自 `securityMatcher`，不要都落到 form-login 链。
- 去掉 `@EnableIamServer`/`@EnableBizServer` 的 `@SpringBootApplication` 元注解。
- 模块依赖瘦身：`seedj-iam-starter` 不强制拖入 eventbus/outbox/actuator；JDBC/Postgres 用 optional/runtime 或单独的 `seedj-iam-starter-jdbc`。

---

## 3. 目标架构建议

### 3.1 模块

推荐（按优先级）：

```text
core
  cn.byteforce.seedj.iam              IamUser/IamRole/IamPolicy/... + 用例接口 + IamException
  cn.byteforce.seedj.iam.spi          IamUserRepository/IamAuthorizationRepository/
                                      IamAuditSink/TotpSecretProtector/IamSigningKeyStore/
                                      IamResourceAttributeResolver/IamPolicyDecisionPoint

domain-infra-memory
  cn.byteforce.seedj.iam.memory       本地/测试实现（用户、角色、TOTP、策略、审计）

domain-jdbc-postgresql
  cn.byteforce.seedj.iam.jdbc         JDBC 实现 + IamSchemaCapability + OAuth2SchemaCapability
                                      + JdbcMigrationRunner 复用

seedj-iam-starter（新增，推荐）
  cn.byteforce.seedj.iam.autoconfigure IamAutoConfiguration + IamProperties + 条件
  cn.byteforce.seedj.iam.security      SAS 配置、token customizer、UserDetails adapter、
                                      TOTP filter/provider、native-auth converter/provider
  cn.byteforce.seedj.iam.web           SCIM controller、admin controller、MFA controller
  cn.byteforce.seedj.iam.annotation    @EnableIamServer（只 @Import）

starter（保留聚合）
  Biz + EventBus + 对 seedj-iam-starter 的可选聚合
```

最小路径（不改模块数）：把上述 IAM 包直接放 `starter`，但 **POM 依赖必须 optional/条件化**，否则“一依赖”仍然会拖入整个 IAM+Postgres+actuator。推荐在 SCIM/ABAC 开始前拆出 `seedj-iam-starter`，否则 `starter` 会变成 god module。

### 3.2 契约分层

- **业务入口（深接口）**：`IamIdentityService`（认证/密码/TOTP）、`IamAuthorizationService`（角色/权限）、`IamProvisioningService`（SCIM 用例）、`IamPolicyDecisionPoint`（ABAC）。业务方只注入这些。
- **机制端口（SPI）**：`IamUserRepository`、`IamAuthorizationRepository`、`IamAuditSink`、`TotpSecretProtector`、`IamSigningKeyStore`、`IamResourceAttributeResolver`。只允许装配层和 provider 实现方看见。
- **Spring 适配器**：`IamUserDetailsService`（实现 Spring `UserDetailsService`）、`IamPasswordEncoder`、`IamTokenCustomizer`、`IamUserInfoMapper`、`NativeAuth*`、`Totp*`、`Scim*`。不要让 `core` 依赖 Spring Security 的 `UserDetailsService`/`Authentication`。

### 3.3 安全链

| 顺序 | matcher | 认证方式 | 说明 |
|---|---|---|---|
| 1 | `/oauth2/**`、`/.well-known/**`、`/userinfo`、`/connect/**` | SAS | 现有 AS 链 |
| 2 | `/scim/**` | Bearer JWT + SCIM scope | stateless、CSRF off |
| 3 | `/iam/**` | 浏览器 session 或 Bearer | MFA 注册/校验、admin API；浏览器 CSRF on |
| 4 | `/actuator/**` | Basic/ADMIN | 现有 actuator 链 |
| 5 | 其余 | formLogin | 默认链；处理 MFA pending、`/error` |

新增 SCIM/admin 端点时必须显式加 matcher；否则会落到默认链，被 form-login/session 保护，Bearer 调用失败。

### 3.4 Token claim

| claim | 来源 | 签发到 |
|---|---|---|
| `sub` | `IamUser.id` | AT/ID |
| `roles` | 直接 + 组 + 继承角色 code | AT/ID |
| `permissions` | 有效权限 code（可配置开关） | AT |
| `groups` | 组 id/displayName | AT/ID |
| `tenant_id` | 用户/租户 | AT/ID |
| `amr` | `pwd` / `totp` / `recovery` | AT/ID |
| `acr` | `urn:seedj:acr:pwd` / `...:totp` | AT/ID |
| `auth_time` | 认证时刻 | ID/AT |
| `device_id` | native-auth | AT（可选） |
| `sid` | 会话 | ID |

权限很多时不要无限膨胀 token：`include-permissions=false`，资源服务器用 introspection/entitlements；或只放 roles，细粒度走 PDP。

---

## 4. SPI 依赖污染审计

### 4.1 审计口径

“依赖污染”分五类：

1. **纵向**：实现模块 import 另一个实现模块的类（应无）。
2. **横向**：一个 artifact 把无关能力的依赖带给所有消费者（fat module）。
3. **隐藏**：代码/自动配置依赖某个类或 bean，但没有 `@ConditionalOnClass`/`@ConditionalOnBean`/POM 声明。
4. **装配形状**：机制 bean 被塑造成只服务一个能力，第二个能力只能复制。
5. **API/治理**：注解、契约包、文档把实现细节或启动器语义泄漏给业务方。

### 4.2 发现

| ID | 严重度 | 类型 | 发现 | 证据 | 影响 | 建议 |
|---|---|---|---|---|---|---|
| D1 | **P0** | API 形状 | `@EnableIamServer`/`@EnableBizServer` 元注解带 `@SpringBootApplication` | `EnableIamServer.java:14`、`EnableBizServer.java:14` | 注解=启动器，不适合组合；业务方已有 `@SpringBootApplication` 时重复；组件扫描副作用 | 去掉 `@SpringBootApplication`，只保留 `@Import`；启动类自己加 |
| D2 | **P0** | 横向依赖 | `starter` 非可选引入 memory、JDBC、Postgres 驱动、JDBC starter、actuator、OAuth2 AS+RS | `starter/pom.xml:19-57` | 任何业务应用都被拖入 IAM+DB+actuator；无法按需裁剪 | 拆 `seedj-iam-starter`；JDBC/Postgres/actuator optional/runtime；auto-config 条件化 |
| D3 | **P0** | 隐藏依赖 | IAM `JWKSource` 强依赖 `PkiProvider`，无 `@ConditionalOnBean` | `IamServerAutoConfiguration.java:303` | 关闭 PKI 或换 JDBC 密钥库时 IAM 启动失败 | 定义 `IamSigningKeyStore`；JDBC JWK 默认；PKI 适配器 optional |
| D4 | **P0** | 缺失能力 | starter 无用户存储/`PasswordEncoder`；测试宿主才有 `UserDetailsService` | `IamServerAutoConfiguration` 无相关 bean；`starter/src/test/java/cn/byteforce/seedj/iam/IamServer.java` | “一注解”启动后只能靠 Boot 默认用户；生产不可用 | JDBC/memory `IamUserRepository` + `UserDetailsService` adapter + `PasswordEncoder` |
| D5 | **P0** | 安全 | 默认 client `{noop}secret`、固定 redirect、固定 TLS client | `IamServerAutoConfiguration.java:251-297` | 生产后门/弱口令 | 默认不注册 demo client；`seedj.iam.demo.enabled=false`；从 properties/JDBC 加载 |
| D6 | **P1** | 装配形状 | `JdbcMigrationRunner` 构造时绑定 scope；outbox 独占唯一 bean | `JdbcMigrationRunner.java:56,64`；`OutboxAutoConfiguration.java:32-36` | IAM 无法复用 runner；要么复制 runner，要么依赖 eventbus outbox | 改成 `JdbcMigrationRunner(DataSource)` + `migrate(scope, migrations)`；共享 bean |
| D7 | **P1** | 隐藏依赖 | `OutboxAutoConfiguration` 只按 `JdbcTemplate` 激活，不校验 PostgreSQL | `OutboxAutoConfiguration.java:24-28` | MySQL/MariaDB 应用启动时执行 `pg_advisory_xact_lock` 失败 | `@ConditionalOnProperty(provider=postgres)` 或 `DatabaseDriver` 条件；文档 |
| D8 | **P1** | 隐藏依赖 | `PostgresOutboxStore` 静态使用 Jackson，但 POM 是 `provided`，auto-config 无 `@ConditionalOnClass` | `PostgresOutboxStore.java:34`；`domain-jdbc-postgresql/pom.xml:61-65` | 缺 Jackson 时 `NoClassDefFoundError` | 改非 optional，或加 `@ConditionalOnClass(JsonMapper.class)` + 明确错误 |
| D9 | **P1** | 横向依赖 | `domain-infra-memory` 打包 PKI（BouncyCastle）+ Transit + EventBus + ID；BC 是 compile | `domain-infra-memory/pom.xml:30-34` | 只用 ID/Transit 也拖入 BC | 拆适配器或 BC optional；PKI 用户显式加 BC |
| D10 | **P1** | 装配/API | IAM 用 `@Configuration` + 注解导入，不在 `AutoConfiguration.imports`；无 Properties/条件 | `IamServerAutoConfiguration.java:68`；`starter/src/main/resources/META-INF/spring/...AutoConfiguration.imports` | 与 R3 不一致；无法按 profile/属性启停；配置靠 `@Value` | `@AutoConfiguration` + `@EnableConfigurationProperties(IamProperties)` + `@ConditionalOnProperty` |
| D11 | **P1** | 配置 | `@Value("${spring.security.oauth2.authorizationserver.issuer}")` 必填，缺失时启动失败且无业务提示 | `IamServerAutoConfiguration.java:318-319` | 业务方复制测试夹具才能起 | `IamProperties.issuer` + `@Validated` + 清晰错误 |
| D12 | **P1** | 覆盖 | 硬编码 `RegisteredClientRepository` 覆盖 Boot `spring.security.oauth2.authorizationserver.client.*` | `IamServerAutoConfiguration.java:251-297` | 标准 Boot 配置失效；必须写 bean | 用 Boot properties 或 `IamClientProperties`；保留 `@ConditionalOnMissingBean` |
| D13 | **P2** | 文档/治理 | `docs/spi/README.md` 说 `domain-jdbc-postgresql` 不该有 Spring 装配，但实际有 `OutboxAutoConfiguration` | `docs/spi/README.md:40`；`OutboxAutoConfiguration.java` | IAM JDBC 放哪、谁来装配会反复争论 | 更新宪法：允许实现模块自带 auto-config，或把装配收回 starter；二选一并写清楚 |
| D14 | **P2** | 契约面 | `core` 同时承载 infra + 100+ workflow 类型 | `core` jar / `SpiSurfaceTest` | 只用 PKI 也拿到 workflow API 面 | 后续拆 `workflow-spi`；当前不阻塞 |
| D15 | **P2** | 安全/边界 | IAM 链只匹配 AS 端点；SCIM/admin 会落到 form-login 链 | `IamServerAutoConfiguration.java:203-214` | 新增 SCIM 后默认被 session 保护，不是 bearer | 新增 `/scim/**`、`/iam/**` 专用 stateless chain |
| D16 | **P2** | 测试装配 | `ClusterExtension` 打开 `spring.main.allow-bean-definition-overriding=true` | `starter/src/test/java/cn/byteforce/seedj/it/extension/ClusterExtension.java:327` | 测试可能掩盖重复 bean；生产默认 false 会启动失败 | 装配测试单独关闭 overriding；测试 `jwtDecoder` 用 `@Primary`/`@ConditionalOnMissingBean` 解决 |

### 4.3 正面发现（不要改坏）

| 项 | 证据 | 价值 |
|---|---|---|
| `core` 零 Spring | `core/pom.xml:18-38` | 契约层可被非 Spring 实现复用 |
| `starter/src/main` 不 import 实现模块 | grep 无 `cn.byteforce.seedj.jdbc`/`memory`/`workflow.memory` import | 纵向依赖干净 |
| `core` test-jar 依赖均 `test` scope | `domain-infra-memory/pom.xml:23-29`、`domain-jdbc-postgresql/pom.xml:23-29`、`workflow-infra-memory/pom.xml:23-29` | JUnit/AssertJ 不进入生产 classpath |
| `SpiSurfaceTest` 白名单 | `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java` | 防止机制类型泄漏到契约包 |
| Outbox 无 DataSource 不启动 | `OutboxAutoConfiguration.java:24-28` | 无 DB 应用不会因 outbox 炸 |
| EventBus 无 Micrometer/Jackson 降级 | `EventBusAutoConfiguration.java:104-125` | 可选依赖不阻断启动 |
| `InfrastructureCapability` + 迁移 | `core/.../InfrastructureCapability.java`、`JdbcMigrationRunner` | IAM schema 可直接复用这套生命周期 |

### 4.4 结论

- **没有纵向循环/实现互相 import**，这是好消息。
- 真正恶心的是 **D6/D7/D8**：机制 bean 被 outbox 的形状绑架、只按 `JdbcTemplate` 判断 PostgreSQL、Jackson 是事实必需但被标 `provided`。如果直接照抄 `OutboxAutoConfiguration` 去做 IAM JDBC，会把这些问题复制成第二套。
- **D3/D4/D5/D10/D12** 是 IAM 自身从“demo”走向“产品”必须修的；其中 D5 是安全 P0。
- **D1/D2** 决定“一依赖 + 一注解”是不是真的成立；不修的话，业务方拿到的仍是一个带 `@SpringBootApplication` 的胖 starter。

---

## 5. 实施顺序与工作量

| 阶段 | 内容 | 验收 | 粗略工作量 |
|---|---|---|---|
| **P0 底座** | 拆 `seedj-iam-starter`；`@EnableIamServer` 去 `@SpringBootApplication`；`IamProperties`；`IamUserRepository` + `UserDetailsService` + `PasswordEncoder`；JDBC schema capability；SAS JDBC repository；JWK 持久化；删除 demo `{noop}` client；修 `JdbcMigrationRunner` 形状；outbox 按 PostgreSQL 条件化 | 一个空应用加依赖+注解，配置 DB 后能登录；重启 client/authorization 不丢；无 PKI 也能起；OIDC 100 用例回归 | 2–3 周 |
| **P1 RBAC** | 角色/权限/组/层级表；有效权限计算；token claims；Biz `JwtAuthenticationConverter` 映射；admin API；缓存/失效 | `@PreAuthorize` 生效；角色变更下一次 token/刷新生效 | 1.5–2 周 |
| **P1 TOTP** | RFC 6238/4648；注册/确认/校验/恢复码；Transit 加密；浏览器 MFA pending 流程；`amr`/`acr` | RFC 向量、重放、窗口、恢复码、浏览器登录 | 1–1.5 周 |
| **P1 native-auth** | 自定义 grant、converter/provider、token generator、MFA 两阶段、设备绑定、限流/锁定、metadata | 正确/错误密码、MFA、refresh、revoke、限流 | 1.5–2 周 |
| **P2 SCIM 2.0** | User/Group CRUD、PATCH、filter、分页、ETag、错误、SCIM client 认证；`active=false` 撤销 | 核心 SCIM conformance 用例；SCIM 用户可 OIDC 登录 | 2–3 周 |
| **P2 ABAC** | 策略模型、PDP、资源属性 resolver、`@PreAuthorize("@iam...")`、审计、缓存 | 允许/拒绝/默认拒绝/DENY 优先/越权审计 | 2–3 周 |
| **P2 加固** | 审计、登录限流、锁定、JWK 轮换、token 撤销、SCIM bulk 评估、文档/示例模块 | 安全用例、运维文档、`samples/iam-server` | 1–2 周 |

总计约 **10–14 周**（一名熟悉 Spring Security 的资深工程师，含测试；SCIM filter/PATCH 与 ABAC 策略语言的范围是最大变量）。如果只要“能演示”，可以砍到 4–5 周；但“开箱 IAM”不能砍 JDBC、用户存储、RBAC、TOTP 和安全加固。

**为什么这个顺序：**

1. 所有能力都需要用户存储和 JDBC，先做底座。
2. RBAC 是 ABAC 的权限码来源，先 RBAC。
3. TOTP 是 native-auth 的 MFA 前置，先 TOTP。
4. SCIM 提供用户/组 provisioning，ABAC 需要用户属性，SCIM 在 ABAC 前。
5. native-auth 可以独立于 SCIM；如果移动端优先，可与 SCIM 并行。

---

## 6. 测试策略

| 层 | 位置 | 覆盖 |
|---|---|---|
| 单元 | `core`/`iam-starter` | TOTP RFC 6238 向量；Base32；SCIM filter/PATCH parser；策略求值；native converter/provider；密码/OTP 常量时间 |
| 契约 TCK | `core` test-jar | `IamUserRepositoryContractTest`、`IamAuthorizationRepositoryContractTest`、`IamPolicyStoreContractTest`、`TotpSecretProtectorContractTest`；memory/JDBC 各 `extends` 一行 |
| 装配 | `seedj-iam-starter` | `ApplicationContextRunner`：无 DB、JDBC、memory、demo 开关、PKI 关闭、SCIM/TOTP/native/RBAC/ABAC 开关、用户覆盖 bean |
| 集成 | `starter/src/test` + Testcontainers | OIDC 100 回归；JDBC 重启持久化；多实例 schema；SCIM CRUD/PATCH/filter；TOTP 登录；native-auth 全流程；RBAC/ABAC 资源服务器 |
| 一致性 | `docs/assessment` | SCIM 2.0 conformance（RFC 7643/7644）；OIDC RFC；native-auth 负向；TOTP 重放 |
| 安全 | `starter/src/test` | 限流/锁定、token 撤销、密码/OTP 不落日志、CSRF、open redirect、SCIM filter 注入、越权审计 |
| 性能 | 可选 | token 签发 p95、策略求值 p95、SCIM 列表分页；权限缓存命中率 |

建议新增测试类：

```text
IamJdbcIT                 PostgreSQL schema + SAS JDBC + 重启 + 多实例
IamScimIT                 SCIM User/Group/Filter/Patch/Pagination/ETag/Error
IamTotpIT                 enroll/confirm/login/replay/recovery/disable
IamNativeAuthIT           password/MFA/refresh/revoke/rate-limit
IamRbacIT                 claims + @PreAuthorize + 角色变更
IamAbacIT                 policy allow/deny/default-deny/resource resolver
IamMigrationIT            iam/oauth2 scope 幂等、checksum、并发
IamAutoConfigurationTest  条件装配、无 DB、无 PKI、demo 关闭
```

---

## 7. 开放决策与风险

### 7.1 必须先决策

1. **单租户还是多租户？** SCIM 的 `userName` 唯一性、issuer、角色、策略都受此影响；多租户建议 `tenant_id` 从第一天进表。
2. **native-auth 是否只对第一方 App 开放？** 是否要求 App Attest/Play Integrity？是否保留系统浏览器 + PKCE 作为推荐路径？
3. **SCIM 范围**：只做 User/Group 核心，还是必须 EnterpriseUser/bulk/排序全量？这决定 2 周还是 5 周。
4. **ABAC 策略语言**：内置类型化 DSL，还是直接接 OPA/Cedar？建议内置覆盖 80%，外部 PDP 作为插件。
5. **Token 是否内嵌 permissions？** 权限多时 token 体积/刷新延迟；建议可配置，默认内嵌 roles + 可选 permissions。
6. **JWK 存哪？** JDBC `iam_jwk`、PKI 状态文件、还是 KMS？推荐 JDBC 默认，PKI/KMS 可选。
7. **撤销语义**：refresh 立即失效即可，还是 AT 也要立即失效？后者需要 introspection/denylist。
8. **模块拆分**：是否新增 `seedj-iam-starter`？建议新增；如果坚持留在 `starter`，至少把依赖 optional 化。

### 7.2 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| native-auth 被撞库/钓鱼/App 篡改 | 高 | 第一方限定、App 完整性证明、限流/锁定、设备绑定、短 AT、审计；默认推荐 PKCE |
| SCIM filter/PATCH 复杂度失控 | 高 | 先锁定核心子集 + conformance 测试；bulk/EnterpriseUser 后置 |
| ABAC 自造 DSL 变成半成品语言 | 高 | 类型化 AST、白名单操作、DENY 优先、外部 PDP 插件；不做通用脚本 |
| 多实例权限缓存/撤销不一致 | 中 | `iam_authz_epoch` + 短 TTL；JDBC 撤销；必要时 Postgres LISTEN/NOTIFY |
| TOTP 密钥管理 | 中 | Transit/KMS 加密；key version；轮换；不落日志 |
| SAS 升级导致自定义 grant API 变化 | 中 | 只依赖 `tokenEndpoint` 公开扩展点；集成测试锁定；ADR 记录版本 |
| 模块拆分影响现有用户 | 中 | 保留 `starter` 聚合；新 starter 逐步迁移；1.0.0 未发布，成本可控 |
| PostgreSQL 锁定 | 低（已接受） | 明确 `domain-jdbc-postgresql` 只支持 PG；其他数据库后续适配 |

---

## 8. 验收清单（目标态）

```bash
# 1. 全量构建 + 现有 OIDC 回归
mvn -o -pl core,domain-infra-memory,domain-jdbc-postgresql,starter -am test
mvn -o -pl starter -am verify -Dit.test='OidcIT'

# 2. 新能力（实现后）
mvn -o -pl starter -am verify \
  -Dit.test='IamJdbcIT,IamScimIT,IamTotpIT,IamNativeAuthIT,IamRbacIT,IamAbacIT,IamMigrationIT'

# 3. 形状检查（目标全部为 0）
grep -rn '@SpringBootApplication' starter/src/main/java/cn/byteforce/seedj/*/annotation
grep -rn '{noop}' starter/src/main
grep -rn 'InMemoryRegisteredClientRepository\|InMemoryOAuth2AuthorizationService' starter/src/main
grep -rn 'new JdbcMigrationRunner([^)]*,' domain-jdbc-postgresql/src/main
grep -rn 'ServiceLoader' --include='*.java' .
grep -rn '^import cn\.byteforce\.seedj\.\(jdbc\|.*memory\|workflow\.memory\)' starter/src/main
```

**完成定义：**

- 一个空 Spring Boot 应用，只加 `seedj-iam-starter` + `@EnableIamServer`，配置 `issuer`/`datasource`/bootstrap admin，即可启动并完成 OIDC 登录、SCIM 创建用户、RBAC 授权、TOTP 绑定、native-auth 登录。
- 重启后 client/authorization/consent/用户/角色/策略不丢。
- 关闭 PKI 仍可启动（JDBC JWK）。
- 关闭 SCIM/TOTP/native/RBAC/ABAC 任一子能力，不影响其他能力。
- 现有 100 个 OIDC 用例全绿；新增能力有独立 IT。

---

## 附录 A：证据索引

| 主题 | 文件:行 |
|---|---|
| starter 胖依赖 | `starter/pom.xml:19-57` |
| memory 的 BC compile 依赖 | `domain-infra-memory/pom.xml:30-34` |
| jdbc 的 Jackson provided | `domain-jdbc-postgresql/pom.xml:61-65` |
| IAM 配置入口/无自动配置 | `starter/src/main/java/cn/byteforce/seedj/iam/config/IamServerAutoConfiguration.java:68-69` |
| AS 链/resource server | `.../IamServerAutoConfiguration.java:203-214` |
| 内存 client/authorization/consent | `.../IamServerAutoConfiguration.java:239-297` |
| `{noop}`/硬编码 client | `.../IamServerAutoConfiguration.java:251-297` |
| JWK 依赖 PKI | `.../IamServerAutoConfiguration.java:302-315` |
| `@Value` issuer | `.../IamServerAutoConfiguration.java:318-319` |
| 注解带 `@SpringBootApplication` | `starter/src/main/java/cn/byteforce/seedj/iam/annotation/EnableIamServer.java:14` |
| 同上（Biz） | `starter/src/main/java/cn/byteforce/seedj/biz/annotation/EnableBizServer.java:14` |
| Biz 只映射 scope | `starter/src/main/java/cn/byteforce/seedj/biz/config/BizServerAutoConfiguration.java:113-121` |
| Biz 只校验 issuer+scope | `.../BizServerAutoConfiguration.java:87-99` |
| outbox 只按 JdbcTemplate 条件 | `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/OutboxAutoConfiguration.java:24-28` |
| outbox 独占 migration runner | `.../OutboxAutoConfiguration.java:32-36` |
| migration runner scope 绑定构造器 | `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/JdbcMigrationRunner.java:56,64` |
| PostgresOutboxStore 静态 Jackson | `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/PostgresOutboxStore.java:34` |
| starter auto-config imports 只有 core/eventbus | `starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| SPI 宪法模块表自相矛盾 | `docs/spi/README.md:40` |
| 测试夹具提供用户存储 | `starter/src/test/java/cn/byteforce/seedj/iam/IamServer.java` |
| OIDC 实际 100 用例 | `starter/target/failsafe-reports/cn.byteforce.seedj.test.OidcIT.txt` |
| core 零 Spring | `core/pom.xml:18-38` |
| test-jar 均为 test scope | `domain-infra-memory/pom.xml:23-29`、`domain-jdbc-postgresql/pom.xml:23-29`、`workflow-infra-memory/pom.xml:23-29` |
| 契约白名单 | `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java` |

## 附录 B：SAS 7.1.1 扩展点核对

以下均通过 `javap` 对 `spring-security-oauth2-authorization-server-7.1.1.jar` 核对：

| 扩展点 | 用途 |
|---|---|
| `OAuth2AuthorizationServerConfigurer.tokenEndpoint(...)` | 自定义 grant 入口 |
| `OAuth2TokenEndpointConfigurer.accessTokenRequestConverter(...)` | native-auth 请求解析 |
| `OAuth2TokenEndpointConfigurer.authenticationProvider(...)` | native-auth 认证/签发 |
| `OAuth2TokenContext` / `DefaultOAuth2TokenContext` | 构造签发上下文 |
| `JwtGenerator` / `OAuth2TokenCustomizer<JwtEncodingContext>` | AT/ID Token 签发与 claim 注入 |
| `OAuth2AuthorizationServerMetadata.Builder.grantType(String)` | 发布 native-auth grant type |
| `OAuth2Authorization.Builder` | 保存 principal、grant、scope、token、attributes |
| `JdbcRegisteredClientRepository` | client 持久化 |
| `JdbcOAuth2AuthorizationService` | authorization 持久化 |
| `JdbcOAuth2AuthorizationConsentService` | consent 持久化 |
| `OAuth2AuthorizationCodeAuthenticationProvider` / `OAuth2RefreshTokenAuthenticationProvider` | 现有授权码/refresh 逻辑，native-auth 可复用 token 生成/refresh 语义 |

结论：native-auth 与 JDBC IAM **不需要 fork SAS**，用公开扩展点即可实现；风险主要在安全语义和测试，不在 API 可行性。
