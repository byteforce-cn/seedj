# 读模型

所有业务读只经 `ReadGateway`。业务代码拿不到 `TemporalStore`，也不允许自己拼范围或
脱敏；`/objects/**` 只是默认关闭的调试/内部面。

## 一次读的生命周期

```text
ScopeContextFilter（认证后）
  → ContextWriter[]（按 order 写事实）
  → ScopeContextHolder.open
ReadGateway.find/search/history
  1. ObjectModuleRegistry 找对象；未知 404
  2. Caller 必须拥有模块声明的 readPermission；否则 403
  3. DataScopeResolver[] → ScopePredicate（AND 收窄）
  4. FieldPolicyResolver[] → FULL/MASKED/HIDDEN
  5. TemporalStore 带谓词查询（先过滤再分页/计数）
  6. 字段渲染；审计 ReadAccessEvent
```

失败关闭：没有支持该对象的 resolver → 403；resolver 抛异常 → 503；
resolver 返回未知字段、字段策略引用未知字段 → 500（框架缺陷）；审计失败只记录
ERROR，不阻断读。

## 模块声明与类型化查询

```java
modules.object("employee", "hr_employee")
        .readPermission("hr.employee.read")
        .contextKey(HrScopeKeys.ORG_UNITS)
        .field("employee_no", FieldType.TEXT).required().filterable().sortable().indexed()
        .field("phone", FieldType.TEXT).privacy(Privacy.SENSITIVE).mask(Maskers.last4())
        .field("id_number_hash", FieldType.TEXT).writeOnly()
        .query(HrEmployeeView.class)
            .path("/api/hr/employees")
            .permission("hr.employee.read")
            .sort("employee_no");
```

框架为每个 `.query(...)` 生成 `GET` 端点；`ViewMapper` 默认按 snake_case ↔ camelCase
映射 record 组件，元结构（`id/version/validFrom/...`）从 `ObjectVersion` 取。
HIDDEN 字段不会出现在视图中，MASKED 字段已打码。

## 通用对象查询（默认关闭）

```text
GET /objects/{objectType}/{id}
GET /objects/{objectType}?page=1&size=50&sort=name&direction=asc&as_of=...
```

必须显式设置 `seedj.read.generic-surface=enabled`；否则端点不存在（404）。
开启后仍走同一个 `ReadGateway`，范围、字段策略与审计不降级。

保留参数：

| 参数 | 语义 |
|---|---|
| `page` | 1 起；非法值 400 |
| `size` | 1..200；0 或超过 200 返回 400 |
| `sort` | 必须是 schema 声明的 `sortable` 字段；未知字段 400 |
| `direction` | `asc` / `desc` |
| `as_of` | 业务时点（半开区间） |
| `recorded_as_of` | 系统时点（半开区间） |

除保留参数外，其余查询参数按 `equals` 过滤；未知或不可过滤字段 400。
过滤值按 `FieldType` 转换；转换失败 400。

响应：

```json
{ "items": [ ... ], "page": 1, "size": 50, "total": 123 }
```

`total` 是 scope 过滤后的总数，不受分页影响。

## 字段隐私

| 层级 | 默认 | 策略可授予 |
|---|---|---|
| `PUBLIC` / `INTERNAL` | FULL | 可收窄 |
| `SENSITIVE` | HIDDEN | `read.masked` → MASKED（有 masker） |
| `RESTRICTED` | HIDDEN | `read` → FULL |

`writeOnly` 永远不进入读投影与事件；`SENSITIVE/RESTRICTED` 字段不得 `eventVisible`。
`MASKED` 但没有 masker 时退化为 HIDDEN。

## 时点语义

- `as_of`：业务有效期；`[from, to)`，左闭右开。
- `recorded_as_of`：系统记录时间；用于审计/补偿场景。
- 两个参数可以同时出现，分别过滤不同时间轴。
- 服务器以 `Instant` 存储；`LocalDate` 字段按 UTC 日期边界转换，不接受本地时区猜测。

## 相关文档

- [命令契约](command-contract.md)
- [安全](security.md)
- 查询 OpenAPI：源码仓 `config/conformance/api/query.openapi.yaml`
