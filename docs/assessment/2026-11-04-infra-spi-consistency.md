# 基础设施 SPI 一致性评估与整改建议（pki / transit / eventbus）

- 日期：2026-11-04
- 范围：`core` 的 `cn.byteforce.seedj.{pki,transit.spi,eventbus.spi}`、`domain-infra-memory`、`domain-jdbc-postgresql`、`starter`（含 `ClusterExtension` / `SeedjIT`）
- 方法：代码审查 + 依赖路径追踪 + `ApplicationContextRunner` 复现（见 §3.1）+ 现有测试清单比对

- **落地状态：已全部实施**（2026-11-04）。决策见 `docs/adr/ADR-0002-infra-spi-constitution.md`，
  规则与配置速查见 `docs/spi/README.md`。下文保留评估时的原始事实，用作回归依据。

## 落地结果（2026-11-04）

| 项 | 状态 | 证据 |
|---|---|---|
| §3.1 单模块启动失败 | 已修 | `OutboxAutoConfiguration` 按 `DataSource`/`JdbcTemplate` 条件装配；relay 无 store 时空转 |
| §3.2 relay 永不执行 | 已修 | `OutboxRelay` 自持调度线程；`OutboxRelayTest.schedulesItself` |
| §3.3 `Heartbeat.lost` 快照 | 已修 | 改为 `AtomicBoolean`；`OutboxRelayTest.leaseLostSkipsMarkSent` |
| §3.4 无 DDL | 已修 | 版本化 `Migration` + `seedj_schema_history` + `InfrastructureInitializer`（不再用裸 SQL 启动脚本） |
| §3.4 追加：claim SQL 在真实 PG 上报错 | 已修 | 原 `UNION ALL + FOR UPDATE` 非法；改为"先回收过期租约，再单子查询 claim"；`PostgresOutboxStoreContractTest`（11 用例） |
| §3.5 两个 `DomainEvent` | 已修 | 删除 `Models.DomainEvent`；`OutboxMessage.from(DomainEvent)` 走 `EventTypeRegistry` |
| §3.6 死代码 | 已清理/已补完 | 删除 `SeedjConfiguration`；补完 `InfrastructureInitializer` / `InfrastructureCapability` / `Migration*` 作为资源保证与迁移机制 |
| §3.7 `ProviderFactory` 无 `create()` | 已删 | 实现模块自带 `@AutoConfiguration`，provider 自己构造并接收配置 |
| §5.2 Transit 无装配 | 已修 | `TransitAutoConfiguration` + 状态文件持久化（多服务共享） |
| §5.3 EventBus 意图层 | 已加 | `EventPublisher` / `EventSubscriber` / `OutboxStore` / `EventBusTransport` / `EventConsumerStore`；装配收回 `starter` |
| §6 三层测试 | 已补 | 5 个 TCK、各模块装配测试、`EventBusIT` / `TransitIT` / `IdIT` |

新增约束（用户补充）：ID 生成同样纳入 core SPI —— `cn.byteforce.seedj.id.IdGenerator`
（`long id()` 雪花），在 `domain-infra-memory` 实现，多服务从共享文件领取节点编号。
- 结论：三个能力**各自发明了一套 SPI 形状、装配方式、配置键、错误模型和测试策略**。PKI 已在 ADR-0001 收敛（本评估的基准），Transit 有最好的契约文档但**没有任何装配**，EventBus 把机制暴露成业务契约且**只依赖内存模块的应用启动即失败**。建议先修 3 个已复现缺陷，再按 §4 的"SPI 宪法"统一三者，最后补 §6 的三层测试。

---

## 0. 一句话结论

> 类库对业务方的承诺是"加一个依赖、注入一个接口、调一个方法"。
> 现在 pki 做到了；transit 少了下半截（只给接口不给 bean）；eventbus 给错了上半截（给机制不给意图）。

---

## 1. 现状：三套 SPI，三种实现方式

### 1.1 差异矩阵

| 维度 | PKI | Transit | EventBus |
|---|---|---|---|
| 契约包 | `cn.byteforce.seedj.pki` | `cn.byteforce.seedj.transit.spi` | `cn.byteforce.seedj.eventbus.spi` |
| 契约组织 | 1 个接口，模型作为接口内嵌 record | 1 门面 + 4 子接口，模型**一类型一文件**（`final class` + getter） | 2 个容器类 `Ports` / `Models`，模型内嵌 record |
| 业务方怎么拿到实例 | 注入 `PkiProvider` | **`new BcTransitProvider()`**（来自实现模块） | 注入 `OutboxProvider` / `EventBusProvider` |
| 发现机制 | Spring `@Bean` 直接 new | **无** | `ServiceLoader` + `BeanFactory.createBean(Class)` 反射 |
| 工厂接口 | 无 | 无 | `ProviderFactory`（**定义了两份**：`spi/ProviderFactory.java:3` 与 `spi/Ports.java:16`） |
| 多实现选择 | 无（`@ConditionalOnMissingBean`） | 无 | 字符串名 + `seedj.eventbus.outbox-provider` |
| 配置类 | `PkiProperties`（可变 POJO + getter/setter，`Path` 类型，字段有中文注释） | **无** | `EventProperties`（record，两个 `String`） |
| 开关 | `seedj.pki.enabled`（`PkiAutoConfiguration.java:12`） | **无** | **无** |
| 实现侧可配置性 | 通过 `PkiProperties` 注入 | 无 | **不可能**：工厂只能给 `Class`，由框架反射构造 |
| 错误类型 | `PkiException(String[, Throwable])` | `TransitException(ErrorCode, retryable)` | `Ports.OutboxException` / `Ports.EventBusException` + 直接抛 `IllegalStateException` |
| 生命周期 | **不实现** `AutoCloseable` | `AutoCloseable`，`close()` 语义写在 package-info | `AutoCloseable`，`close()` 空实现 |
| 契约测试 | `PkiSpiSurfaceTest`（包级白名单） | 无 | 无 |
| 契约文档 | ADR-0001 | `package-info.java`（48 行，最严谨） | 无 |
| 装配模块 | `domain-infra-memory` | **无** | `domain-infra-memory`（实现模块，不是装配模块） |

### 1.2 装配路径（当前真实链路）

```
pki      : 业务 @Autowired PkiProvider
           ← PkiAutoConfiguration:16  (domain-infra-memory)
           ← new BcPkiProvider(PkiProperties...)

transit  : 业务 new BcTransitProvider()          ← 直接依赖实现模块，绕过一切
           （全仓库 grep 无任何 bean/自动配置引用 TransitProvider）

eventbus : 业务 @Autowired OutboxProvider / EventBusProvider
           ← EventAutoConfiguration:23-33 (domain-infra-memory)
           ← ServiceLoader<*ProviderFactory>
           ← beanFactory.createBean(factory.providerType())   ← 反射构造
```

`PkiConfiguration` 与 `EventAutoConfiguration` 同在
`domain-infra-memory/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

---

## 2. 认知负担清单（业务开发视角）

把"完成一个意图所需知道的事"逐条数一遍。**这是本项目的核心指标：知识条目数越少越好。**

### 2.1 发布一个领域事件（今天需要 10 条知识）

1. 依赖 `domain-infra-memory` **和** `domain-jdbc-postgresql`（少一个就启动失败，见 §3.1）
2. 自己在数据库建 `outbox` 表（11 列）+ `outbox_audit` 表 —— **仓库内没有任何 DDL**
3. 从 SQL 反推列类型、`sequence` 是"按 partition 严格递增"、`attempts` 语义
4. 配置 `seedj.eventbus.outbox-provider=postgres`、`seedj.eventbus.event-bus-provider=file`
5. 自己把 `AbstractAggregateRoot.raise()` 的事件翻译成 `new OutboxMessage(9 个参数)`：
   `eventId`、`topic`、`partitionKey`、`sequence`、`eventType`、`eventVersion`、`payload`、`headers`、`occurredAt`
   —— 类库**不提供** `DomainEvent → OutboxMessage` 的桥，两个 `DomainEvent` 也不是同一个接口
6. 知道 `save()` 必须在事务内（否则 `IllegalStateException`，`PostgresOutboxProvider.java:27`）
7. 知道要给业务应用加 `@EnableScheduling`，否则 `OutboxRelayTask.relay()` **永不执行**（§3.2）
8. 知道 file transport 把事件写到 **进程工作目录** `./data/eventbus`（`FileEventBusProvider.java:18`），无法配置
9. 知道 lease 丢失抛的是 `IllegalStateException`（字符串 "Lease lost"），与 `OutboxException` 混淆
10. 理解 relay 的重试/退避/心跳策略是库内固定值

目标：`eventPublisher.publish(event)` + 1 个配置项 + 1 个 DDL 文件（由库提供）。

### 2.2 加密一个字符串（今天需要 6 条知识）

1. 依赖 `domain-infra-memory`（为了拿实现类，而不是为了 bean）
2. `new BcTransitProvider()` —— 自己管理生命周期、自己保证单例
3. 没有 Spring bean ⇒ 测试里无法用假实现替换，必须走真实现
4. `new CreateKeyRequest(name, type, derived, convergentEncryption, exportable, allowPlaintextBackup)`
   —— 6 个参数、4 个 `Boolean` 三态（null = 默认），无静态工厂
5. `new EncryptRequest(keyName, plaintext, context, keyVersion)` —— 后两个传 `null`
6. `catch (TransitException e)` 后自己判断 `e.getErrorCode()` / `e.isRetryable()`

目标：注入 `TransitProvider` + `transit.crypto().encrypt(...)` + 静态工厂。

### 2.3 拿一张证书（今天需要 3 条知识 —— 唯一的合格样例）

1. 依赖 `domain-infra-memory`
2. 配置 `seedj.pki.*`（有注释、有默认值、`enabled` 可关）
3. `pki.signingIdentity("iam")`

这三条正是 ADR-0001 想要的效果。**整改目标就是把这个形状复制到另外两个能力上。**

---

## 3. 已复现/已确认的缺陷（不是风格问题）

### 3.1 【P0，已复现】只依赖 `domain-infra-memory` 的应用启动即失败

`EventAutoConfiguration` 挂在 `domain-infra-memory` 的全局 imports 上，但 outbox 的实现只在
`domain-jdbc-postgresql` 里（`META-INF/services/...OutboxProviderFactory`）。
因此"我只想用 PKI"的应用会在 refresh 阶段炸：

```
BeanInstantiationException: Failed to instantiate [Ports$OutboxProvider]:
  Factory method 'outboxProvider' threw exception
Caused by: java.lang.IllegalStateException: No SPI found for OutboxProviderFactory
    at EventAutoConfiguration.createProvider(EventAutoConfiguration.java:49)
```

复现方式（`ApplicationContextRunner` + `AutoConfigurations.of(EventAutoConfiguration.class)`，无 Postgres 模块）：

```bash
mvn -o -pl domain-infra-memory -am test -Dtest=BootProbeTest
# Tests run: 1, Failures: 1 ... No SPI found for OutboxProviderFactory
```

即：**能力模块之间存在隐藏的启动期强耦合**，与"加一个依赖就能用"的承诺直接冲突。
附带说明：`domain-infra-memory` 名为"内存/磁盘"，却**没有 outbox 实现**，本地开发必须起 Postgres。

### 3.2 【P0】relay 永远不会执行

`OutboxRelayTask.relay()` 标了 `@Scheduled(fixedDelay = 1000)`（`OutboxRelayTask.java:37`），
但全仓库 `grep -rn "@EnableScheduling\|SchedulingConfigurer"` **零命中**，`EventAutoConfiguration`
也没有 `@EnableScheduling`。`@Scheduled` 只在 `ScheduledAnnotationBeanPostProcessor` 存在时被注册，
因此：bean 建出来、日志干净、事件永久停在 `PENDING`。

业务方不可能从任何文档/报错得知这一点 —— 静默失效比抛异常更贵。

### 3.3 【P0】`Heartbeat.lost` 是创建时的快照，租约丢失守卫恒为 false

```java
// Heartbeat.java:5
public record Heartbeat(ScheduledFuture<?> future, boolean lost) {}
// OutboxRelayTask.java:107
return new Heartbeat(futureRef[0], lost.get());   // 永远 false
```

`handleLeaseLost` 把 `AtomicBoolean lost` 置 true，但 `Heartbeat` 里存的是当时的 `boolean` 副本。
后果：`OutboxRelayTask.java:63` 的 `if (heartbeat.lost()) ... return;` 永不生效，
租约已被别的节点抢走时仍会 `markSent` —— 幂等边界被破坏。

### 3.4 【P0】outbox 表结构不在仓库里

`find . -name "*.sql"` → 0 个文件。`PostgresOutboxProvider` 依赖 11 个列 + `outbox_audit` 表，
`seedj.jdbc.Migration` / `MigrationChecksums` 存在但**没有任何迁移实现**。
业务方必须读 SQL 反推 DDL，且没有版本升级路径。

### 3.5 【P1】两个 `DomainEvent`，且 eventbus 的那个是死类型

| 类型 | 状态 |
|---|---|
| `cn.byteforce.seedj.domain.event.DomainEvent` | 有 `@EventType` 逻辑名/版本、被 `AbstractAggregateRoot` 使用 |
| `cn.byteforce.seedj.eventbus.spi.Models.DomainEvent` | **全仓库零实现、零引用** |

于是"写侧（聚合）"和"投递侧（outbox）"之间没有共享词汇：`eventType` 该填
`EventTypeRegistry.logicalName(...)` 还是类名？`eventVersion` 该填 `@EventType.version` 还是 1？
无人回答。这是 §2.1 第 5 步的根因。

### 3.6 【P1】死代码：`SeedjConfiguration` / `InfrastructureCapability`

- `starter` **没有** `META-INF/spring/...AutoConfiguration.imports`，`SeedjConfiguration` 从未被注册；
- `InfrastructureCapability` 零实现（全仓库仅 `InfrastructureInitializer` 引用）；
- `SeedjConfiguration` 内 10 个 import 未使用（`ObjectMapper`、`AutoConfigurationPackages`、`List` …）；
- `InfrastructureInitializer` 同时用 `orderedStream()` 和 `sorted(comparingInt(getOrder))`，双重排序。

业务方若看到这套"能力探针"生命周期，会以为需要实现它 —— 实际是第 4 种并行机制。
（它的设计意图"表是否存在/执行 DDL"恰好是 §3.4 的答案，见 §5.3 决策项。）

### 3.7 【P2】`ProviderFactory` 定义两份，且没有 `create()` 方法

```java
public interface ProviderFactory<P> {          // spi/ProviderFactory.java
    String providerName();
    Class<? extends P> providerType();         // 只有 Class，没有实例化入口
}
```

`Ports.java:16` 又定义了一遍同名同形接口。缺 `create()` 的后果是**实现方无法决定自己的构造**：

- `FileEventBusProvider` 只能把 `./data/eventbus` 写死在构造函数里；
- `PostgresOutboxProvider` 依赖 `JdbcTemplate`，只能靠框架反射注入；
- 任何需要凭据/路径/超时的 provider 都无法通过配置注入。

即 §1.1 "实现侧可配置性：不可能"的机制根因。

---

## 4. 目标设计：SPI 宪法（8 条）

> 三个能力、未来的 3 个适配器（Vault、Kafka、Redis）都按这 8 条落。写进 `docs/spi/README.md`，
> 新增能力时逐条勾选。

**R1 一个能力 = 一个业务入口 + （可选）一个实现入口。**
业务方只注入 `PkiProvider` / `TransitProvider` / `EventPublisher`。
`OutboxStore`、`EventBusTransport` 这类机制接口只允许装配层和 provider 实现方看见。

**R2 业务入口必须是意图级（深接口）。**
判据：业务方为了完成一件事，**是否必须理解库内状态机**（claim/lease/heartbeat/attempt/version）。
是 → 接口位置错了。反例：`OutboxProvider` 的 8 个方法里业务方只需要 1 个（`save`）；
`claim/markSent/markFailed/renewLease/retry/discard` 全部是 relay 的私有协调 → 应下沉。

**R3 装配只用 Spring 自动配置，禁止 `ServiceLoader` + 反射构造。**
实现模块自带 `@AutoConfiguration`，bean 名即 provider 名；多实现用
`Map<String, T>` 注入 + `seedj.<cap>.provider` 选择；单实现零配置。
理由：IDE 可跳转、堆栈可读、`@ConditionalOnClass` 可保护、provider 自己构造（解 §3.7）。

**R4 每个能力两个配置键 + 一个 Properties 类，键名格式统一。**
`seedj.<cap>.enabled`（默认 true，kill switch）、`seedj.<cap>.provider`（多实现时必填）。
Properties 类风格统一为 record（不可变），未知键快速失败（Boot 默认）。

**R5 统一错误模型。**
`core` 提供共享父类型：`InfrastructureException(message, code, retryable)`；
每个能力一个 `ErrorCode` 枚举（`ErrorCategory` + `defaultRetryable`，直接采用 transit 现有设计）。
禁止：在契约层抛 `IllegalStateException`（租约丢失 → `LEASE_LOST` code）、嵌套 `Ports.XxxException`、字符串匹配。

**R6 统一模型风格。**
一类型一文件、`record`（或 `final class` + 静态工厂，二选一）、compact constructor 内校验、
`toString` 脱敏。禁止 `Ports`/`Models` 容器类，禁止手写 `equals/hashCode`（`Models.java:28-41`）。

**R7 统一生命周期。**
三个入口都 `extends AutoCloseable`；`close()` 幂等且不抛；由 Spring 推断 destroy 方法。
`close()` 之后的行为写进契约（transit 已有 `PROVIDER_CLOSED`，抄过来）。

**R8 每个能力三件套测试，缺一不可。**
① 契约 TCK（`core` test-jar，实现方 `extends` 一行）；
② 装配测试（`ApplicationContextRunner`，覆盖"只有本模块""多实现选择""用户覆盖""无 micrometer"）；
③ 集成测试（`starter`，真 bean、真 HTTP、真 DB）。

---

## 5. 各能力整改动作

### 5.1 PKI（基准，只做 3 处微调）

| 动作 | 理由 |
|---|---|
| `PkiProvider extends AutoCloseable`（默认空实现） | R7 |
| `PkiException` 继承 `InfrastructureException`，加 `ErrorCode` | R5 |
| `PkiSpiSurfaceTest` 泛化为 `SpiSurfaceTest(package, allowed)`，三个包共用 | R8，防止再次泄漏 |

其余不动。ADR-0001 的成果是本轮整改的模板。

### 5.2 Transit（缺的是下半截，工作量最小）

1. **新增 `TransitAutoConfiguration`（`domain-infra-memory`）**：
   ```java
   @AutoConfiguration
   @ConditionalOnProperty(prefix = "seedj.transit", name = "enabled", matchIfMissing = true)
   @EnableConfigurationProperties(TransitProperties.class)
   public class TransitAutoConfiguration {
       @Bean @ConditionalOnMissingBean
       TransitProvider transitProvider(TransitProperties props) {
           return new BcTransitProvider(props);   // 现在可以传配置了
       }
   }
   ```
   注册进 `AutoConfiguration.imports`；`TransitProperties` 提供 provider 名 + 本地实现策略（无默认密钥、无落盘）。
2. **`BcTransitProvider` 构造可配置**：至少 `provider` 名；本地实现默认"纯内存"，显式声明不持久化（避免业务方误以为重启后密钥还在）。
3. **模型补静态工厂 + 三态收敛**：
   `CreateKeyRequest.of(keyName, KeyType)`、`EncryptRequest.of(keyName, plaintext)`；
   把 4 个 `Boolean` 三态换成 `KeyOptions` record（`derived/convergent/exportable` 各有明确默认值）。
4. **`TransitKeyAdmin` 从门面剥离为独立 bean**（P2）：业务方只需要 crypto/signer/mac；密钥管理属运维面。

业务侧最终形态（3 条知识）：依赖 `domain-infra-memory` → 注入 `TransitProvider` → `transit.crypto()`。

### 5.3 EventBus（改动最大，但收益最高）

**(a) 把契约拆成"意图层 / 机制层"**

```java
// 意图层（业务方唯一入口，cn.byteforce.seedj.eventbus）
public interface EventPublisher {
    void publish(DomainEvent event);          // 事务内调用；实现负责 outbox 或直投
    void publish(OutboxMessage message);      // 逃生舱：显式消息
}

// 机制层（只有 relay 和 provider 看见，cn.byteforce.seedj.eventbus.spi）
public interface OutboxStore { void append(OutboxMessage m); List<Claimed> claim(ClaimRequest r); ... }
public interface EventBusTransport { CompletionStage<Void> send(OutboxMessage m); }
```

`OutboxProvider` 现名保留为 `OutboxStore` 的别名式迁移（`@Deprecated` 一版），避免一次性破坏 starter。

**(b) 补上缺失的桥：`DomainEvent → OutboxMessage`**

`EventTypeRegistry.logicalName()/schemaVersion()` 作为 `eventType`/`eventVersion` 的唯一来源；
`eventId` 由库生成；`sequence` 由 store 分配（不是业务方填）；`payload` 用显式注册的序列化器。
删除死类型 `Models.DomainEvent`（§3.5）。

**(c) relay 自持调度器，不依赖业务方注解**

`OutboxRelayTask` 内部用 `ScheduledExecutorService`（它已经有两个线程池了）在
`afterPropertiesSet()` 启动、`@PreDestroy` 停止；删掉 `@Scheduled`。
业务方零注解要求；`relay()` 保持 public 以便单测（§3.2 同时被修）。

**(d) 补齐本地实现，让 `domain-infra-memory` 自洽**

新增 `InMemoryOutbox` + `FileOutbox`（磁盘 journal）；`domain-infra-memory` 单独可启动（修 §3.1），
本地开发不需要 Postgres。

**(e) DDL + 迁移（已按用户决策修订）**

不落裸 SQL 文件。DDL 作为版本化 `Migration` 条目交付，由 `JdbcMigrationRunner`
（version + SHA-256 checksum + `(scope, version)` 历史表 + PostgreSQL advisory lock）执行；
`InfrastructureInitializer` 在启动期调用 `InfrastructureCapability` 保证表与 topic 存在。
不引入 Flyway，也不保留 `schema-initialize` 开关。

**(f) 配置与装配**

```yaml
seedj:
  eventbus:
    enabled: true
    provider: memory        # memory | postgres | kafka ...
    relay: { enabled: true, batch-size: 100, lease: 30s, poll-interval: 1s }
    file:  { base-dir: ${java.io.tmpdir}/seedj-eventbus }   # 不再写死 ./data
```

**(g) 模块边界**

`EventAutoConfiguration` + `OutboxRelayTask` 属于**装配/策略**，不属于"内存实现"。
装配统一放 `starter`（已实施，不再新增独立装配模块），
`domain-infra-memory` / `domain-jdbc-postgresql` 退回纯适配器。
（若不想加模块，最低要求：把 relay 与选择逻辑移到 `starter`，并在 POM 注释写明理由。）

**(h) 修 `Heartbeat`**

`record Heartbeat(ScheduledFuture<?> future, AtomicBoolean lost)`，或改成 `final class` 持有 `AtomicBoolean`。
同时补一个"租约被抢占后不得 markSent"的单测（§3.3）。

---

## 6. 集成测试补齐方案（eventbus / transit）

### 6.1 分层策略

| 层 | 位置 | 依赖 | 覆盖什么 | 数量级 |
|---|---|---|---|---|
| 契约 TCK | `core` test-jar | 无 | SPI 语义（transit package-info 那 48 行、lease/幂等/批量/脱敏） | 4 个抽象类 × 20 断言 × 每个实现 1 行 `extends` |
| 装配测试 | 各实现模块 | `ApplicationContextRunner` | 单模块可启动、多实现选择、`@ConditionalOnMissingBean` 覆盖、micrometer 缺失、`enabled=false` | 每能力 4~6 个 |
| 集成测试 | `starter/src/test` | Testcontainers（已有） | 真 bean、真事务、真 HTTP、真 DB 状态迁移 | 见 6.2 |

当前 `starter` 的 `buildCommonProperties()`（`ClusterExtension.java:320`）**只写了 `seedj.pki.*`**，
eventbus / transit 从未进入应用上下文。

### 6.2 `starter` 集成测试用例清单

**`EventBusIT`（新增，复用 `ClusterExtension`）**

1. IM 内一个测试端点：事务内 `raise(OrderCreated)` → `eventPublisher.publish(e)` → HTTP 200。
2. 500ms 后轮询：`outbox.status` 从 `PENDING` → `PROCESSING` → `SENT`；`attempts = 1`。
3. `seedj.eventbus.file.base-dir=<tempDir>` 下出现对应 topic 目录与文件，内容 = payload。
4. 负向：transport 注入失败（`publish` 返回 exceptional stage）→ `status = PENDING`、`next_retry_at` 前移、`attempts = 2`。
5. 负向：不在事务内调用 `publish` → 明确错误码（不是裸 `IllegalStateException`）。
6. 并行：同一 `partitionKey` 的 3 条事件必须按 `sequence` 顺序落盘（验证 claim 的 `NOT EXISTS` 子查询）。
7. 运维面：`retry(recordId, operator)` 后 `outbox_audit` 有记录，且状态回到 `PENDING`。

**`TransitIT`（新增，无需 Docker）**

1. 注入 `TransitProvider`（证明有 bean）。
2. `createKey` → `encrypt` → `decrypt` 往返一致。
3. `rotateKey` → 新密文用 v2、旧密文仍可解密。
4. `decrypt` 传错 `context` → `INVALID_CONTEXT`。
5. `close()` 后再调用 → `PROVIDER_CLOSED`；`close()` 两次不抛。
6. `toString()` 不含明文/密文（`package-info` 承诺，必须可执行）。

**`IdIT`（新增，无需 Docker）**：`/api/public/test/ids` 断言雪花单调、UUIDv7 `version=7` 且可排序。

**跨服务（已实现）**：IM 发布 → HR 按消费者组消费，见 `EventConsumeIT`。

### 6.3 无 Docker 的降级路径

`EventBusIT` 的 1~3、5~6 可用 `InMemoryOutbox` + `FileEventBusTransport` 在 `starter` 的
`@SpringBootTest` 里跑（无需 Testcontainers）；Postgres 专属断言（`FOR UPDATE SKIP LOCKED`、
`gen_random_uuid`）单独一个 `@Testcontainers` 用例，默认 profile 下 `@Disabled` 或 `@Tag("docker")`。

---

## 7. 分阶段落地

| 阶段 | 内容 | 验收 | 预估 |
|---|---|---|---|
| **P0 止血** | §3.1 装配隔离（outbox bean 缺失不再炸启动）、§3.2 relay 自持调度、§3.3 `Heartbeat` 修复、§3.4 DDL 入仓、§3.6 死代码删除、`EventAutoConfiguration`/`TransitAutoConfiguration` 装配测试 | `mvn -pl core,domain-infra-memory,domain-jdbc-postgresql test` 绿；`ApplicationContextRunner("只有 memory 模块")` 不失败；新增 relay 单测覆盖租约抢占 | 2~3 天 |
| **P1 收敛** | R3 去 ServiceLoader（bean-map 选择）、R4/R5/R6 配置/异常/模型统一、`EventPublisher` 意图层、`DomainEvent` 桥、`domain-infra-memory` 补 outbox 实现、transit 装配 | 业务方代码示例从 10 步降到 3 步；`grep -r "ServiceLoader" --include=*.java .` 零命中；两个 `ProviderFactory` 合并 | 4~6 天 |
| **P2 加固** | TCK 四件套 + surface test 泛化、`starter` 的 `EventBusIT`/`TransitIT`、`docs/spi/README.md` 宪法、装配收回 `starter`、`TransitKeyAdmin` 剥离 | §6.2 用例全绿；新实现方接入只需 1 行 `extends` + 1 个 `@Bean` | 4~6 天 |

先做 P0：三项都是"业务方一用就撞"的问题，且互相独立，可并行。

---

## 8. 可执行的验收清单

```bash
# 1. 装配不互相绑架：只有 memory 模块也必须能起
mvn -o -pl domain-infra-memory -am test -Dtest='*AutoConfigurationTest'

# 2. relay 真的会跑：租约被抢占后不得 markSent
mvn -o -pl domain-infra-memory test -Dtest='OutboxRelayTaskTest,HeartbeatTest'

# 3. 契约语义可执行（每个实现一行 extends）
mvn -o -pl core,domain-infra-memory,domain-jdbc-postgresql test -Dtest='*ContractTest'

# 4. 端到端
mvn -o -pl starter verify -Dit.test='SeedjIT,EventBusIT,EventConsumeIT,TransitIT'

# 5. 形状检查（应全部为 0）
grep -rn "ServiceLoader" --include=*.java .
grep -rn "class Ports\|class Models" --include=*.java core/
find . -name "*.sql" -not -path '*/target/*' | wc -l        # 应为 ≥1
grep -rn "new BcTransitProvider" --include=*.java starter/src/main domain-*/src/main   # 应为 0
```

---

## 9. 需要决策的开放问题

1. **eventbus 是"发布"还是"事件总线"？** 已定：是事件总线。补 `EventSubscriber` /
   `EventConsumerStore`，支持消费者组、ack、retry、DLQ；跨服务 IM→HR 已由 `EventConsumeIT` 覆盖。
2. **file transport 是测试夹具还是生产适配器？** 现在两者身份不明（写 CWD、无配置、无顺序保证）。
   建议明确标注 `@Deprecated`/`local-dev only`，或补齐"单机、有序、可配置目录"的实现承诺。
3. **`InfrastructureCapability` 保留还是删除？** 已定：保留。框架不引入 Flyway，
   由 initializer + 版本化 migration 保证数据库表与队列 topic 一定存在。
4. **`domain-infra-memory` 是否改名为 `domain-infra-local`？** 名字里的 "memory" 与"含 PKI 磁盘状态、
   文件事件总线"不符；1.0.0 未发布，此时改名免费。
5. **`core` 是否引入编译期依赖做 TCK？** 建议 `core` 出 test-jar（JUnit 5 + AssertJ，
   已经是 test scope 依赖），不改 `core` 的 main 依赖面。
