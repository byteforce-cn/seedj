# ADR-0007：业务 starter 独立与安全契约下沉

- 状态：accepted
- 日期：2026-11-06
- 决策者：seedj 维护者
- 关联：ADR-0002（基础设施 SPI 统一）、ADR-0003（IAM 生产化）、`docs/spi/README.md`、`docs/iam/README.md`

## 1. 背景

`starter` 是所有宿主应用的唯一聚合入口，因此**任何业务服务只要依赖它，就会把整个
OIDC 授权服务器打进 classpath**：

- `starter/pom.xml` 依赖 `seedj-iam-starter`（SAS + SCIM + TOTP + JWK + BouncyCastle）；
- `starter/.../biz/config/BizServerAutoConfiguration.java` 直接
  `import cn.byteforce.seedj.starter.iam.security.IamJwtValidators`；
- 业务代码要记录一次 403，只能 `import cn.byteforce.seedj.iam.spi.IamAuditSink`。

同时存在两个实测缺陷：

1. **业务链在聚合宿主里是死链**。`IamServerAutoConfiguration#defaultSecurityFilterChain`
   是无 `securityMatcher` 的 catch-all 且 `@Order(5)`，而 `bizSecurityFilterChain`
   没有 `@Order`（= `LOWEST_PRECEDENCE`）。Spring Security 按 order 取第一条匹配的链，
   所以 `/api/**` 永远落到 IAM 的表单登录链，返回 302 → `/login` 而不是 401 JSON。
   因为集成测试里 IAM 与 biz 是两个独立进程，这个问题一直没被覆盖。
2. **安全审计有三个通道**。`core/audit`（哈希链，已受理的变更）、
   `core/iam/IamAuditEvent`（安全审计，`@Audited` 的 javadoc 已指定为拒绝通道），
   设计草案还在提第三个 `SecurityAuditor`。

## 2. 决策

### 2.1 `core.security`：biz 与 iam 共用的安全契约（零 Spring）

| 类型 | 作用 |
|---|---|
| `Caller` | 调用方身份快照（record）。业务代码唯一需要认识的安全类型，测试直接 `new` |
| `CallerProvider` | 单方法接口，未认证返回 `Optional.empty()` |
| `SecurityErrorCode` | `implements ErrorDescriptor`，四类失败：REQUIRED / FAILED / EXPIRED / DENIED |
| `SecurityAuditEvent` / `SecurityAuditSink` | 从 `core/iam/IamAuditEvent` / `spi/IamAuditSink` 机械迁移 |
| `TokenClaims` / `AccessTokenRules` | 与框架无关的令牌声明视图 + 唯一一份 RFC 9068 判定 |

**不做**：`Authentication` / `Principal` / `Credential` / `Authenticator` /
`SecurityContext` / 方法级安全注解 / 切面 / `PasswordEncoder` / `TokenGenerator` /
`Encryptor`。这些能力仓库里已经有了（Spring Security、SAS、`TransitProvider`、
`IamPolicyDecisionPoint`），再造一份只会多一条 seam。

`core/security` 的公开类型由 `SpiSurfaceTest` 白名单守卫（7 项）。

### 2.2 `seedj-biz-starter`：业务服务的唯一入口

```
seedj-biz-starter
├── biz/annotation/EnableBizServer      marker 模式（同 @EnableIamServer）
├── biz/autoconfigure/                  BizEnabledMarker + BizEnablementConfiguration
├── biz/security/                       资源服务器 + Caller + 401/403 JSON + 拒绝审计
├── biz/infrastructure/                 InfrastructureInitializer（原 core 包，修 split package）
├── audit/wiring · cqrs/wiring · eventbus/wiring · observability/wiring
└── src/test/…/demo/hr                  demo 从 src/main 移出
```

**硬约束：不依赖 `seedj-iam-starter`**，由 `BizStarterPackageIndependenceTest`
（main sources 不得 import `cn.byteforce.seedj.iam`）门禁。

### 2.3 `starter` 保留为集成服务聚合

`starter` 的定位不变：**IT 集成服务**（同时需要 IdP 与业务能力）的聚合载体。
它依赖 `seedj-biz-starter` + `seedj-iam-starter` + outbox + 存储实现，
`src/main` 不再有代码，只保留集成测试。

三档受众：

| 服务类型 | 依赖 | 不携带 |
|---|---|---|
| 业务服务 | `seedj-biz-starter` | SAS / SCIM / TOTP / JWK / BCrypt |
| 身份服务 | `seedj-iam-starter` | 业务横截面 |
| IT 集成服务 | `starter` | — |

依赖方向：`core` ← `{seedj-biz-starter, seedj-iam-starter}` ← `starter`。
两个 starter 之间零依赖。

### 2.4 过滤器链顺序规则

> **只有最后一条链可以不写 `securityMatcher`，且它必须是 `LOWEST_PRECEDENCE`。**

现行顺序：

| order | 链 | matcher |
|---|---|---|
| 1 | `authorizationServerSecurityFilterChain` | SAS endpoints |
| 2 | `scimSecurityFilterChain` | `/scim/v2/**` |
| 3 | `iamSecurityFilterChain` | `/iam/**` |
| 4 | `iamActuatorSecurityFilterChain` | actuator endpoints |
| 6 | `bizSecurityFilterChain` | 无（catch-all） |
| LAST | `defaultSecurityFilterChain` | `/login`、`/logout`、`/mfa`、`/error` |

`BizIamChainOrderTest` 断言 biz 链序为 6 且早于 IAM 浏览器链。

### 2.5 令牌规则只有一份

`AccessTokenRules` 承载 RFC 9068 判定（`typ=at+jwt`、issuer、时钟偏移、非空 `aud`）
与 authority 前缀约定（`SCOPE_` / `ROLE_` / `PERM_` / `GROUP_`，此前散落在 3 处）。
两侧各写一个 10 行映射适配器：

- iam：`IamJwtValidators`（Spring `OAuth2TokenValidator`）；
- biz：`JwtClaimsMapper` + `JwtDecoder` 的 validator 列表。

`AccessTokenRulesTest` 用 RFC 9068 测试向量同时门禁两侧语义。

### 2.6 拒绝审计在执行点产生

401/403 由 `JsonAuthenticationEntryPoint` / `JsonAccessDeniedHandler` 写入
`SecurityAuditSink`（`seedj.biz.audit-denials=false` 可关）。业务代码不参与，
审计失败只落 WARN，绝不改变响应。

### 2.7 异步传播边界

`CallerPropagation` 只在**进程内**传播（`@Async` 的 `TaskDecorator`）。刻意**不**把
`Caller` 写进 `EventMessage.headers`：headers 会落库且对所有消费者可见，
租户/用户在 headers 里属于数据泄漏（与 `TracePropagation` 的同一条约定一致）。

## 3. 兼容性

`1.0.0` 未发布，无已发布 API 基线，以下为机械替换：

| 变更 | 影响 |
|---|---|
| `cn.byteforce.seedj.iam.IamAuditEvent` → `cn.byteforce.seedj.core.security.SecurityAuditEvent` | import 替换 |
| `cn.byteforce.seedj.iam.spi.IamAuditSink` → `cn.byteforce.seedj.core.security.SecurityAuditSink` | import 替换 |
| `cn.byteforce.seedj.core.InfrastructureAutoConfiguration` → `cn.byteforce.seedj.biz.infrastructure.*` | 修正 split package |
| `cn.byteforce.seedj.biz.config.BizServerAutoConfiguration` 删除 | 由 `BizSecurityAutoConfiguration` 取代 |
| `@EnableBizServer` 改为 marker 模式 | 与应用类上的 `@SpringBootApplication` 解耦 |
| 业务服务依赖 `starter` → 改依赖 `seedj-biz-starter` | 可选；`starter` 仍可用 |

## 4. 验证

| 变更 | 验证 |
|---|---|
| 令牌规则 | `AccessTokenRulesTest`（RFC 9068 向量、时钟偏移、scope 空格分隔、user_id 回退） |
| 身份契约 | `CallerTest`、`JwtCallerProviderTest`（JWT / 非 JWT / 匿名 / 作用域嵌套） |
| 401/403 形状 | `JsonSecurityResponseTest`（状态码、`WWW-Authenticate`、JSON 形状、拒绝审计） |
| 装配 | `BizSecurityAutoConfigurationTest`（开关、bean 形状、链序 6、public-paths） |
| 链路顺序 | `BizIamChainOrderTest`（biz 6 < iam `LOWEST_PRECEDENCE`） |
| 模块边界 | `BizStarterPackageIndependenceTest`、`SpiSurfaceTest`（`core.security` 7 项） |
| 行为等价 | `starter` 的 110 个集成测试（含 `OidcIT` 100 例）+ 全仓 `mvn -o test` |

命令：

```bash
mvn -o -pl starter verify
```

## 5. 非目标

- 不自建方法级安全（`@PreAuthorize` + `@iam.authorize` 已可用）。
- 不引入第二套 `SecurityContext`；`CallerProvider` 是 Spring 上下文的投影。
- 不做服务间调用凭据（client_credentials / mTLS）——等第一个真实消费者。
- 不把资源级 ABAC 下沉到 biz：需要它的服务显式接入 `IamPolicyDecisionPoint`。
