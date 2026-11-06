# 读模型

读侧分两类：**通用对象查询**与**类型化查询**。先用通用查询；只有当投影需要跨对象
组合或稳定 DTO 时才加类型化端点。

## 通用对象查询

```text
GET /objects/{objectType}/{id}
GET /objects/{objectType}?page=1&size=50&sort=name&direction=asc&as_of=...
```

保留参数：

| 参数 | 语义 |
|---|---|
| `page` | 1 起；非法值 400 |
| `size` | 1..200；0 或超过 200 返回 400 |
| `sort` | 必须是 schema 声明的可排序字段；未知字段 400 |
| `direction` | `asc` / `desc` |
| `as_of` | 业务时点（含） |
| `recorded_as_of` | 系统时点（含） |

除保留参数外，其余查询参数按 `equals` 过滤；未知 filter 不静默忽略，返回 400。
过滤值按 `ObjectSchema` 的 `FieldType` 强制转换（日期、UUID、布尔、数字）；转换失败 400。

响应：

```json
{ "items": [ ... ], "page": 1, "size": 50, "total": 123 }
```

`writeOnly` 字段（如证件号）在投影中移除；派生字段可以读，但不能写。

## 类型化查询

HR 样板提供：

```text
GET /api/hr/employees?page=1&size=50
GET /api/hr/employees/{employeeId}
```

类型化查询只读 `ObjectSchema` 的 `readable()` 投影，不直接拼 SQL；写字段不出现。
分页与排序规则与通用查询一致，避免第二套语义。

## 时点语义

- `as_of`：业务有效期；`[from, to)`，左闭右开。
- `recorded_as_of`：系统记录时间；用于审计/补偿场景。
- 两个参数可以同时出现，分别过滤不同时间轴。
- 服务器以 `Instant` 存储；`LocalDate` 字段按 UTC 日期边界转换，不接受本地时区猜测。

## 相关文档

- [命令契约](command-contract.md)
- [决策指南](decision-guide.md)
- [查询 OpenAPI](conformance/api/query.openapi.yaml)
