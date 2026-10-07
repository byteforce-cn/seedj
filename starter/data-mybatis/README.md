# starter-data-mybatis

MyBatis-Plus 数据访问基线：**CodeEnum TypeHandler、审计填充、分页与多租户**，并配套 `data-core` 模型。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-data-mybatis</artifactId>
</dependency>
```

## 自动生效的能力

| 能力 | 说明 |
| --- | --- |
| CodeEnum TypeHandler | 扫描 `CodeEnum` 枚举并按 `code` 读写；默认扫描 `@SpringBootApplication` 基包（`AutoConfigurationPackages`），空结果告警 |
| 审计填充 | `BaseEntity` 的 `createTime/updateTime` 取填充时刻；`createBy/updateBy` 取 `RequestContext.username()`（可关闭） |
| 分页 | `PaginationInnerInterceptor`（单页上限 max-limit，默认 500）；`PageUtils.toPage/toResult` 与 `data-core` 分页模型互转 |
| 多租户（显式开启） | `TenantLineInnerInterceptor` + 上下文租户条件；**fail-closed**：上下文缺失租户即拒绝执行 |

## 配置

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `byteforce.mybatis.enabled` | `true` | 基线整体开关 |
| `byteforce.mybatis.code-enum.base-packages` | 主类基包 | TypeHandler 扫描包（多入口/公共包场景显式配置） |
| `byteforce.mybatis.audit.enabled` | `true` | 审计填充开关 |
| `byteforce.mybatis.audit.use-request-context` | `true` | 是否从上下文取操作人 |
| `byteforce.mybatis.pagination.enabled` | `true` | 分页插件开关 |
| `byteforce.mybatis.pagination.max-limit` | `500` | 单页最大条数 |
| `byteforce.mybatis.tenant.enabled` | `false` | 租户插件（显式开启） |
| `byteforce.mybatis.tenant.column` | `tenant_id` | 租户列名 |
| `byteforce.mybatis.tenant.ignore-tables` | — | 忽略租户条件的表（大小写不敏感） |

## 多租户语义（重要）

- 启用后，所有经 MyBatis-Plus 的查询/更新自动追加 `tenant_col = 当前租户`；
- 上下文缺失租户 → 抛异常拒绝（防跨租户泄漏）。后台任务需先 `RequestContext.setTenantId(...)`；
- 确有跨租户需求时用 `@InterceptorIgnore(tenantLine = "true")` 显式豁免并评审。

## 逻辑删除

`data-core` 的 `LogicDeletedEntity`（`0` 未删 / `1` 已删）由 MyBatis-Plus 自动改写
`delete/select/update` 语句，无需额外配置。

## 测试

`mvn -pl starter/data-mybatis -am verify`（扫描注册、填充语义、插件装配、租户 fail-closed、分页转换）。
