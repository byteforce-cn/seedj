# starter-eis-temporal — EIS 时点查询

EIS 读路径：`/objects` 与 `/time-travel` 端点族，半开区间 `[from, to)` 语义
（`03-time-contract/temporal.rules.yaml`），敏感列读投影剔除。

## 依赖与装配

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-eis-temporal</artifactId>
</dependency>
```

前置：`starter-eis-core`（`EisObjectRepository` + 已注册对象类型 + 已物化对象表）。
引入即生效（Servlet Web 环境）；开关 `byteforce.eis.temporal.enabled=false`。

## 端点

| 端点 | 语义 |
| --- | --- |
| `GET /objects/{objectType}/{id}?as_of&recorded_as_of` | 当前 / 业务时点 / 系统时点版本（至多一个） |
| `GET /time-travel/objects/{objectType}/{id}?valid_at&recorded_at` | 双时间轴精确查询 |
| `GET /time-travel/objects/{objectType}/{id}/history?timeline&from&to` | 版本历史（valid / recorded / both；窗口半开相交过滤） |

- 无版本命中 / 未注册类型 → 404（EIS-404-001）；参数非法 → 400（EIS-400-001）；
- 响应体为契约形状（`ObjectResponse` / `ObjectVersion` / `VersionList`），
  `data` 已剔除对象类型的敏感列（如 `secret_hash`）；
- 仓库投影共享同一实现（{@link EisObjectProjection}），IMPORT 端与内部读一致。

## 边界

- 只读；写路径一律经 `starter-eis-core` 命令运行时；
- 不含余额类专用读模型（`/balances` 属域服务读模型，进度按需）。
