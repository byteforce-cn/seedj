# domain-infra-memory

本地基础设施实现：不依赖外部中间件，供开发、测试与单机部署使用。

## 提供的能力

| 能力 | 类型 | 说明 |
| --- | --- | --- |
| PKI | `BcPkiProvider` | BouncyCastle + 状态文件（签发、吊销、签名身份） |
| Transit | `BcTransitProvider` | JCE + 状态文件（密钥与版本历史） |
| EventBus 队列 | `FileEventBusTransport` | 磁盘队列，同机多服务共享目录；支持消费者组 |
| EventBus 队列 | `MemoryEventBusTransport` | 进程内队列，单进程测试/演示；支持消费者组 |
| ID | `SnowflakeIdGenerator` | 雪花（内部 long）+ `StringIdGenerator`；节点编号从共享文件领取 |

## EventBus 消费者组（file）

```text
<base-dir>/<topic>/<epoch>-<eventId>.payload
<base-dir>/<topic>/<epoch>-<eventId>.meta
<base-dir>/<topic>/.groups/<group>/acks/<base>.ack
<base-dir>/<topic>/.groups/<group>/claims/<base>.<deliveryId>.<untilMillis>.<attempts>
<base-dir>/<topic>/.groups/<group>/dlq/<base>.<deliveryId>.meta
```

- 同一 group 内用 `CREATE_NEW` 原子创建 claim 文件，**跨进程/同 JVM 多实例只有一个能抢到**；
- 租约到期后其他实例可接管，`attempts` 递增；
- 不同 group 的 ack/claim/dlq 完全隔离，各收到一份；
- ack 删除 claim 并写 ack 标记；retry 先写延迟 claim 再删旧 claim；dead-letter 复制到 group 的 `dlq/`；
- 消息文件不自动清理（本地开发/测试用途）。生产多实例/长期保留请使用真正的 MQ 适配器。

`MemoryEventBusTransport` 的 group 状态在进程内，不能跨进程；它的价值是单进程测试与演示。

## 配置

```yaml
seedj:
  eventbus:
    transport: file          # file | memory
    file:
      base-dir: ${java.io.tmpdir}/seedj-eventbus
    memory:
      capacity: 10000
```

## 测试

```bash
mvn -o -pl domain-infra-memory -am test
```

契约测试：`EventBusTransportContractTest`、`EventConsumerStoreContractTest`；
同机多实例：`FileEventConsumerGroupTest`。
