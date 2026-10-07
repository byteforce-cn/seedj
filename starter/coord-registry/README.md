# starter-coord-registry

`starter-coord` 一族的注册面模块（Phase 1，seedj ADR-0001）：**服务自动注册 / 优雅注销**与
**发现消费（含缓存降级）**。装配前置：容器中存在 `CoordClient`（由 `starter-coord` 提供；
未引入或 `byteforce.coord.enabled=false` 时整体优雅回退）。

## 快速开始

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-coord-registry</artifactId>
</dependency>
```

```yaml
spring:
  application:
    name: orders                       # service_name 默认来源
byteforce:
  coord:
    agent-host: 127.0.0.1
    agent-port: 19527
    registry:
      host: 10.0.0.11                  # 可选：注册视角地址（NAT / 容器场景覆盖）
      metadata:
        version: 1.4.2                 # 透传附加 metadata（键见 C1）
```

服务在 web 容器就绪（`WebServerInitializedEvent` 端口）后**异步注册**，失败按
1s→30s 指数退避重试（不阻塞启动）；容器关闭时优雅注销（早于 web 容器停止与
`CoordClient.close`）。发现消费注入 `CoordDiscoveryClient`：

```java
List<CoordServiceInstance> instances = discoveryClient.getInstances("orders");
int port = instances.get(0).getPort();
```

## 配置属性（前缀 `byteforce.coord.registry`）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 模块总开关 |
| `auto-register` | `true` | 启动期自动注册 / 关闭期优雅注销 |
| `service-name` | — | 默认 `spring.application.name`；两者皆空 → 跳过 + WARN |
| `instance-id` | `{host}:{port}` | 同键重复注册 = 覆盖续约（契约幂等），重启可安全复用 |
| `host` | 本机 hostname | 注册视角地址（NAT / 容器场景覆盖） |
| `protocol` / `weight` | `http` / `100` | metadata 约定键（C1；weight v1 未参与选择） |
| `ttl` | `30s` | 租约 TTL（秒级传给契约）；心跳由 SDK `Registration` 维护 |
| `metadata.*` | — | 附加 metadata（同名键覆盖内置键） |
| `discovery.cache-enabled` | `true` | 发现结果 TTL 缓存 |
| `discovery.cache-ttl` | `30s` | 缓存 TTL：命中期不触达 Agent |
| `discovery.max-stale` | `5m` | Agent 不可达时的陈旧窗口（超窗抛错，见 C5） |

## 语义边界（约定 C1 / C5 / C6）

- **metadata 不透明**（契约建议、服务端不解析）：starter 按 C1 生成
  `address`（必填）+ `protocol` / `weight` 约定键；解析侧尽力而为（非法 JSON 不抛错）。
- **实例不含健康标记**：发现结果不做主动探活；剔除只基于租约活性（心跳停止 → 过期移除）。
- **降级**：自动注册不阻塞启动；发现消费在陈旧窗口内降级返回旧值（WARN），超窗抛错。
- **心跳失败恢复**：注销旧注册（尽力而为）后重建注册刷新租约；旧句柄随停机一并关闭。

## 测试

单元测试（mock，`mvn verify`）：36 例——identity / metadata schema、注册生命周期
（重试 / 心跳恢复 / 优雅注销 / 跳过分支）、发现缓存（命中 / 过期 / 陈旧降级 / 禁用）。
集成验证见 [`starter-coord-it`](../coord-it/README.md)（自动注册 + 优雅注销端到端）。
