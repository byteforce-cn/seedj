# starter-core

JSON 序列化契约模块：引入即生效（自动配置），把 `CodeEnum` 与 `Long` 的 wire 形态固化。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-core</artifactId>
</dependency>
```

## 能力（契约见 [ADR-0003](../../docs/adr/0003-json-serialization-contract.md)）

| 行为 | 说明 |
| --- | --- |
| `CodeEnum` 序列化 | 输出业务 `code`（非 name / ordinal） |
| `CodeEnum` 反序列化 | **严格按 code 命中**；未知 code → 400；**绝不按 ordinal 静默映射**；枚举名不接受 |
| `Long` | 默认序列化为字符串（防 JS 精度丢失）；反序列化兼容数字与字符串 |
| 注册 | 经 `AutoConfiguration.imports` 自动生效，无需手工 `@Import` |

## 配置

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.jackson.enabled` | `true` | 整体开关（关闭后回到 Jackson 原生态） |
| `byteforce.jackson.long-as-string` | `true` | 全局长整型字符串化开关 |

## 覆盖与扩展

业务自定义 `JsonMapperBuilderCustomizer` 与本模块**叠加**生效；需要覆盖某类型行为时，注册同类类型并调整
customizer 顺序（后注册优先）。**不得移除** CodeEnum 的 code 语义与 ordinal 防护（安全性质）。

## 测试

`mvn -pl starter/core -am verify`（含 ordinal 静默映射护栏与 imports 注册守卫用例）。
