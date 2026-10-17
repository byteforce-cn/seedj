# starter-coord-it

`starter-coord` 一族的**集成套件模块**（seedj ADR-0001 / ROADMAP 测试策略 T4）：
全部 `*IntegrationTest` 在此统一承载，基于 Testcontainers 在**真实 coord 环境**
（单容器 server + agent + loopback 转发器）上验证装配与调用链路。

## 运行

```bash
# 全部集成套件（需要本机 Docker + coord 镜像）
mvn -pl starter/coord-it -am verify -Pit

# 覆盖镜像（默认 ghcr.io/byteforce-cn/coord:v0.3.5）
mvn -pl starter/coord-it -am verify -Pit -Dcoord.it.image=<image>
```

- 默认 `mvn verify`（无 `-Pit`）不运行本模块任何用例（套件只含 IT）；
  单元测试与覆盖率门槛由各上线模块自己的默认 `verify` 保障。
- 镜像 / 容器不可用时**硬失败**，不静默跳过——集成验证必须产生真实证据。
- 容器为 JVM 级单例（`CoordItContainer`），跨测试类复用；退出时 shutdown hook 停止
  （Ryuk 兜底）。

### 与 `coord dev` 的差异（容器形态说明）

`coord dev`（v0.2.0 实测；后续版本仍无容器化 `dev` 形态结论）无法满足验证诉求：不启用可选服务（Config/Cache 等 RPC 返回
`UNIMPLEMENTED`）、绑定非 loopback 被 fail-closed 拒绝。套件改用与上游 CI 一致的
server + agent 进程组合（loopback、免鉴权；服务经 `agent.toml` 显式开启），并以容器内置
perl 转发器暴露 Agent 端口（详见 `src/test/resources/coord-it/` 与 `CoordItContainer`）。
若上游后续支持 `dev` 容器化 + 服务开关，可收敛回单命令启动。

### 时序要点（踩坑记录）

- **引导阶段（ConfigData）早于 `@DynamicPropertySource` 生效时机**：需要被
  `spring.config.import=coord:` 引导读取的 Agent 地址，由 `CoordItContainer` 写入
  **系统属性**；key 预置（测试常量值）必须放在测试类**静态初始化块**（先于上下文装载）。
- 每个测试类按属性集获得独立 ApplicationContext（Spring TestContext 缓存），
  共享同一个 coord 容器；跨用例数据用 `uniqueName` 隔离。

## 套件清单（34 例）

| 套件 | 覆盖 |
| --- | --- |
| `CoordStarterIntegrationTest` | 子客户端装配 / 启动协商 / 健康检查 |
| `CoordConfigIntegrationTest` | 动态配置 put / 类型化读取 / 前缀列举 / Watch |
| `CoordRegistryIntegrationTest` | 注册 → 发现（metadata）→ 注销；注册事件 Watch |
| `CoordCacheIntegrationTest` | String / Hash / List / Set 数据面 |
| `CoordMqIntegrationTest` | Topic → publish → poll → ack → DLQ |
| `CoordWorkflowIntegrationTest` | SW DSL 部署 → 实例推进至 COMPLETED |
| `CoordTransitIntegrationTest` | 信封加解密（context 绑定）与 HMAC 用途化签名/验签（含篡改与跨用途拒绝） |
| `CoordAutoRegistrationIntegrationTest` | 自动注册（metadata schema）+ 子上下文优雅注销 |
| `CoordConfigImportIntegrationTest` | `spring.config.import=coord:` 导入与绑定、前缀过滤 |
| `CoordConfigRefreshIntegrationTest` | 外部变更 → rebind → 刷新事件；`@Value` 不刷新护栏 |
| `CoordLockServiceIntegrationTest` | 互斥 / 等待窗口 / 看门狗续约超 TTL / 失锁回调 |
| `CoordClientIntegrationTest` | 真实双实例 LB 分布 + 无实例 fail loud |
| `CoordObservabilityIntegrationTest` | 真实 RPC → `coord.rpc.calls` 增长 |
| `CoordRateLimiterIntegrationTest` | 判定形状 + AOP 放行/拒绝「不静默」语义 |

契约语义护栏（T1）与异常路径（T2）用例随套件持续累加。
