# starter-eis-it — EIS 集成套件

EIS 内核族对 **真实 PostgreSQL 16** 的端到端验证（Testcontainers）。
测试套件模块：不产出可消费构件（不入 BOM、禁止 install/deploy）。

**不引入 H2**（硬性工程决策）：EIS 依赖域类型、`TIMESTAMPTZ(6)`、append-only 规则、
部分唯一索引等 PG 特性，内存库不可模拟。

## 运行

```bash
# 默认 verify 不跑集成（模块无单测）
./mvnw -B -pl starter/eis-it -am verify -Pit

# 镜像覆盖（默认 postgres:16-alpine）
./mvnw -B -pl starter/eis-it verify -Pit -Deis.it.postgres.image=postgres:16.4-alpine
```

前置：本机 Docker 可用；镜像不可用时**硬失败**（集成验证必须产生真实证据，不静默跳过）。

## 用例范围（统 25 例）

| 测试类 | 覆盖 |
| --- | --- |
| `EisObjectRepositoryIntegrationTest` | 全类型往返（含 `DATE` / `BIGINT` / `NUMERIC` / `UUID_ARRAY`，数组列 NULL → 空列表、与 `TEXT_ARRAY` 互不串型）、封口相接、半开区间边界（业务/系统/双轴）、行锁并发串行、类型化条件查询（含数组列等值） |
| `EisAuditChainIntegrationTest` | 序列连续、链头一致、回滚不留缺口、并发串行（校验全绿 + 序列 1..N）、文件锚定 |
| `EisCommandRuntimeIntegrationTest` | 命令闭环（命令/事件/审计/outbox 同事务）、时间与数值字段往返、幂等重放与冲突、拒绝路径、载荷脱敏、并发同键恰一次、并发命令审计无缺口 |
| `EisTemporalIntegrationTest` | 时点查询 `/objects`、`/time-travel` 半开区间语义与敏感列读投影剔除 |

容器与建库：`EisPostgresContainer`（JVM 单例）+ `AbstractEisIntegrationTest`
（运行时物化 DDL `META-INF/eis/ddl/eis-core.ddl` + `eis-audit.ddl` + 测试对象表
`eis-it/it-schema.ddl`；每用例 TRUNCATE 隔离）。
