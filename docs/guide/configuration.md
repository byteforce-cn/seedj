# 配置说明

## 两个内置 profile

| | `local` | `postgres` |
|---|---|---|
| 数据源 | 无（显式排除自动配置） | `jdbc:postgresql://localhost:5432/seedj` |
| 命令/对象存储 | 内存，重启清空 | PostgreSQL，版本化迁移 |
| 锁 | 内存 | PostgreSQL |
| 审计 / outbox / eventbus | 关闭 | 开启 |
| 决策账本（`seedj-server/ai`） | 内存 `InMemoryDecisionLedger`（重启即丢） | `agent_decision_*` 两张表 |
| AI 角色开关（`seedj-server/ai`） | 只开决策面：`plane.enabled=true`、`guard` / `runtime` 关闭 | 同左（决策面与数据源无关） |
| 开发认证 | `dev-auth` 固定调用方 `local-admin` | 关闭，走 OIDC |
| 用途 | 本地开发与测试 | 接近生产的单实例 |

启动：

```bash
# local
./mvnw -pl seedj-server/hr -am spring-boot:run -Dspring-boot.run.profiles=local

# postgres（仓库自带 docker-compose）
docker compose up -d
./mvnw -pl seedj-server/hr spring-boot:run -Dspring-boot.run.profiles=postgres
```

`docker-compose.yml` 起的是 `postgres:17-alpine`，库/用户/口令都是 `seedj`，端口 5432。
数据源可用环境变量覆盖：`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、
`SPRING_DATASOURCE_PASSWORD`。

`local` 的 `dev-auth` 与 `degraded-mode` 只允许本地/测试使用；生产 profile 的守卫会让
应用在开启它们时启动失败。

## 常用配置项

| 配置 | 取值 | 说明 |
|---|---|---|
| `seedj.command.provider` | `memory` / `postgres` | 命令运行时与幂等存储 |
| `seedj.object.provider` | `memory` / `postgres` | 双时间轴对象存储 |
| `seedj.read.generic-surface` | `enabled` / 关闭 | 通用 `/objects` 面；生产宿主默认关闭 |
| `seedj.audit.enabled` | `true` / `false` | 审计链；开启后写入 `audit_*` 表 |
| `seedj.outbox.enabled` | `true` / `false` | outbox 投递 |
| `seedj.eventbus.enabled` | `true` / `false` | 事件总线订阅 |
| `seedj.lock.memory.enabled` | `true` / `false` | 单实例内存锁 |
| `seedj.lock.postgres.enabled` | `true` / `false` | 多实例数据库锁 |
| `seedj.biz.dev-auth.enabled` | `true` / `false` | 本地开发调用方注入，禁止生产开启 |
| `seedj.biz.dev-auth.permissions` | 逗号分隔 | dev 调用方的权限集合 |
| `seedj.biz.issuer-uri` | URL | 业务侧校验令牌的 issuer |
| `seedj.biz.public-paths` | 逗号分隔 | 免认证路径 |
| `seedj.biz.cors.allowed-origins` | 逗号分隔 | 浏览器跨域来源 |
| `seedj.security.derived-field-keys` | 逗号分隔密钥 | 派生字段（如证件号散列）的密钥环 |
| `seedj.iam.secret.key` | 字符串 | IAM 签名的稳定密钥；`store=jdbc` 时必须跨重启一致 |
| `seedj.iam.token.resource-audience` | 字符串 | 多资源服务器时的 `aud` |
| `seedj.outbox.relay.*` | 时长/批量 | relay 轮询与批量参数 |
| `seedj.outbox.store.*` | 路径/表名 | outbox 存储位置 |
| `seedj.command.degraded-mode` | `true` / `false` | 降级模式，仅本地允许 |
| `seedj.ai.enabled` | `true` / `false` | AI 总闸；`false` 时连启用标记都不产 |
| `seedj.ai.plane.enabled` | `true` / `false` | 决策面；默认 `false`，开启后租户与身份是启动期必填 |
| `seedj.ai.guard.enabled` | `true` / `false` | 执行侧守卫；默认 `true`，只当决策面时关掉 |
| `seedj.ai.runtime.enabled` | `true` / `false` | agent 循环；默认 `false`，开启后 `runtime.model.*` 必填 |
| `seedj.ai.decision.kid` / `signing-key` | 字符串 | 密钥标识与 Ed25519 私钥；`signing-key` 空则临时密钥并 WARN |
| `seedj.ai.policy.enabled` | `true` / `false` | 策略版本钉死；默认 `false`，开启后 `engine` / `bundle` / `version` 构成受管策略包 |
| `seedj.ai.policy.bundle` / `version` / `epoch` | 字符串 / 长整型 | 受管策略包与当前 head；`enabled=true` 时前两项必填，`epoch` 可空 |
| `seedj.ai.policy.verify` | `irreversible` / `none` | 守卫对不可逆动作校验 head；`none` 只钉版本不校验 |
| `seedj.ai.llm.enabled` | `true` / `false` | LLM 客户端；默认 `false`，开启后 `base-url` / `model` 必填 |
| `seedj.ai.llm.base-url` / `model` / `api-key` | 字符串 | OpenAI 兼容端点、模型名与可选密钥（密钥不进日志） |
| `seedj.ai.llm.timeout` / `max-attempts` / `tool-choice` | 时长 / 整数 / 枚举 | 超时（默认 30s）、重试次数（默认 2）、工具选择（`required` / `auto`） |
| `seedj.ai.provider` | `postgres`（`matchIfMissing=true`）/ 其它 | 账本实现的**开关**，不是 `AiProperties` 的绑定项：由 `seedj-jdbc-postgresql` 的 `PostgresAiAutoConfiguration` 读取，等于 `postgres` 时装配数据库账本，其它值让它整体退出；starter 不提供内存账本 bean，非 `postgres` 场景宿主必须自备 `DecisionLedger` |

## coord 基础设施

`seedj-infra-coord` 是显式选择：`seedj.<cap>.provider=coord` 但没有
`seedj.coord.enabled=true` 时启动失败（`CoordProviderGuard`），不会悄悄退回本地实现。

| 配置 | 默认 | 说明 |
|---|---|---|
| `seedj.coord.enabled` | `false` | coord 总开关；关掉时上面这类 provider 直接判配置错误 |
| `seedj.coord.agent-host` / `agent-port` | `127.0.0.1` / `19527` | agent 地址；失败消息会点名它 |
| `seedj.coord.request-timeout` | `5s` | 单次 RPC 超时 |
| `seedj.coord.namespace` | `seedj:<应用名>` | idgen/编号/序列的名称前缀 |
| `seedj.coord.id.name` / `batch-size` | `application` / `1000` | ID 业务键与本地预取段长 |
| `seedj.coord.numbering.prefix` | `numbering` | 编号 scope 前缀 |
| `seedj.coord.clock.name` | `global-sequencer` | 全局序列的 idgen 名称 |
| `seedj.coord.eventbus.partitions` / `producer` | `4` / 应用名 | topic 分区数（发布与自动建 topic）与发布方标识 |
| `seedj.coord.eventbus.consumer-max-messages` | `0` | 每次 poll 向 agent 要的最大条数；0 = agent 默认 |
| `seedj.coord.eventbus.consumer-scan-limit` | `1000` | 每次 poll 扫描投递账本的上界（账本大小 ≈ 在飞消息数） |
| `seedj.coord.lock.holder-id` | 空 | 锁持有者标识；空 = `host:pid:<随机后缀>`（每个 JVM 唯一，重启变化） |
| `seedj.coord.auth.token` / `token-file` | 空 | 鉴权令牌；也读 `COORD_AUTH_TOKEN`，不要写进配置文件 |

agent 不可达时装配仍然成功（客户端惰性连接），失败发生在首次取号，并且消息里带
`host:port`。

分布式锁用 coord 的 KV + 事务组合，而不是 `LockClient`：coord 的锁租约只到秒级，而
`LockProvider` 契约要求毫秒级 TTL。记录里带 `expiresAt`，由**各实例本地时钟**判定过期，
事务读集校验保证同一时刻只有一个持有者能写成功。时间漂移会缩短或延长互斥窗口；临界区
仍须幂等或用存储侧唯一约束兜底。

```yaml
seedj:
  coord:
    enabled: true
    lock:
      holder-id: ${HOSTNAME}:${PID}   # 可省略
  lock:
    provider: coord
```

`seedj.eventbus.transport=coord` 同时装配发布与消费：消费状态（租约、重试延迟、attempts、死信）
写在 coord KV 的投递账本里，消息本体仍在 MQ。租约与 `retry(delay)` 由本地时钟判定（毫秒级），
所以 `retry` 不会阻塞分区；到期重投按位点回读 MQ，消息已被 retention 清掉时转入死信并记
`seedj.retention.expired`。死信存 KV，因此单条事件大小受 KV 单值上限约束。

## 数据库迁移

`seedj-jdbc-postgresql` 自带 `V1` 起的版本化迁移，启动时执行并记录 checksum；已应用迁移
内容一旦变化会让启动失败。业务新版本迁移由 `seedj-codegen` 生成候选，人工审阅后按
`V2`、`V3`… 顺序提交，已冻结的旧版本不再修改。
