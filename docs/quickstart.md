# 15 分钟 quickstart：本地跑 HR

本页目标：不装数据库、不接 IdP，10 分钟内让 HR 服务跑起来并完成 create → get → update → list。

## 前置

- JDK 21（`java -version`）
- 仓库自带 Maven Wrapper（`./mvnw`），无需单独安装 Maven
- Docker 只在跑 PostgreSQL 集成测试时需要；本地模式不需要

## 启动

```bash
./mvnw -pl seedj-server/hr -am spring-boot:run \
  -Dspring-boot.run.profiles=local
```

`local` profile 的显式约定（见 `seedj-server/hr/src/main/resources/application-local.yml`）：

- 排除 DataSource/JdbcTemplate/事务管理器自动配置，零外部依赖；
- 命令与对象使用内存实现（进程重启数据清空）；
- 锁用内存实现；审计、outbox、eventbus 关闭；
- `seedj.command.degraded-mode=true` 与 `seedj.biz.dev-auth.enabled=true` 只允许本地/测试；
- dev auth 注入固定调用方 `local-admin`（权限 `hr.employee.write` / `hr.employee.read`）。

健康检查：

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP"}
```

## create

```bash
curl -s -X POST localhost:8080/commands/hr.employee.create \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "hr.employee.create",
    "payload": {
      "name": "张三",
      "hire_date": "2026-10-01",
      "employment_type": "full_time",
      "id_number": "110101199001011234"
    },
    "idempotency_key": "quickstart-1"
  }'
```

响应（202）：

```json
{
  "command_id": "...",
  "status": "accepted",
  "event_ids": ["..."],
  "locator": "<employee-id>",
  "version": 1
}
```

记下 `locator`，后续命令用它。

## get

```bash
curl -s localhost:8080/objects/employee/<employee-id>
```

响应中 `data.employee_no` 形如 `EMP-<uuid7>`，且不包含 `id_number_hash`（writeOnly 字段不进入读投影）。

## update

```bash
curl -s -X POST localhost:8080/commands/hr.employee.update \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "hr.employee.update",
    "payload": {"employee_id": "<employee-id>", "name": "张三丰"},
    "idempotency_key": "quickstart-2"
  }'
```

版本递增为 2；再查 `GET /objects/employee/<employee-id>` 可见新姓名。

## 时点读

```bash
curl -s 'localhost:8080/objects/employee/<employee-id>?as_of=2026-10-01T12:00:00Z'
```

返回创建时的版本（`version=1`）。

## 幂等

同一 `idempotency_key` + 同一 payload 重放 → 返回原 `command_id`，不产生新版本；
同一 key + 不同 payload → 409 `EIS-409-001`。

## 错误形状

所有错误都是 `{code,message,details}`：

| 场景 | HTTP | code |
|---|---|---|
| 未认证 | 401 | `EIS-401-001` |
| 无 `hr.employee.write` | 403 | `EIS-403-001` |
| 非法 `hire_date` / 未知命令类型 | 400 | `EIS-400-001` |
| 更新不存在的员工 | 404 | `EIS-404-001` |
| 幂等键冲突 | 409 | `EIS-409-001` |

完整矩阵见 `docs/errors.md`（T1.8 引入）。

## PostgreSQL 模式

```bash
docker compose up -d
./mvnw -pl seedj-server/hr spring-boot:run \
  -Dspring-boot.run.profiles=postgres
```

PostgreSQL 模式使用真实迁移（`seedj_schema_history`）与数据库锁；配置见
`application-postgres.yml` 与 [testing.md](testing.md)。
