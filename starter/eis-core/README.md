# starter-eis-core — EIS 核心运行时

EIS 写路径与对象存储的通用实现：命令受理闭环（幂等 → 授权钩子 → 规则链 → 处理器 → 事件 →
审计 → 封口，**同一 PostgreSQL 事务**）、双时间轴对象存储、outbox，以及全部扩展 SPI。
语义与 EIS 参考内核（`eis/tools/lib/kernel.py`）逐项对齐。

## 依赖与装配

引入依赖即生效（`META-INF/spring/…AutoConfiguration.imports`）：

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-eis-core</artifactId>
</dependency>
```

前置：`JdbcTemplate`（Boot JDBC）与 `PlatformTransactionManager`；
**审计必配**：容器需存在 `EisAuditAppender`（由 `starter-eis-audit` 提供），缺失时运行时不装配。

表结构：执行 `META-INF/eis/ddl/eis-core.ddl`（域定义 + `command`/`event`/`view` 契约表 +
辅助表 `eis_idempotency`/`eis_command_event`/`eis_command_rejection`/`eis_outbox`）。

### 领域字段类型（kind ↔ 生成列）

`EisFieldKind` 与 `eis/tools/schema-compiler` 的生成列一一对应；数组列只支持
`TEXT[]` / `UUID[]` 两种生成物（其余 array 组合编译器回退 `JSONB`）：

| `EisFieldKind` | schema-compiler 生成列 | Java 类型 |
| --- | --- | --- |
| `TEXT` | `TEXT`（`CHAR(3)` / `CHAR(7)` pattern 同以 `TEXT` 注册） | `String` |
| `BOOLEAN` | `BOOLEAN` | `Boolean` |
| `UUID` | `UUID` | `UUID` |
| `TIMESTAMP` | `TIMESTAMPTZ(6)` | `Instant` |
| `JSONB` | `JSONB` | `JsonNode` |
| `TEXT_ARRAY` | `TEXT[]`（`type: array, items.type: string`） | `List<String>` |
| `UUID_ARRAY` | `UUID[]`（`type: array, items.format: uuid`） | `List<UUID>` |
| `DATE` | `DATE` | `LocalDate` |
| `BIGINT` | `BIGINT`（`type: integer`） | `Long`（写入接受整型 `Number`） |
| `NUMERIC` | `NUMERIC` / `NUMERIC(p,s)`（`type: number`、金额/数量 pattern） | `BigDecimal` |

数组列读出口径：数据库 `NULL` / 空数组一律返回空列表（与 `TEXT_ARRAY` 一致）；
`UUID_ARRAY` 写入接受 `List<UUID>`（元素为字符串 UUID 时按 `UUID.fromString` 归一，
非法值报错并带字段名）。

## 扩展点（应用按域注册）

| SPI / Bean | 作用 |
| --- | --- |
| `EisObjectType`（Bean） | 对象类型注册：逻辑名 ↔ 表名 + 领域列 + 敏感列 |
| `EisCommandHandler` | 命令处理器：校验、对象读写、事件草稿、`storedPayload` 脱敏 |
| `EisRule` | 规则链（Spring `@Order` 排序；抛 `EisCommandRejectedException` 拒绝） |
| `EisAuthorizer` | 授权钩子（权限串 = 命令类型）。**缺省 fail-closed：应用必须注册 EisAuthorizer Bean**（可自行适配到 `starter-eis-authz` 的判定操作面）；仅在受控环境可显式 opt-in 全部放行 |
| `EisAuditAppender` | 审计追加（必配；`starter-eis-audit` 提供哈希链实现） |
| `EisNotifyHook` | 提交后失效/唤醒钩子（best-effort、fail-open；默认空操作） |
| `EisOutboxDispatcher` | outbox 分发替换点（默认进程内；跨服务通道预留 coord MQ） |

## 语义要点

- **幂等**：同键同内容 → 返回原终态（`replayed`）；同键不同内容 → `EIS-409-001` 冲突
  （返回首次命令 ID）；指纹 = SHA-256（规范化 `{payload, targets, type}`，`pythonDumps` 模式）；
- **拒绝**：命令记录以 `rejected` 落库（原因 + 错误码），不写事件、不入审计链（受理才入链）；
- **单一时刻**：命令 `issued_at`、事件 `occurred_at/recorded_at`、对象封口与审计时间戳共享同一 UTC 微秒时刻；
- **双时间轴**：更新 = 封旧行（`valid_to = superseded_at = now`）+ 追新行（`version + 1`）；
  时点查询半开区间 `[from, to)`；并发更新经行锁（有界重试）串行；
- **端点**：`POST /commands/{commandType}`（202/400/403/404/409，契约形状响应体，**不经**
  `ApiResponse` 包裹）、`GET /commands/{id}`；操作人来自 Spring Security 上下文（JWT `sub`）。

## 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.eis.core.enabled` | `true` | 总开关 |
| `byteforce.eis.core.allow-all-authorizer` | `false` | 仅当容器无 `EisAuthorizer` Bean 时生效：`true` = 使用内建全放行授权器（启动 WARN，禁止生产）；`false` = fail-closed，应用启动失败 |

### 授权安全默认（fail-closed）

命令受理链的第一道门是授权。本 starter 的缺省语义为 **fail-closed**：

- 存在 `EisAuthorizer` Bean → 使用应用提供的决策器；
- 无 Bean 且 `byteforce.eis.core.allow-all-authorizer=false`（默认）→ **应用启动失败**，
  错误信息含属性名与配置示例；
- 无 Bean 且 `byteforce.eis.core.allow-all-authorizer=true` → 使用内建全放行授权器，
  启动打 `WARN`；**禁止用于生产**。

## 边界

- 不负责审计实现（见 `starter-eis-audit`）、时点查询端点（`starter-eis-temporal`）、
  权限判定（`starter-eis-authz`）；
- 除 PostgreSQL 与（可选）coord 外不引入任何中间件；
- 集成验证见 `starter-eis-it`（Testcontainers PG16）。
