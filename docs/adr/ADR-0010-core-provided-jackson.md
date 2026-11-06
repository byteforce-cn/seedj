# ADR-0010：core 保留 provided Jackson，不引入 PayloadCodec

- 状态：已接受
- 日期：2026-11-06
- 决策者：seedj 架构整改
- 关联：`docs/adr/ADR-0008-module-topology.md`、`docs/adr/ADR-0009-package-topology-and-api-convergence.md`

## 背景

`core` 的审计链、幂等指纹、命令运行时与 `CanonicalJsonHelper` 直接使用 Jackson 3
（`tools.jackson.*`）做规范化 JSON 与哈希输入。`core/pom.xml` 以 `provided` 声明
`tools.jackson.core:jackson-databind`，运行期由 starter / jdbc / server 提供。

架构评估把这条依赖列为剩余问题，并给出两条路：

1. 在 `core` 定义 `PayloadCodec`，把 JSON 实现下沉到 starter；
2. 保留 provided Jackson，用 ADR 与形状门禁把“允许的唯一第三方线格式依赖”写死。

## 决策

选择第 2 条：**保留 provided Jackson**，并把它固化为显式规则。

- `core` 仍然零 Spring、零数据库、零监控框架；Jackson 是唯一允许的第三方线格式依赖。
- 禁止 Jackson 2（`com.fasterxml.jackson`）重新进入 `core`；审计哈希向量只认
  Jackson 3 的规范化输出。
- `CorePurityTest` 增加两条断言：`core` 的 main sources 不得 import
  `com.fasterxml.jackson`；`core/pom.xml` 的 Jackson 依赖必须是 `provided`。
- 运行期消费者必须显式提供 Jackson；缺少时应用启动/序列化失败，不得静默降级。

## 后果

- 正面：不引入一层没有实际收益的编解码抽象；审计哈希的字节级行为保持冻结；
  `core` 的依赖面用一个测试与一份 ADR 说清楚。
- 负面：`core` 不是字面意义上的“零第三方依赖”；换 JSON 实现需要改 `core`，
  不是换一个 provider。
- 后续：T4.1 的 `docs/decision-guide.md` 与 `core/README.md` 必须写明这条边界；
  T4.3 的依赖扫描把 Jackson 列入白名单。

## 备选方案

- `PayloadCodec` + starter 实现：需要把 `JsonNode` 形态的审计/幂等输入全部改成
  `Map`/`String`，并重新验证哈希向量；收益只是把同一份依赖从 `core` 挪到 starter，
  没有消除依赖，成本与风险都更大。
- 改用 JDK 自带 JSON：JDK 没有标准化 JSON API，自研解析器会引入新的正确性风险。
