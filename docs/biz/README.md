# Biz Starter

业务服务开箱即用：资源服务器安全、命令模板、审计、事件总线、可观测性。
**不包含** OIDC 授权服务器——需要签发令牌请用 [`seedj-iam-starter`](../iam/README.md)。
业务服务的可运行宿主见 `seedj-server/hr`、`seedj-server/im`；两者聚合只发生在 `seedj-it` 的集成测试里。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>seedj-biz-starter</artifactId>
</dependency>
```

## 启动

```java
@SpringBootApplication
@EnableBizServer                     // 纯功能开关，不提供 @SpringBootApplication 语义
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
```

```yaml
seedj:
  biz:
    enabled: true
    issuer-uri: https://iam.example.com        # 也可用 spring.security.oauth2.resourceserver.jwt.issuer-uri
    audience: order-api                        # 可选；设置后校验 aud 包含该值
    public-paths: [/actuator/health, /error, /api/public/**]
    tenant-id: default
    audit-denials: true                        # 401/403 写安全审计
  audit:
    enabled: true                              # 需要 AuditStore + 事务管理器
```

## 调用方身份

业务代码只认识 `Caller`，不 import 任何 Spring Security 类型：

```java
@Service
public class OrderService {
    private final CallerProvider callers;
    private final SecurityAuditSink audit;
    private final IdGenerator<String> ids;

    public OrderService(CallerProvider callers, SecurityAuditSink audit,
                        IdGenerator<String> ids) {
        this.callers = callers;
        this.audit = audit;
        this.ids = ids;
    }

    public void cancel(String orderId) {
        Caller caller = callers.find().orElseThrow();   // 公开端点也可能为空
        if (!caller.hasPermission("order:cancel")) {
            throw new IllegalStateException("no permission");  // 由执行点转 403
        }
        audit.record(SecurityAuditEvent.of(ids.id(), caller,
                "order.cancel", "order", orderId, SecurityAuditEvent.OUTCOME_SUCCESS, ""));
    }
}
```

测试不需要 Spring：

```java
Caller alice = new Caller("u-1", "alice", "default",
        Set.of("ADMIN"), Set.of("order:cancel"), Set.of(), Set.of("pwd"),
        Caller.DEFAULT_ACR, Map.of());
CallerProvider provider = () -> Optional.of(alice);
OrderService service = new OrderService(provider);
```

## 错误响应

401 与 403 使用同一形状，前端只写一套解析：

```json
{ "error": "ACCESS_DENIED", "message": "access denied" }
```

`error` 取值来自 `SecurityErrorCode`：`AUTHENTICATION_REQUIRED` /
`AUTHENTICATION_FAILED` / `TOKEN_EXPIRED` / `ACCESS_DENIED`。
401 保留 RFC 6750 的 `WWW-Authenticate: Bearer`（无效令牌带 `error="invalid_token"`）。

## 过滤器链顺序

只有最后一条链可以不写 `securityMatcher`，且必须是 `LOWEST_PRECEDENCE`；
业务链固定 `@Order(6)`。详见 [ADR-0007](../adr/ADR-0007-biz-starter-and-security-contract.md)。

## 资源级授权（可选）

令牌里的 `roles` / `permissions` 不够用时，接入 IAM 的策略决策点：

```java
@PreAuthorize("@iam.authorize(authentication, 'order', #id, 'cancel')")
public void cancel(String id) { ... }
```

这需要显式依赖 `seedj-iam-starter`（或自行适配 `IamPolicyDecisionPoint`）。

## 与身份服务 / 集成测试的区别

| 依赖 | 携带 | 适用 |
|---|---|---|
| `seedj-biz-starter` | 资源服务器 + cqrs + 审计 + eventbus | 业务服务（`seedj-server-hr` / `seedj-server-im`） |
| `seedj-iam-starter` | OIDC / SCIM / RBAC / ABAC / MFA | 身份服务（`seedj-server-iam`） |
| `seedj-it` | 三个 server（test scope）+ 夹具 | 集成测试，不进入生产 classpath |
