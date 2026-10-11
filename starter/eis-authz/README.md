# starter-eis-authz — EIS 授权解析（判定唯一权威）

`04-authz-contract/resolution.api.yaml` 的权威实现：`/resolve` `/check` 判定引擎
（双时间轴、代理展开、SoD prevent/warn、`reason_code`），以及消费方的远程客户端与
进程内 TTL 缓存。

**coord Policy 不接入判定路径**（EIS 评估点 13）：本引擎是唯一判定权威；
`policy` bundle / Rego 仅保留评审期工具面。

## 装配矩阵（引入即生效）

| 场景 | 提供 | 结果 |
| --- | --- | --- |
| 权威服务（IMS） | `EisAuthzStore` Bean（读域表/镜像表） | `EisAuthzEngine`（`@Primary`）+ `/resolve` `/check` 端点 |
| 消费方 | `byteforce.eis.authz.base-url` | `EisAuthzClient` + `EisAuthzCachingOperations`（`@Primary`，TTL 缓存） |
| 其他 | — | 无操作面；Web 环境引入本模块但两者皆缺将 fail-fast（未配置本能力） |

## 引擎语义（对齐 `tools/authz-resolver`）

- **双时间轴**：`as_of`（业务）必填；`recorded_as_of`（系统）可选；半开区间；
- **代理展开**：agent 经代理记录行使被代理人角色；代理范围的每个键须与绑定作用域一致；
- **作用域过滤**：`/resolve` 的 `scope`——绑定作用域含该键且值不同 → 排除；空作用域（全局）不受过滤；
- **SoD**：`prevent` → 拒绝（`reason_code=SOD-xxx`，作用域键相等或双方均缺省视为同域）；
  `warn` → 放行并在 `sod_warnings` / `warnings` 上报；
- **原因码**：`ALLOWED` / `NO_PERMISSION` / `SOD-xxx`。

## 缓存语义（评估点 8）

- 纯 best-effort 加速层、fail-open：过期/缺失一律回源，读失败直接上抛（缓存不参与正确性）；
- 失效：TTL 兜底 + `EisAuthzCachingOperations#invalidateAll()`（域事件脉冲入口）；
- 条目上界 10k（触顶清空并告警，约束键空间异常流量）。

## 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.eis.authz.enabled` | `true` | 总开关 |
| `byteforce.eis.authz.base-url` | — | 远程权威基址（消费方） |
| `byteforce.eis.authz.cache-ttl` | `30s` | 进程内缓存 TTL |
| `byteforce.eis.authz.cache-enabled` | `true` | 缓存开关 |

## 边界

- 不承载认证（登录/JWT 由 `starter-authorization`/`starter-resource`）；
- 数据权威在域服务（IMS `iam_*`）；引擎只消费事实（`EisAuthzStore` 由应用实现）；
- coord Event 失效订阅在应用侧接线（fail-open；TTL 兜底）。
