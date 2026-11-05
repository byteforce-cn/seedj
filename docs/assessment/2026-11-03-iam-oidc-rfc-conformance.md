# IAM OIDC / OAuth 2.0 RFC 一致性评估

- 日期：2026-11-03
- 范围：`seedj1/starter` 中的 IAM（Spring Authorization Server 7.1.1）、IM 资源服务器（`BizServerAutoConfiguration`）
- 方法：黑盒 HTTP 探测 + 代码审查 + `OidcIT` 集成测试（93 个用例）
- 结论：**测试覆盖的 RFC MUST 全部通过**；剩余问题集中在令牌受众/类型、撤销传播、生产持久化三类设计项，需要 ADR 或后续任务，不是测试缺口。

---

## 1. 评估结论摘要

改造前 `OidcIT` 有 10 个用例且全部通过，但严格性不足：

- 只覆盖 discovery/JWKS 结构和 3 个 `invalid_client` 负向用例；
- 没有授权码全流程、登录/同意、ID Token/AT 签名与 claims、PKCE 正向、refresh、introspection/revocation 正向语义；
- 两个“安全”用例（未注册 redirect_uri、未注册 post_logout_redirect_uri）实际被匿名登录跳转或 `/error` 分发短路，断言“不是 evil host”必然成立，属于**假阳性**。

改造后：

- `OidcIT` 从 10 个用例扩展到 **93 个用例**，覆盖 RFC 8414、OIDC Discovery、RFC 6749、RFC 6750、RFC 7636、RFC 7662、RFC 7009、OIDC Core、RP-Initiated Logout 与资源服务器侧；
- 新增会话级测试夹具 `IamTestSession`（Cookie、CSRF、登录、同意页、授权码、token、refresh）；
- 修复了实现侧与 RFC 冲突的行为（见第 3 节），使严格用例真实通过，而不是用 `@Disabled` 绕过；
- 全量 `starter` 验证：**94 个测试，0 失败，BUILD SUCCESS**（`OidcIT` 93 + `SeedjIT` 1）。

---

## 2. 覆盖矩阵

> 所有用例位于 `starter/src/test/java/cn/byteforce/seedj/test/OidcIT.java`。

| 规范 | 关键要求 | 对应用例（节选） | 状态 |
|---|---|---|---|
| RFC 8414 §2/§3 | issuer/endpoint 元数据、HTTPS、issuer 与 base URL 一致 | `openidConfigurationMustExposeRequiredMetadata` | ✅ |
| RFC 8414 §5 | `oauth-authorization-server` 与 `openid-configuration` 公共字段一致 | `authorizationServerMetadataMustBeConsistentWithOidcDiscovery` | ✅ |
| RFC 8414 §2 | 声明 JWT client auth 时必须发布签名算法 | `metadataMustAdvertiseJwtClientAuthenticationSigningAlgorithms` | ✅ |
| RFC 7517 | JWKS 只暴露公钥成员、kid 可用 | `jwksMustExposePublicRsaKey`、`idTokenMustBeVerifiableWithPublishedJwks` | ✅ |
| RFC 6749 §3.1 | 匿名 HTML 授权请求进入登录 | `anonymousAuthorizationRequestMustRedirectToLogin` | ✅ |
| RFC 6749 §4.1.2.1 | client_id/redirect_uri 非法时 400 且绝不重定向 | `authorizationRequestWithoutClientIdMustNotRedirect`、`...UnknownClientId...`、`...UnregisteredRedirectUri...`、`...RedirectUriFragment...`、`...NonExactRedirectUri...` | ✅ |
| RFC 6749 §3.3 | scope 大小写、重复值、非法字符 | `...InvalidScope...`、`...WrongCaseScope...`、`...DuplicateScopeValue...` | ✅ |
| RFC 6749 §3.1 | 重复参数 → invalid_request | `...DuplicateState...`、`...DuplicateScopeParameter...` | ✅ |
| RFC 6749 §3.1.2.4 | 不支持 response_type → 重定向 `unsupported_response_type` | `authorizationRequestWithUnsupportedResponseTypeMustRedirectError` | ✅ |
| RFC 7636 §4.2/4.3 | PKCE 必需、仅 S256、challenge 长度 | `...WithoutPkce...`、`...PlainPkce...`、`...ShortCodeChallenge...` | ✅ |
| OIDC Core §3.1.2.7 | response_mode 声明与拒绝未支持值 | `...UnsupportedResponseMode...`、`...QueryResponseModeMustSucceed` | ✅ |
| OIDC Core §3.1.2.1 | max_age 非负整数、强制重认证 | `...InvalidMaxAge...`、`...MaxAgeZeroMustReauthenticate`、`...FreshSessionAndMaxAge...` | ✅ |
| OIDC Core §3.1.2.6 | prompt=none/login/consent 语义 | `...PromptNoneAndNoSession...`、`...PromptNoneAndSession...`、`...MissingConsent...`、`...PromptLoginMustReauthenticate`、`...PromptNoneCombined...` | ✅ |
| RFC 6749 §4.1.2.1 | state 回传；拒绝同意 → access_denied；scope 收窄 | `authorizationSuccessMustEchoState`、`...ConsentDenied...`、`...ConsentMustNarrowGrantedScope` | ✅ |
| OIDC Core §3.1.3 | scope 含/不含 openid 时 ID Token 的签发 | `...WithoutOpenidMustNotIssueIdToken`、`...WithOpenidMustIssueIdToken` | ✅ |
| RFC 6749 §5.1/§5.2 | token 成功/错误、no-store、invalid_client 401 + Basic、invalid_grant 400 | `tokenEndpointMustRejectInvalidBasicClient`、`...InvalidPostClientWith400`、`...MissingClient`、`...InvalidCodeWith400`、`tokenResponseMustBeNonCacheable`、`...MalformedBasicHeader` | ✅ |
| RFC 6749 §3.2 | 缺少/重复参数 | `...MissingGrantType`、`...DuplicateGrantType`、`...DuplicateParameters` | ✅ |
| RFC 7636 §4.6 | code_verifier 错误/缺失/过短 → invalid_grant | `...WrongCodeVerifier`、`...MissingCodeVerifier`、`...ShortCodeVerifier` | ✅ |
| RFC 6749 §4.1.3 | code 绑定 redirect_uri、单次使用 | `...WrongRedirectUri`、`authorizationCodeMustBeSingleUse` | ✅ |
| RFC 6749 §6 | refresh 轮换、旧 refresh 失效、refresh 响应含 ID Token | `refreshTokenMustRotate`、`refreshResponseMustContainIdToken` | ✅ |
| OIDC Core §2 | ID Token 必需 claims、nonce 可选 | `idTokenMustContainRequiredClaims`、`idTokenWithoutNonceMustNotContainNonce` | ✅ |
| RFC 6750 §3/§3.1 | Bearer challenge、invalid_token、insufficient_scope | `userInfoMustRequireBearerToken`、`...RejectInvalidToken`、`...RejectTokenWithoutOpenidScope` | ✅ |
| RFC 7662 §2.2/§2.3 | active/inactive、client 认证、token_type_hint | `introspectionMustReportActiveAccessToken`、`...InactiveUnknownToken`、`...InvalidClient`、`...IgnoreUnknownTokenTypeHint` | ✅ |
| RFC 7009 §2.2/§2.2.1 | 撤销 access/refresh、未知 token 200、invalid_client | `revocationMustRevokeAccessToken`、`...RefreshToken`、`...UnknownToken`、`...InvalidClient` | ✅ |
| OIDC RP-Initiated Logout | 未注册 redirect 拒绝、注册 redirect + state、无效 hint | `logoutMustRejectUnregistered...`、`...RegisteredUriWithState`、`...InvalidIdTokenHint` | ✅ |
| 资源服务器 | JWT 校验、scope → authority、ID Token 不可作为 AT | `resourceServerMustAcceptAccessToken`、`...EnforceScope`、`...RejectTamperedToken`、`...RejectAlgNoneToken`、`idTokenMustNotBeAcceptedByResourceServer` | ✅ |
| 令牌类型混淆 | ID Token 不可被 userinfo/introspection/RS 接受 | `idTokenMustNotBeAcceptedByUserInfo`、`idTokenMustNotBeIntrospectableAsAccessToken`、`idTokenMustNotBeAcceptedByResourceServer` | ✅ |

---

## 3. 实现侧修复

| 文件 | 修复 | 对应规范 |
|---|---|---|
| `iam/web/AuthorizationRequestConformanceFilter.java`（新增） | 在 SAS `AuthorizationFilter`/授权端点校验器之前校验 client_id、redirect_uri 精确匹配、response_type、response_mode、scope、PKCE、max_age、prompt；`prompt=none` 返回 `login_required`/`consent_required`；`prompt=login`/`max_age` 强制重认证；不合法 redirect 直接 400 | RFC 6749 §3.1.2/§4.1.2.1、RFC 7636 §4.2、OIDC Core §3.1.2.1/§3.1.2.6/§3.1.2.7 |
| `iam/web/ReauthAwareAuthenticationSuccessHandler.java`（新增） | 强制重认证时通过短期 HttpOnly Cookie 保存去掉 prompt/max_age 的授权 URL，登录后同源校验并回跳 | OIDC Core §3.1.2.1/§3.1.2.6 |
| `iam/web/EndpointParameterConformanceFilter.java`（新增） | token/introspection/revocation 的重复参数统一 400 invalid_request；token 端点无任何客户端认证时 400 invalid_client（修复 SAS 对重复参数返回 500、以及 authorization_code 无客户端时落到 invalid_request 的问题） | RFC 6749 §3.2/§5.2、RFC 7662 §2.1、RFC 7009 §2.1 |
| `iam/web/IdTokenIntrospectionGuard.java`（新增） | introspection 遇到同一授权中的 ID Token 时返回 `active=false` | RFC 7662 |
| `iam/config/IamServerAutoConfiguration.java` | 401 + Basic challenge 只用于 `invalid_client`（不再把 invalid_grant 误判为 401）；补 `scopes_supported`/`claims_supported`/`response_modes_supported`/JWT client auth 签名算法；注册 `post_logout_redirect_uri`；显式提供内存 `OAuth2AuthorizationService`/`OAuth2AuthorizationConsentService`；放行 `/error`（否则 400 会被错误转发成登录 302）；注册两个前置过滤器 | RFC 6749 §5.2、RFC 8414 §2、OIDC Discovery、RP-Initiated Logout |
| `biz/config/BizServerAutoConfiguration.java` | `NimbusJwtDecoder` 增加 issuer + `scope` 存在性校验，阻断 ID Token 作为 AT 使用 | RFC 6750、资源服务器安全 |
| `iam/IamServer.java`（测试夹具） | 提供 alice/carol/dave/erin/frank 确定性用户，支持登录、同意、拒绝、收窄、read-only 场景 | 测试基础设施 |
| `im/ImServer.java` + `im/ImTestController.java`（测试夹具） | 启用方法安全并暴露 `/api/me`、`/api/read`，验证 JWT 与 `SCOPE_read` | 测试基础设施 |
| `test/support/IamTestSession.java`（新增） | Cookie/CSRF/登录/同意/授权码/token/refresh 会话夹具 | 测试基础设施 |
| `test/OidcIT.java` | 10 → 93 个严格用例 | 全部规范 |

---

## 4. 剩余问题与建议

### 4.1 高：Access Token 的受众/类型（建议 ADR）

**现状**：

- Access Token 的 `aud` 是 `default-client`（SAS 默认），不是资源服务器标识；JWT 头没有 `typ=at+jwt`；
- IM 资源服务器目前通过“必须有 `scope` claim”阻断 ID Token，但不校验 `aud`；任何由同一 issuer 签发、带 `scope` 的 token 都会被接受（包括为其他 client 签发的 AT）；
- 这不是 OIDC Core 的 MUST，但属于 RFC 9068 级别的令牌边界问题。若后续有多个资源服务器/多 client，风险会放大。

**建议**：走 ADR 引入 RFC 9068：

- AS 侧：AT 设置 `typ=at+jwt`，`aud` 指向资源服务器标识（或同时包含 client 与 resource）；
- RS 侧：`JwtDecoder` 校验 `typ`、`aud`，不再用 `scope` 存在性替代类型校验；
- 需要兼容性评估（现有 client 依赖 `aud=client` 的行为）。

### 4.2 中：refresh 后旧 Access Token 在 UserInfo/Introspection 立即失效

SAS 在 refresh 时替换 `OAuth2Authorization` 中的 access token；旧 AT 仍是未过期的 JWT，资源服务器会继续接受，但 userinfo/introspection 因查不到授权而拒绝。RFC 6749 §6 没有要求撤销旧 AT，因此不构成 MUST 违规，但会造成“同一 token 在 RS 可用、在 userinfo 不可用”的不一致。

**建议**：明确产品语义（刷新即撤销旧 AT），或在自定义 `OAuth2AuthorizationService` 中保留历史 token；在文档中标注。

### 4.3 中：撤销不传播到资源服务器

`/oauth2/revoke` 只影响 AS 侧存储；IM 以无状态 JWT 校验，撤销后的 AT 在 `exp` 前仍可用。RFC 7009 只定义 AS 行为，不要求 RS 实时感知。

**建议**：若业务要求“撤销立即生效”，RS 需接入 introspection 或本地 denylist；否则在安全文档中明确“撤销延迟至 exp”。

### 4.4 低：OIDC 的 SHOULD 项

- `prompt=consent`：未强制重新展示同意页（OIDC Core §3.1.2.6 SHOULD）；
- `prompt=select_account`：未实现账户选择（SHOULD）；
- 无 `id_token_hint` 的 logout：返回 400，而 RP-Initiated Logout §2 建议向用户确认（SHOULD）；
- `insufficient_scope` 403 未带 `WWW-Authenticate`（RFC 6750 §3 SHOULD）；
- 畸形 Basic 头返回 400 `invalid_request`（SAS 行为；可争论为 `invalid_client` 401，但不是明确的 MUST 违反）。

### 4.5 低/中：元数据静态化

`scopes_supported`、`claims_supported` 目前按 `default-client` 硬编码。若后续引入动态 client/scope，需要从 `RegisteredClientRepository` 聚合，否则元数据会再次不完整。

### 4.6 生产就绪项（非 RFC）

- `IamServerAutoConfiguration` 内置 demo `default-client`（`{noop}secret`、硬编码 127.0.0.1 redirect、consent/PKCE 固定）；
- `InMemoryRegisteredClientRepository`、`InMemoryOAuth2AuthorizationService`、`InMemoryOAuth2AuthorizationConsentService`：重启即丢失授权/同意；
- JWK 每次启动重新生成，重启后旧 token 全部失效，无轮换/持久化；
- starter 不提供 `UserDetailsService`，测试宿主才有用户；生产需要真实用户存储。

这些不影响本次 RFC 测试结论，但属于上线前必须解决的生产化项。

---

## 5. 未覆盖项

以下项目未纳入本次 93 个用例，建议按需补充测试夹具：

- 过期 Access/ID/Refresh Token（当前 TTL 30 分钟/7 天，需要可配置短 TTL 的测试 client）；
- `client_secret_post` 成功路径、`private_key_jwt`、`tls_client_auth`（需要额外注册 client/JWK/证书）；
- PAR（`request_uri`）、DPoP、TLS-bound access token；
- `response_type=code` 省略 `redirect_uri`（RFC 6749 §3.1.2.3 的 MAY）；
- `at_hash`（授权码流程可选，当前 SAS 不签发）；
- 动态 client registration、`claims` 请求参数、`acr_values`/`login_hint` 的完整语义；
- 多 client 下的 code/refresh token 客户端绑定、introspection/revocation 跨 client 限制。

---

## 6. 验证

已执行：

```bash
cd /home/wxy/idp/bf/seedj1
mvn -o -pl starter verify
```

结果：

```
Tests run: 93, Failures: 0, Errors: 0, Skipped: 0  -- OidcIT
Tests run:  1, Failures: 0, Errors: 0, Skipped: 0  -- SeedjIT
Tests run: 94, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

报告路径：`starter/target/failsafe-reports/cn.byteforce.seedj.it.OidcIT.txt`。

---

## 7. 变更文件

```
starter/src/main/java/cn/byteforce/seedj/iam/config/IamServerAutoConfiguration.java     (修改)
starter/src/main/java/cn/byteforce/seedj/iam/web/AuthorizationRequestConformanceFilter.java   (新增)
starter/src/main/java/cn/byteforce/seedj/iam/web/EndpointParameterConformanceFilter.java      (新增)
starter/src/main/java/cn/byteforce/seedj/iam/web/IdTokenIntrospectionGuard.java               (新增)
starter/src/main/java/cn/byteforce/seedj/iam/web/ReauthAwareAuthenticationSuccessHandler.java (新增)
starter/src/main/java/cn/byteforce/seedj/biz/config/BizServerAutoConfiguration.java            (修改)
starter/src/test/java/cn/byteforce/seedj/iam/IamServer.java                                  (测试夹具)
starter/src/test/java/cn/byteforce/seedj/im/ImServer.java                                    (测试夹具)
starter/src/test/java/cn/byteforce/seedj/im/ImTestController.java                            (新增)
starter/src/test/java/cn/byteforce/seedj/test/support/IamTestSession.java                    (新增)
starter/src/test/java/cn/byteforce/seedj/test/OidcIT.java                                    (重写)
docs/assessment/2026-11-03-iam-oidc-rfc-conformance.md                                       (新增)
```

---

## 8. 建议的后续 ADR

1. **ADR：Access Token 类型与受众（RFC 9068）** —— `typ=at+jwt`、`aud=resource`、RS 校验规则、兼容性/迁移。
2. **ADR：授权/同意/JWK 持久化与轮换** —— 从内存迁移到 JDBC，JWK 多代密钥与轮换策略，重启兼容。
3. **ADR：撤销传播模型** —— AS-only 撤销 vs RS introspection/denylist，明确安全语义与延迟。
4. **ADR：生产 client 与用户存储** —— 移除 starter 内置 demo client，改为配置/JDBC 驱动，补用户存储 SPI。
