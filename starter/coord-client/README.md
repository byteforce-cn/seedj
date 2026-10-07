# starter-coord-client

`starter-coord` 一族的消费面模块（Phase 3，seedj ADR-0001）：**客户端负载均衡**与
**声明式 HTTP 客户端**（框架内置 `HttpServiceProxyFactory` + `@HttpExchange`），
以及可选的 coord 熔断状态观察桥。装配前置：容器中存在 `CoordDiscoveryClient`
（由 `starter-coord-registry` 提供）。

## 快速开始

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-coord-client</artifactId>
</dependency>
```

```java
@HttpExchange
public interface OrderClient {          // host = 服务名（由 LB 改写为实例地址）

    @GetExchange("http://orders-service/api/orders/{id}")
    Order get(@PathVariable String id);
}

// 声明式代理：
OrderClient client = clientFactory.restClient(OrderClient.class);
// 响应式（classpath 存在 webflux 时）：
Mono<Order> mono = reactiveFactory.webClient(OrderClient.class).get("1");
```

`http://orders-service/...` 中 host 为**服务名**（不含 `.`、非 `localhost`/IP 时）；
命中不到实例抛 `CoordNoAvailableInstanceException`（不静默直连）。真实主机名
（含 `.`）直通，不经发现。

## 配置属性（前缀 `byteforce.coord.client`）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 模块总开关 |
| `load-balancer.strategy` | `round-robin` | `round-robin` / `random`（weight 预留不参与） |
| `circuit-breaker-report.enabled` | `false` | 熔断状态观察桥：调用成败上报 coord（Agent 本地态） |

发现缓存与陈旧窗口（C6）由 `starter-coord-registry` 的
`byteforce.coord.registry.discovery.*` 统一配置。

## 暴露的 Bean

| Bean | 类型 | 用途 |
| --- | --- | --- |
| `roundRobinCoordLoadBalancer` / `randomCoordLoadBalancer` | `CoordLoadBalancer` | 实例选择（缓存来自发现消费） |
| `coordHttpServiceClientFactory` | `CoordHttpServiceClientFactory` | RestClient 形态声明式代理 |
| `coordReactiveHttpServiceClientFactory` | `CoordReactiveHttpServiceClientFactory` | WebClient 形态（条件启用） |

## 语义边界（C6）

- **不重造声明式客户端**：`@HttpExchange` 接口由框架 `HttpServiceProxyFactory` 生成代理，
  本模块只提供 LB 拦截（`ClientHttpRequestInterceptor` / `ExchangeFilterFunction`）。
- **客户端弹性不在本模块**：超时 / 重试 / 降级由应用侧组合（如 Resilience4j），
  factory 的 `customizer` 参数即组合点；coord 熔断器仅作**状态协调与观测**
  （transport 级成败上报，可选开关）。
- 观察桥语义：收到响应 = success；IO / 运行时错误 = failure；**选择 / 改写失败不算调用
  成败**（不上报）。

## 测试

单元测试（`mvn verify`）：23 例——LB 策略（轮询顺序 / 随机 / 空集 fail loud / 过滤）、
服务名口径与 URI 改写、RestClient 工厂端到端（本机真实 HTTP 服务）、reactive 过滤器、
自动配置装配。集成验证见 [`starter-coord-it`](../coord-it/README.md)
（真实注册双实例 → 声明式调用分布到两端）。
