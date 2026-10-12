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
| 消费方 | `byteforce.eis.authz.base-url` | `EisAuthzClient` + `EisAuthzCachingOperations`（`@Primary`，TTL 缓存）；配置服务凭据时自动携带 `Authorization: Bearer` |
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
| `byteforce.eis.authz.token-uri` | — | OAuth2 令牌端点（服务间认证；完整 URL） |
| `byteforce.eis.authz.client-id` | — | 服务间认证客户端 ID |
| `byteforce.eis.authz.client-secret` | — | 服务间认证客户端密钥 |
| `byteforce.eis.authz.scope` | — | 令牌作用域（空格分隔） |
| `byteforce.eis.authz.token-refresh-skew` | `30s` | 令牌刷新提前量 |

## 服务间认证（消费方）

判定权威的 `/resolve` `/check` 按安全基线要求认证；消费方（如 `ims-hr`）同时配置
`token-uri` / `client-id` / `client-secret`（+ 可选 `scope`）后，自动经 OAuth2
`client_credentials`（HTTP Basic 客户端认证）取令牌并携带 `Authorization: Bearer`，
令牌按 `expires_in` 缓存、过期前 `token-refresh-skew` 自动刷新；令牌端点失败一律
fail-closed（`EisAuthzRemoteException`，不得静默降级为无认证调用）。

需要复用既有服务身份/令牌管理体系（如 `OAuth2AuthorizedClientManager`）时，注册自定义
`EisAuthzTokenProvider` Bean 即可覆盖默认实现（`@ConditionalOnMissingBean`）。

> ⚠️ **安全边界**：**未配置凭据时行为与旧版一致（明文直连，不携带任何认证头）**——
> 仅适用于内网直连或由基础设施（istio mTLS 等）提供传输与身份保障的部署；
> 认证部署下必须配置上述凭据，否则每个命令都会在授权阶段被 401 拒绝。
> 凭据仅配置一部分（如只有 `client-id`）时启动即 fail-fast，避免静默退化。

## 边界

- 不承载认证（登录/JWT 由 `starter-authorization`/`starter-resource`）；
- 数据权威在域服务（IMS `iam_*`）；引擎只消费事实（`EisAuthzStore` 由应用实现）；
- coord Event 失效订阅在应用侧接线（fail-open；TTL 兜底）。
