# starter-coord-observability

`starter-coord` 一族的观测面模块（Phase 3，seedj ADR-0001）：把 SDK
`ObservabilityProvider` SPI 桥接为 **Micrometer 指标**。

## 快速开始

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-coord-observability</artifactId>
</dependency>
```

前置：classpath 存在 Micrometer 且容器中存在 `MeterRegistry`（典型：引入 Actuator）。
**无 Registry 时桥优雅回退（返回 null Bean），不影响启动**——meter 设施就绪后自动生效。
提供一个 `ObservabilityProvider` Bean 即可接管（本桥回退）。

## 指标

| 指标 | 类型 | 标签 | 说明 |
| --- | --- | --- | --- |
| `coord.rpc.calls` | counter | `operation`, `outcome=success\|error` | RPC 调用计数 |
| `coord.rpc.latency` | timer | `operation` | RPC 时延（SDK 上报纳秒） |
| `coord.watch.events` | counter | `operation` | Watch 事件计数（SPI 预留面） |
| `coord.connection.state` | gauge 族 | `state` | 连接状态：当前为 1、其余为 0（SPI 预留面） |

> SDK 0.2.0 实证：运行期实际触发 `recordRpcCall`（AgentRpcClient）；
> `recordWatchEvent` / `recordConnectionStateChange` 为 SPI 预留面——
> 上报实现已就绪，SDK 触发后自动生效。

## 配置属性（前缀 `byteforce.coord.observability`）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 桥总开关 |

## 测试

单元测试（`mvn verify`）：9 例——指标映射（counter / timer / gauge 族 / 空值兜底）、
装配与回退（无 Micrometer / 无 Registry / 用户 Provider 接管）。
集成验证见 [`starter-coord-it`](../coord-it/README.md)（真实 RPC → 指标增长）。
