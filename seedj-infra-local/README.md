# seedj-infra-local

本地基础设施实现：不依赖外部中间件，供开发、测试与单机部署使用。

## 包结构

```text
cn.byteforce.seedj.infra.local.pki        BouncyCastle PKI（签发/吊销/状态文件）
cn.byteforce.seedj.infra.local.transit    JCE Transit（密钥/版本/状态文件）
cn.byteforce.seedj.infra.local.id         UUIDv7 / 雪花 / 字符串 ID
cn.byteforce.seedj.infra.local.eventbus   文件队列与进程内队列（消费者组）
cn.byteforce.seedj.infra.local.lock       进程内锁（默认关闭，需显式开启）
cn.byteforce.seedj.infra.local.audit      审计哈希链内存实现
cn.byteforce.seedj.infra.local.cqrs       命令记录 / 事件存储
cn.byteforce.seedj.infra.local.domain     双时间轴对象存储
cn.byteforce.seedj.infra.local.iam        IAM 存储与密钥保护
cn.byteforce.seedj.infra.local.workflow   工作流参照实现
cn.byteforce.seedj.infra.local.security   安全审计 sink
```

## 边界

- 只依赖 `core` 契约与 JDK；禁止依赖 JDBC、starter 与宿主模块。
- 进程内锁默认关闭：多实例部署下的“进程内互斥”会静默失效，必须显式
  `seedj.lock.memory.enabled=true`；生产请使用 PostgreSQL 锁或其它分布式实现。
- 文件队列适合本地多服务联调；长期保留与多机部署请使用真正的 MQ 适配器。
- `InMemoryEventStore` 只用于本地与测试；生产事件流请使用
  `seedj-jdbc-postgresql` 的 `PostgresEventStore`（重启可重放、并发有裁决）。

## 测试

```bash
./mvnw -o -B -pl seedj-infra-local -am test
```

契约测试复用 `core` test-jar：`PkiProviderContractTest`、`TransitProviderContractTest`、
`LockProviderContractTest`、`WorkflowProviderContractTest`、`EventBusTransportContractTest`。
