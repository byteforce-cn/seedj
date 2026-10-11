# ADR-0003：JSON 序列化契约（CodeEnum code 映射 / Long 字符串化）

| 项 | 值 |
| --- | --- |
| 状态 | Accepted（已采纳；2026-10-07 落地轮，决策与实现同批） |
| 日期 | 2026-10-07 |
| 决策人 | （待填写） |
| 关联 | [`starter/json`](../../starter/json/)（Jackson 定制与自动配置，原 `starter-core`）；[`common/core`](../../common/core/)（`CodeEnum`）；评估报告 §3.2；[ADR-0002](./0002-error-response-contract.md)（错误体与 traceId） |

## 背景与问题

1. **现状（评估实证，2026-10-07）**：`starter-json`（时名 `starter-core`）Jackson 定制链三段全断——未注册（不会自动生效）、注册即抛
   （Jackson 3 注册 API 差异）、反序列化静默失效；更严重的是**数字输入默认按 `ordinal` 解析**：
   实测 `{"c":1}` → ordinal=1 的枚举，与 `CodeEnum.code` 语义相悖，属静默数据错误（P0）。
2. **约束**：`CodeEnum`（code ↔ 枚举）是 codegen、Jackson、MyBatis 三处共用的全仓契约；
   wire 形态一经发布不可更改；
   前端 JS `Number` 精度上限 2^53，雪花 ID（Long）直出数字存在精度丢失风险；
   技术基线 Jackson 3.1.5（`tools.jackson`），`ValueSerializer` 无参注册、接口键反序列化注册均不可用/不级联。

## 决策

### D1 CodeEnum 序列化：输出 `code`

- 实现 `CodeEnum` 的枚举在 JSON 中输出 `code`（数字或字符串按 code 类型），**不输出** `name`/`ordinal`。

### D2 CodeEnum 反序列化：严格 code 语义

- 只接受 `code` 输入（数字与字符串均可按 code 类型读取）；
- 未知 code → `InvalidFormatException` → 统一映射 400 + `BAD_REQUEST`；
- JSON `null` → `null`；
- **绝不按 ordinal 静默命中**（守卫用例固化：ordinal 与 code 错开的枚举，输入 ordinal 值必须报错）；
- 枚举名（如 `"RED"`）不再接受——双语义（名/code）会引入歧义，v1 只保留 code 单一语义。

### D3 注册机制（Jackson 3 实证路径）

- 序列化器继承 `StdSerializer<CodeEnum<?>>` 后经 `addSerializer(INSTANCE)` 类型安全注册；
- 反序列化经 `ValueDeserializerModifier#modifyEnumDeserializer` 按**具体枚举类型**接管
  （按接口键 `addDeserializer(CodeEnum.class, …)` 不级联，实测静默失效）；
- 定制经 `META-INF/spring/…AutoConfiguration.imports` 自动注册，引入 `starter-json` 即生效，无需手工 `@Import`。

### D4 Long → String：默认开启

- 序列化：`Long`（含原生 `long`）默认输出**字符串**，防前端 JS 精度丢失；
- 反序列化：兼容数字与字符串两种输入；
- 开关：`byteforce.jackson.long-as-string=false` 关闭（关闭后为 Jackson 默认数字形态）；
- 影响面声明：作用于**全局长整型字段**，属于对外 wire 契约；如仅个别字段需字符串化，关闭全局开关并使用注解局部控制。

### D5 模块级开关与覆盖策略

- `byteforce.jackson.enabled=false` 整体关闭 seedj Jackson 定制（回到 Jackson 原生态）；
- 业务方通过自定义 `JsonMapperBuilderCustomizer` **追加**定制；需要覆盖默认行为时，注册同类类型序列化器
  并调整 customizer 顺序（后注册者优先），不得移除本 module 的 CodeEnum/ordinal 防护语义（安全性质）。

### D6 空值策略：维持 Jackson 默认

- 响应体**包含** `null` 字段（Jackson 默认）；如需裁剪，使用 Boot 原生
  `spring.jackson.default-property-inclusion`，库内不改变默认。

## 备选方案与驳回理由

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 方案 A：数字输入兼容「ordinal 或 code」 | 驳回 | 双语义歧义；错误数据静默通过，属数据正确性缺陷 |
| 方案 B：反序列化兼容枚举名（`"RED"`） | 驳回 | 与 code 语义并用引入歧义；如需排障可维护 code→枚举文档 |
| 方案 C：`Long` 默认不字符串化（opt-in） | 驳回 | 组织前端口径统一为字符串 ID；默认开启 + 开关关闭成本最低 |
| 方案 D：装配 `spring.jackson` 全量默认（Non-Null、失败开关等） | 驳回 | 超出必要边界，改动上游默认行为，影响面不可控 |

## 后果

- **正面**：code 契约在 API 边界完整成立（序列化/反序列化/DB 三处一致）；ordinal 静默错映射被守卫用例封死；
  大整数 ID 前端精度问题从机制上消除。
- **负面 / 成本**：Long 字符串化改变默认 wire 形态（数字 → 字符串），存量期望数字字段的消费者需感知；
  枚举按名输入的历史载荷将报 400（错误可见，优于静默错映射）。
- **风险**：第三方 SDK 自带 Jackson module 与本 module 对 Long 的注册冲突——按 D5 顺序策略处理并文档化。

## 未决事项

- 是否提供「按注解粒度字符串化」（如 `@JsonSerialize(using=…)` 约简）样例；
- `BigDecimal` / `LocalDateTime` 的 wire 形态是否需要统一约定（当前沿用 Jackson/Boot 默认）。

## 参考

- [评估报告 §3.2](../assessment/2026-10-07-business-starter-gap-assessment.md)
- [ADR-0002：错误响应契约](./0002-error-response-contract.md)
- 实现：[`SeedjJacksonAutoConfiguration`](../../starter/json/src/main/java/cn/byteforce/seedj/starter/json/SeedjJacksonAutoConfiguration.java)、
  [`CodeEnumDeserializerModifier`](../../starter/json/src/main/java/cn/byteforce/seedj/starter/json/jackson/CodeEnumDeserializerModifier.java)

## 修订记录

| 日期 | 修订 |
| --- | --- |
| 2026-10-07 | 初稿：CodeEnum 严格 code 语义 / ordinal 防护 / Long 字符串化默认与开关 / 注册机制（与实现同批落地） |
| 2026-10-07 | 开源前置清理：模块更名 `starter-core` → `starter-json`（坐标与引用同步） |
