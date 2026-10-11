# starter-coord

`coord-java-sdk` 的 Spring Boot 消费侧接线：业务服务引入本模块后，直接获得可注入的
`CoordClient` 及全部子客户端 Bean，无需手工拼装 `CoordConfig` 与生命周期。

## 定位与上游边界

- coord 上游明确**不提供官方 Spring Boot starter**（产品决策，见 coord `README.zh-CN.md`
  与 `docs/production/ops/boundaries.md` B-SE-3）。官方 recipe 只有一条：
  `@Bean(destroyMethod = "close")` + `CoordClient.create(CoordConfig)`，其中 `close()`
  必须被调用（注销注册、取消 Watch / MQ 订阅、关闭连接与线程池）。
- 本模块把这套 recipe 在 seedj 侧**一次固化**，供所有业务服务复用：
  属性外部化 → 生命周期托管 → 子客户端 Bean 注入 → 可选健康检查 / 启动协商。
  SDK 仍是唯一集成面，本模块不引入任何新的协议或缓存实现。

### 版本与坐标约定

| 项 | 值 | 说明 |
| --- | --- | --- |
| SDK | `cn.byteforce:coord-java-sdk:0.3.0` | 由 `bom` 统一管理 |
| 平台 | Spring Boot 4.1.1 | 由 `bom` 统一管理 |
| Java | 21 | 与 SDK 一致 |

> ⚠️ **传递依赖版本被平台 BOM 提升**：SDK 自测组合为 gRPC 1.68.0 / protobuf-java 4.28.0，
> 但在 seedj BOM（导入 Boot BOM）下实际解析为 **gRPC 1.83.1 / protobuf-java 4.35.1**
> （`mvn -pl starter/coord dependency:tree` 实证）。该组合已在 SDK 0.3.0 的官方测试范围内
> （SDK README「Also tested」列），方向受 protobuf 支持（runtime 新于
> gencode）；当前单元测试在该组合下通过。如后续遇到疑似版本相关异常，可在应用侧
> `dependencyManagement` 钉回 SDK 测试版本，并回报 coord 上游。
## 家族模块（coord-starter 一族）

本模块是家族的**接线层基座**；能力面模块独立增量引入（均依赖本模块，未引入时优雅回退）：

| 模块 | 职责 | 文档 |
| --- | --- | --- |
| `starter-coord`（本模块） | 客户端 Bean / 生命周期 / 健康检查 + 锁与限流封装（Phase 2） | 本文 |
| `starter-coord-registry` | 自动注册 / 优雅注销；发现消费（TTL 缓存降级） | [README](../coord-registry/README.md) |
| `starter-coord-config` | `spring.config.import=coord:` 导入 + 运行期刷新（rebind） | [README](../coord-config/README.md) |
| `starter-coord-client` | 客户端负载均衡 + 声明式 HTTP（`@HttpExchange`） | [README](../coord-client/README.md) |
| `starter-coord-observability` | `ObservabilityProvider` → Micrometer 指标桥 | [README](../coord-observability/README.md) |
| `starter-coord-it` | 集成套件（Testcontainers 真实环境，全部 `*IntegrationTest`） | [README](../coord-it/README.md) |

架构约束、Phase 0 决议与分期见 [seedj ADR-0001](../../docs/adr/0001-coord-starter-spring-cloud-replacement.md)
与 [ROADMAP.md](./ROADMAP.md)。
## 快速开始

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-coord</artifactId>
</dependency>
```

最小配置（默认值即可连本机 Agent；coord 的部署模型是「每台机器一个 Agent」）：

```yaml
byteforce:
  coord:
    agent-host: 127.0.0.1
    agent-port: 19527
```

按类型注入子客户端：

```java
@Service
public class OrderService {

    private final LockClient lockClient;
    private final IdGenClient idGenClient;

    public OrderService(LockClient lockClient, IdGenClient idGenClient) {
        this.lockClient = lockClient;
        this.idGenClient = idGenClient;
    }
}
```

生产建议：连接非 loopback Agent 必须启用 TLS（明文默认被拒，fail-closed）；CCT 凭据
提供 `CoordAuthTokenSupplier` Bean 以支持刷新：

```java
@Configuration(proxyBeanMethods = false)
public class CoordSupportConfiguration {

    @Bean
    CoordAuthTokenSupplier coordAuthTokenSupplier(CredentialStore store) {
        return () -> store.currentCct();   // 每次 RPC 读取当前 token，刷新无需重建 channel
    }
}
```

## 配置属性（前缀 `byteforce.coord`）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用自动配置 |
| `agent-host` | `127.0.0.1` | Agent 主机 |
| `agent-port` | `19527` | Agent gRPC 端口 |
| `request-timeout` | `5s` | 单次 RPC 超时 |
| `auto-restore-watches` | `true` | 断线后自动恢复 Watch 订阅 |
| `heartbeat-threads` | `4` | 心跳/重连调度线程数 |
| `allow-insecure-plaintext` | `false` | 允许非 loopback 明文（默认拒绝，fail-closed） |
| `auth-token` | — | 固定 CCT；仅当无 `CoordAuthTokenSupplier` Bean 时生效 |
| `await-connected-timeout` | —（惰性） | >0 时启动阶段等待协商，失败阻止启动（fail-fast） |
| `tls.enabled` | `false` | 启用 TLS |
| `tls.ca-cert-path` | — | CA 证书（启用 TLS 时必填，缺失拒绝启动） |
| `tls.client-cert-path` | — | 客户端证书（与私钥成对 = mTLS） |
| `tls.client-key-path` | — | 客户端私钥 |

配置错误（明文连非 loopback、TLS 缺证书等）在启动时直接抛错，不静默降级——这是 SDK 的
fail-closed 语义，本模块不做任何放宽。

### 锁（前缀 `byteforce.coord.lock`，约定 C3）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用锁服务封装 |
| `holder-id` | `{app}:{hostname}:{pid}` | 持有者 ID（`(holder_id, lease_id)` 凭据） |
| `ttl` | `30s` | 默认租约 TTL；看门狗续约周期 = ttl/3（下限 1s） |

```java
CoordLockLease lease = lockService.acquireOrThrow("order-42", Duration.ofSeconds(3));
try {
    if (lease.isValid()) { /* 临界区 */ }
} finally {
    lease.release();
}
```

语义：**不实现** `java.util.concurrent.locks.Lock`、**不模拟可重入**；续约失败 / 锁易主 →
标记失锁 + `CoordLockLostListener` 回调（失锁后不再发送释放 RPC，避免误删新持有者租约）。

### 限流（前缀 `byteforce.coord.ratelimiter`，约定 C4）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用限流封装（API / AOP） |
| `aop-enabled` | `true` | `@CoordRateLimit` 注解 AOP（依赖容器 AOP 代理设施） |

- **按实例（Agent 本地）语义**，不承诺集群级限流；**配额来源不在 starter 侧**
  （契约无规则下发面，配额由 Agent 配置 / 业务管理面维护）。
- `@CoordRateLimit(key = "literal" | "#param" | "#a + '-' + #b", permits)`：被拒抛
  `CoordRateLimitExceededException`（不静默）；Advisor 以 `ROLE_INFRASTRUCTURE` 注册，
  **无需 aspectjweaver**。

## 暴露的 Bean

全部 Bean 均 `@ConditionalOnMissingBean`（健康指示器为同名回退），使用方定义同类型
Bean 即可接管。

| Bean | 类型 | 用途 |
| --- | --- | --- |
| `coordClient` | `CoordClient` | 主入口；`destroyMethod=close` 托管生命周期 |
| `registry` | `Registry` | 服务注册与发现 |
| `configClient` | `ConfigClient` | 动态配置（含 Watch） |
| `lockClient` | `LockClient` | 分布式互斥锁 |
| `idGenClient` | `IdGenClient` | 分布式唯一 ID |
| `cacheClient` | `CacheClient` | 分布式缓存 |
| `mqClient` | `MqClient` | 消息队列（publish / poll + ack / DLQ） |
| `transitClient` | `TransitClient` | 信封加密/解密 |
| `workflowClient` | `WorkflowClient` | 工作流定义与实例 |
| `policyClient` | `PolicyClient` | RBAC/ABAC 策略评估 |
| `pkiClient` | `PkiClient` | 本地 PKI CA 证书 |
| `objectStoreClient` | `ObjectStoreClient` | 对象存储（EXPERIMENTAL） |
| `leaderElectionClient` | `LeaderElectionClient` | 租约式 Leader 选举 |
| `eventClient` | `EventClient` | 发布/订阅事件 |
| `schedulerClient` | `SchedulerClient` | 分布式作业调度 |
| `circuitBreakerClient` | `CircuitBreakerClient` | Agent 本地熔断状态 |
| `rateLimiterClient` | `RateLimiterClient` | Agent 本地令牌桶限流 |
| `featureFlagClient` | `FeatureFlagClient` | 功能开关（只读） |
| `coordStartupAwaiter` | `ApplicationRunner` | 可选启动协商（`await-connected-timeout` 控制） |
| `coordHealthIndicator` | `HealthIndicator` | 可选；SERVING→UP / NOT_SERVING→DOWN |
| `coordLockService` | `CoordLockService` | 锁专用 API（看门狗续约 / 失锁回调 / 等待窗口） |
| `coordRateLimiter` | `CoordRateLimiter` | 限流专用 API（按实例语义） |
| `coordRateLimitAdvisor` | `DefaultPointcutAdvisor` | `@CoordRateLimit` 注解拦截（基础设施角色） |

## 生命周期与运维语义

- **关闭顺序由 SDK 保证**：注销注册 → 取消 Watch / MQ 订阅 → 关闭 gRPC channel →
  停止线程池。务必让容器管理 `CoordClient`（本模块已用 `destroyMethod=close` 固定）。
- **连接惰性**：容器启动不要求 Agent 在线；首次 RPC（或健康探测）时才建链与协议协商。
  需要启动期 fail-fast 时设置 `await-connected-timeout`。
- **健康检查**：`healthCheck()` 会顺带完成一次协议协商（幂等）。返回 `DOWN` 表示 Agent
  不可达 / 超时 / 协议版本不匹配；可用 `management.health.coord.enabled=false` 关闭注册。
  健康指示器仅在应用引入 Actuator（classpath 存在 `spring-boot-health`）时生效。

## 集成验证（Testcontainers）

集成套件已统一迁至 [`starter-coord-it`](../coord-it/README.md)（Testcontainers 单容器
真实 coord 环境，当前 14 类 33 例）：覆盖本模块全部分布式能力装配链路，以及
registry / config / client / observability 能力面与锁 / 限流的语义护栏。

```bash
# 默认 verify 只跑单元测试（不需要 Docker）
mvn -pl starter/coord -am verify

# 只跑集成套件（需要本机 Docker + coord 镜像）
mvn -pl starter/coord-it -am verify -Pit

# 覆盖镜像（默认 ghcr.io/byteforce-cn/coord:v0.3.0）
mvn -pl starter/coord-it -am verify -Pit -Dcoord.it.image=<image>
```

容器形态、`coord dev` 差异说明与时序踩坑见 [coord-it README](../coord-it/README.md)。

## 已实现 / 未实现

**已实现（本模块）**：

- 属性外部化与 fail-closed 校验穿透；
- `CoordClient` + 全部 17 个子客户端 Bean；
- 生命周期托管（close 语义）；
- 可选健康检查（Actuator，可关闭）；
- 可选启动等待与协议协商（fail-fast）；
- 凭据扩展点（`CoordAuthTokenSupplier`）与可观测扩展点（`ObservabilityProvider` Bean 直通）；
- **Phase 2 封装**：锁专用 API（看门狗续约 / 失锁回调 / 等待窗口，不实现 `Lock`、
  不模拟可重入）与限流（专用 API + `@CoordRateLimit` 注解 AOP，按实例语义）。

**由家族模块承接（见上文家族表）**：服务自动注册（registry）、配置 Watch → Environment 桥
（config）、Micrometer 指标桥（observability）、客户端负载均衡与声明式 HTTP（client）。

**未实现（有意留空，需单独立项）**：

- **bean 级刷新**（自研 refresh scope）：v1 刷新深度止于 `@ConfigurationProperties` 级
  （R3 决议），bean 级重建另立 ADR；
- **LB 权重参与选择**：metadata `weight` 已计入注册 schema（C1）但 v1 选择策略不消费；
- **Workflow / Scheduler 注解封装**：声明式任务与工作流触发（Phase 4，按诉求）；
- **与 `starter-authorization` 的 CCT 联动**：OAuth2 凭据与 coord CCT 的统一凭据存储；
- **`starter-coord-springcloud-bridge`**：R2 决议 v1 不投放（互操作刚需出现时重新立项）。

分期路线（Phase 0 决议与约定、契约目标日期对齐、测试策略）见 [ROADMAP.md](./ROADMAP.md)。

**测试范围**：单元测试覆盖属性映射、自动配置装配/回退/关闭、健康检查与 fail-closed 语义、
锁与限流语义（不依赖真实 Agent，默认 `mvn verify` 执行）；集成套件已迁至
[`starter-coord-it`](../coord-it/README.md)（`mvn verify -Pit`，真实 coord 环境）。
