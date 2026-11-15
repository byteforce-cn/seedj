# 配置说明

## 两个内置 profile

| | `local` | `postgres` |
|---|---|---|
| 数据源 | 无（显式排除自动配置） | `jdbc:postgresql://localhost:5432/seedj` |
| 命令/对象存储 | 内存，重启清空 | PostgreSQL，版本化迁移 |
| 锁 | 内存 | PostgreSQL |
| 审计 / outbox / eventbus | 关闭 | 开启 |
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
| `seedj.coord.eventbus.partitions` / `producer` | `4` / 应用名 | topic 分区数与发布方标识 |
| `seedj.coord.auth.token` / `token-file` | 空 | 鉴权令牌；也读 `COORD_AUTH_TOKEN`，不要写进配置文件 |

agent 不可达时装配仍然成功（客户端惰性连接），失败发生在首次取号，并且消息里带
`host:port`。

## 数据库迁移

`seedj-jdbc-postgresql` 自带 `V1` 起的版本化迁移，启动时执行并记录 checksum；已应用迁移
内容一旦变化会让启动失败。业务新版本迁移由 `seedj-codegen` 生成候选，人工审阅后按
`V2`、`V3`… 顺序提交，已冻结的旧版本不再修改。
