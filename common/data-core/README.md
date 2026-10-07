# common/data-core

数据模型模块：**实体基类与分页模型**（ORM 无关，注解仅依赖 `mybatis-plus-annotation`）。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>data-core</artifactId>
</dependency>
```

## 能力

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `BaseEntity` | `id` / `createTime` / `createBy` / `updateTime` / `updateBy` | 主键（雪花）+ 审计字段（自动填充由 starter-data-mybatis 完成） |
| `LogicDeletedEntity` | + `deleted` | 逻辑删除：`0` 未删 / `1` 已删（`@TableLogic`，SQL 自动改写） |
| `TenantEntity` | + `tenantId` | 多租户：插入自动填充；启用租户插件后查询自动追加租户条件 |
| `PageQuery` | `pageNum` / `pageSize` | 请求分页参数（页码 ≥1；每页上限由分页插件 max-limit 保证） |
| `PageResult<T>` | `records` / `total` / `pageNum` / `pageSize` / `pages` | 响应分页结果（不可变记录集，空值防御） |

## 示例

```java
public class Order extends LogicDeletedEntity {
    private String orderNo;
}

PageResult<Order> page = PageUtils.toResult(mapper.selectPage(PageUtils.toPage(query), wrapper));
```

## 边界

- 仅模型与注解，无运行时代码；填充/插件行为在 `starter-data-mybatis`；
- 审计操作人存**用户名字符串**（来源 `RequestContext.username()`）；
- 多租户为**显式启用**能力；后台任务需先写入租户上下文（fail-closed，详见数据模块 README）。

## 测试

`mvn -pl common/data-core -am verify`（含注解契约护栏）。
