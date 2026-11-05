# ADR-0006：分布式锁独立为一级基础设施能力

- 状态：accepted
- 日期：2026-11-06
- 决策者：seedj 基础设施维护者
- 关联：`docs/adr/ADR-0002-infra-spi-constitution.md`、`docs/adr/ADR-0005-cqrs-and-identity-contract.md`

## 1. 背景

CQRS 命令模板需要"同一聚合的命令串行执行"，第一版把锁定义成 `cn.byteforce.seedj.cqrs.spi.CommandLock`。这是能力归属错误：

- outbox relay 需要"同一分区只允许一个 relay 实例"；
- workflow 调度需要"同一流程实例只允许一个调度器"；
- 业务对账、号段分配、外部资源占位都需要同一把锁；
- 以上消费者没有一个属于 cqrs，却都被迫依赖 cqrs 的类型与传递依赖。

对照 `pki` / `transit`：它们各自是独立能力，公共契约在自己的包里，provider 在 `domain-infra-memory` 里，谁也不拥有谁。锁必须是同样的形状。

## 2. 决策

### 2.1 公共契约 `cn.byteforce.seedj.lock`

| 类型 | 职责 |
|---|---|
| `LockProvider` | 能力入口 + provider 契约：`tryAcquire(key, ttl)`、`acquire(key, ttl)`（占用即抛 `LOCK_BUSY`）、`close()` |
| `LockLease` | 租约：`key()`、`close()`（幂等、只释放自己的）；`LockLease.of(key, release)` 供实现复用 |
| `LockErrorCode` | `INVALID_KEY` / `INVALID_TTL` / `LOCK_BUSY`(可重试) / `PROVIDER_UNAVAILABLE`(可重试) / `PROVIDER_CLOSED` / `INTERNAL_ERROR` |
| `LockException` | 统一异常，继承 `InfrastructureException` |

### 2.2 语义写进契约，不留给实现解释

- `tryAcquire` **不阻塞**：等待策略属于调用方（退避、队列、快速失败）。
- 租约到期是唯一的崩溃恢复机制；因此 **锁不提供绝对互斥**：GC 停顿、时钟漂移、TTL 过短都可能重叠。临界区操作仍必须幂等，或有存储侧唯一约束兜底。
- `close()` 幂等且只能释放自己持有的租约（实现用持有者 token 校验）。
- provider 不可用时**必须报错**（`PROVIDER_UNAVAILABLE`），不得静默降级为"获取成功"，也不得假装"没抢到"。
- 不提供公平性、不提供可重入。

### 2.3 归属与依赖方向

- 锁**不被任何能力拥有**：`cqrs` 只是消费者之一，`CommandAgent` 依赖 `lock.LockProvider`。
- `lock` 包不得 import `cqrs` / `eventbus` / `outbox`，由 `LockPackageIndependenceTest` 做形状门禁（与 outbox 的门禁同构）。
- `CqrsErrorCode` 删除 `LOCK_BUSY`；锁相关错误一律走 `LockException`，调用方按 `isRetryable()` 统一处理。

### 2.4 适配器与默认值

- `domain-infra-memory` 提供 `InMemoryLockProvider`（可注入 `Clock`，测试可精确控制到期）。
- 自动配置 `InMemoryLockAutoConfiguration` **默认关闭**，必须 `seedj.lock.memory.enabled=true`。理由：默认"进程内互斥"是多实例部署最坏的失效模式——本地全绿、线上静默失效且无任何报错。
- 生产环境实现（Redis / 数据库 / ZooKeeper）按 `LockProviderContractTest` 补，不在本 ADR 内。

### 2.5 契约测试

`LockProviderContractTest`（core test-jar，7 个用例）：空闲可获取、同键互斥、异键独立、释放可重入且幂等、租约到期可重入、占用时 `acquire` 抛 `LOCK_BUSY`、空白键与非法 TTL 显式失败、`key()` 回显。到期用例用真实时间（150ms TTL + 400ms 等待），因为真实 provider 无法伪造时钟；内存实现另有可控时钟用例。

## 3. 后果

### 3.1 变更

- 新增公共契约包 `cn.byteforce.seedj.lock`，`SpiSurfaceTest` 精确登记 4 个类型。
- 删除 `cn.byteforce.seedj.cqrs.spi.CommandLock` 与 `InMemoryCommandLock`；`CommandAgent.Options.lock` → `lockProvider`，`withLock` → `withLockProvider`。
- `CommandAgent` 在锁被占用时抛 `LockException(LOCK_BUSY)`（可重试），不再借用 `CqrsException`。
- 演示模块 `demo.hr` 装配 `LockProvider`（内存实现），锁键仍是 `聚合类型:编码后标识`。

### 3.2 约束

- 生产装配必须提供分布式 provider；`InMemoryLockProvider` 只能用于单实例。
- `lockTtl` 必须大于临界区耗时（CQRS 里即"加载 + 处理 + 保存 + 发布"），否则锁在临界区中途失效。
- 需要"绝对串行"的业务，除锁之外还要有唯一约束或幂等键——锁会过期，约束不会。

## 4. 验证

```bash
mvn -o test
```

| 模块 | 用例 |
|---|---|
| core | 110（含契约模板 7、`LockPackageIndependenceTest` 1、`SpiSurfaceTest` 新增 `lock` 包参数 1） |
| domain-infra-memory | 130（含 `InMemoryLockProviderTest` 8 = 契约 7 + 可控时钟 1、`InMemoryLockAutoConfigurationTest` 3） |
| starter | 23（HR 命令走 `LockProvider`，锁键断言仍在） |

形状门禁：`SpiSurfaceTest` 对 12 个包做公开类型精确匹配；`LockPackageIndependenceTest` 保证锁能力不反向依赖 cqrs/eventbus/outbox。
