# ADR-0003：IAM 生产化架构（OIDC / SCIM / RBAC / ABAC / TOTP / native-auth）

- 状态：accepted
- 日期：2026-11-04
- 决策者：seedj IAM 维护者
- 关联：`docs/assessment/2026-11-04-iam-full-capability-readiness.md`、`docs/spi/README.md`

## 1. 背景

现有 OIDC 基线通过 SAS 7.1.1 提供了完整的授权码/PKCE/refresh/mTLS/introspection/revocation，
但用户、client、authorization、consent 全在内存，默认 client 是 `{noop}` demo，JWK 强依赖
`PkiProvider`，因此“一个依赖 + 一个注解”只能启动演示，不能作为 IAM 使用。

## 2. 决策

1. **分层**：IAM 契约放 `core`（零 Spring）；memory/JDBC 实现分别放
   `domain-infra-memory` 与 `domain-jdbc-postgresql`；Spring 装配集中在新的
   `seedj-iam-starter`；聚合 `starter` 保留 Biz/EventBus 并依赖 IAM starter。
2. **注解只做开关**：`@EnableIamServer`/`@EnableBizServer` 只注册 marker/导入配置，
   不再带 `@SpringBootApplication`。自动配置通过 `AutoConfiguration.imports` 生效，
   并且只有 marker 存在时才激活。
3. **存储**：`seedj.iam.store=memory|jdbc`。JDBC 是生产默认路径；两者实现同一组
   `Iam*Repository` 契约测试。`store=jdbc` 无 `DataSource` 时快速失败。
4. **用户存储**：SCIM 与 OIDC 共用 `iam_user`/`iam_user_credential`，不建第二套用户表；
   `PasswordEncoder` 使用 `DelegatingPasswordEncoder`，新密码写 BCrypt。
5. **OIDC token 边界**：Access Token 使用 RFC 9068 `typ=at+jwt`、非空 `aud`；ID Token
   保持 `typ=JWT`。资源服务器用 `IamJwtValidators` 同时校验 `at+jwt`/issuer/时间/aud，
   因此 ID Token 不能当 Access Token 使用。`aud` 默认仍是 client id；有多资源服务器时
   通过 `seedj.iam.token.resource-audience` 收窄。
6. **JWK**：默认 JDBC `iam_jwk`，私钥经 `IamSecretProtector`（Transit 或 AES-GCM）加密；
    retired key 只保留公钥。PKI 通过 `seedj.iam.jwk.provider=pki` 作为可选适配器，
   IAM 启动不再强依赖 PKI。
7. **RBAC**：角色/权限/组/继承分别建表；有效权限 = 直接角色 ∪ 组角色 ∪ 祖先角色。
   `iam_authz_epoch` 作为跨实例缓存版本；token 默认带 `roles`，`permissions` 可开关。
8. **ABAC**：内置类型化 AST（eq/ne/in/contains/startsWith/endsWith/gt/lt/ge/le/and/or/not/exists），
   资源属性通过 `IamResourceAttributeResolver` 注入；DENY 优先、默认拒绝、短 TTL 缓存。
   不引入通用脚本语言；需要更强表达时替换 `IamPolicyDecisionPoint`（OPA/Cedar adapter）。
9. **TOTP**：RFC 6238/4648 自己实现；secret 只以密文存 `iam_totp`；`last_used_step` 用
   原子 UPDATE 防重放；恢复码只存哈希；浏览器 MFA 使用 pending authentication + session
   升级，native-auth 使用一次性 `mfa_token` challenge。
10. **native-auth**：非标准 grant，默认关闭，仅第一方 App；必须 TLS、限流/锁定、短 AT、
    refresh 轮换、设备绑定、审计。第三方/普通移动端仍推荐系统浏览器 + 授权码 + PKCE
    (RFC 8252)。默认不把 client secret 放进 App。
11. **SCIM**：只做核心 User/Group（EnterpriseUser/bulk 后置）；filter/PATCH 先解析成 AST，
    再用白名单列 + 参数化 SQL 翻译，绝不拼接用户输入；Group 是 canonical 组，角色分配
    单独放 `iam_group_role`。
12. **迁移**：`JdbcMigrationRunner` 改为 `migrate(scope, migrations)` /
    `validate(scope, migrations)`，一个 runner bean 服务 `iam`、`oauth2`、`outbox`
    多个 scope；每个 scope 独立版本与 checksum。

## 3. 安全边界

- demo client 默认关闭；`seedj.iam.demo.enabled=true` 才注册 `default-client`/`tls-client`，
  且 demo secret 使用 BCrypt 而不是 `{noop}`。
- 未知用户与错误密码走同一次 dummy hash，返回同一个 `invalid_grant`。
- 用户禁用/改密时删除 `oauth2_authorization`（JDBC）或隐藏授权（memory），refresh 立即失效。
- TOTP/恢复码/密码/secret 不写日志；审计只记录结果与原因。
- SCIM 过滤器与 PATCH path 不接受任意列名/表达式。

## 4. 兼容性

`1.0.0` 未发布。`starter` 保留 Biz/EventBus 与 IAM 聚合；新应用可直接只依赖
`seedj-iam-starter`。已有应用仍可继续使用 `starter` + `@EnableIamServer`，但必须自行提供
`@SpringBootApplication`（注解不再隐式提供）。

## 5. 验证

- `OidcIT` 100 个 RFC/OIDC 用例保持全绿。
- 新增：TOTP RFC 6238 向量、SCIM filter/PATCH、ABAC DENY 优先、memory/JDBC 契约、
  JDBC 持久化重启、native-auth 密码 + MFA + refresh。
- 形状检查：注解无 `@SpringBootApplication`、starter main 无 `{noop}`/`InMemory*`、
  `JdbcMigrationRunner` 无 scope 构造调用、无 `ServiceLoader`。
