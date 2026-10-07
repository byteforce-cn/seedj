# starter-coord-config

`starter-coord` 一族的配置面模块（Phase 1 / 1.5，seedj ADR-0001）：**启动期配置导入**
（`spring.config.import=coord:...`，经 ConfigData resolver / loader）与**运行期刷新**
（watch → 可变属性源 → `@ConfigurationProperties` 级 rebind）。

## 快速开始

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-coord-config</artifactId>
</dependency>
```

```yaml
spring:
  config:
    import: coord:app.orders;app.common     # 同项多前缀用 ';'；多个导入项用 ','
byteforce:
  coord:
    agent-host: 127.0.0.1
    agent-port: 19527
```

- 导入的 key 以**原文**暴露（前缀仅作过滤）；`optional:coord:` 允许源不可用时跳过 + WARN；
  非 optional 源不可用 = **启动失败**（fail-fast，禁止静默用错配置启动）。
- 引导阶段（无 ApplicationContext）：凭据仅支持静态 `auth-token`，
  `CoordAuthTokenSupplier` 等 Bean **不参与引导**。

运行期刷新自动生效：watch 变更 → 原地更新属性源 → rebind `@ConfigurationProperties`
→ 发布 `CoordConfigRefreshedEvent`（监听可获得变更 key 与失败明细）。

```java
@ConfigurationProperties(prefix = "app.orders")
public class OrderProperties { /* 字段随 coord 变更自动 rebind */ }

@EventListener
public void onRefresh(CoordConfigRefreshedEvent event) {
    log.info("配置刷新: {}", event.getChangedKeys());
}
```

## 配置属性（前缀 `byteforce.coord.config`）

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 刷新链路总开关（导入不依赖它） |
| `refresh.enabled` | `true` | 运行期刷新（关闭后 import 仅提供启动期快照） |

## 语义边界（约定 C2 / R3）

- **v1 刷新深度 = `@ConfigurationProperties` 级**（rebind = destroy + initialize，
  对齐业界 rebind 语义）；bean 级重建另立 ADR。
- **`@Value` 不参与刷新**（字段仅首次解析取值）——动态配置一律使用
  `@ConfigurationProperties`；本模块集成套件含该行为护栏。
- 删除语义（暂定，待上游核实）：watch 事件 `new_value` 缺省 → 从属性源移除该 key；
  空字符串为普通值。重订阅时按 `list()` 快照做删除收敛（覆盖断线窗口）。
- rebind 失败：失败 bean 保留旧值 + ERROR 日志，不中断其余 bean 与 watch；
  watch 断流：保留最后已知值 + WARN + 退避重订阅。
- 属性源位置由 Boot ConfigData 决定（导入文档处）；无 refresh-only 模式（必须先 import）。

## 测试

单元测试（`mvn verify`）：21 例——resolver 语法（optional / 多前缀 / 非法位置）、
loader 失败语义（可用性错误 ± optional）、属性源变更、rebind（含 `@Value` 护栏）、
刷新管理器（订阅 / 快照合并 / 幂等 / 删除 / 断流重订阅）。
集成验证见 [`starter-coord-it`](../coord-it/README.md)（真实导入 + 外部变更触发 rebind）。
