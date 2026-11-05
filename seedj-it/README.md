# starter

seedj 的聚合入口：业务应用只依赖本模块，所有 Spring 装配集中在这里。

## 入口注解

| 注解 | 用途 |
| --- | --- |
| `@EnableBizServer` | 业务资源服务器（JWT / scope / CORS / 无状态会话） |
| `@EnableIamServer` | 内嵌授权服务器（OIDC / mTLS client auth / introspection 防护） |

## 自动配置

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

- `cn.byteforce.seedj.core.InfrastructureAutoConfiguration`
- `cn.byteforce.seedj.eventbus.wiring.EventBusAutoConfiguration`

### 基础设施启动期保证

`InfrastructureInitializer` 在启动期按 order 执行所有 `InfrastructureCapability`：

1. `postgres-outbox`（domain-jdbc-postgresql）：执行版本化迁移，保证
   `outbox` / `outbox_audit` / `seedj_schema_history` 存在；
2. `eventbus-topics`（starter）：保证配置与订阅声明的队列 topic 存在。

required 能力失败阻断启动；optional 只 WARN。迁移不引入 Flyway，使用
version + checksum + history + advisory lock。

### EventBus

- `EventPublisher`：业务发布入口；有 outbox 时写 outbox，否则直接写队列；
- `EventBusOutboxSink`：outbox → 队列的投递适配器；relay / 存储 / 租约归 `seedj-outbox-starter`；
- `EventConsumerContainer`：发现 `EventSubscriber` bean，按消费者组轮询、反序列化、ack/retry/DLQ；
- 队列实现由 `domain-infra-memory` 提供（file / memory），outbox 由 `seedj-outbox-starter` + `domain-jdbc-postgresql` 提供；
- 两者都缺失时 `EventPublisher` 仍可注入，`publish` 抛 `NO_TRANSPORT`，启动不受影响。

消费示例：

```java
@Component
public final class OrderCancelledSubscriber implements EventSubscriber<OrderCancelled> {
    public EventSubscription subscription() {
        return EventSubscription.of("billing", "order.cancelled");
    }
    public void onEvent(EventEnvelope<OrderCancelled> envelope) {
        billingService.onCancelled(envelope.event(), envelope.eventId());
    }
}
```

## 配置

```yaml
seedj:
  eventbus:
    enabled: true
    topics: []                # 额外确保存在的 topic
    consumer: { enabled: true, poll-interval: 200ms, batch-size: 100, lease: 30s, max-attempts: 5 }
  outbox:
    enabled: true
    provider: postgres
    relay: { enabled: true, poll-interval: 1s, batch-size: 100, lease: 30s }
    store: { append-max-attempts: 3 }
```

## 测试

```bash
mvn -o -pl starter -am test
mvn -o -pl starter -am verify -Dit.test='EventBusIT,EventConsumeIT'
```
