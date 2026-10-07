# common/web-core

Web 契约载体模块：**统一返回体、错误码与身份贡献 SPI**。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>web-core</artifactId>
</dependency>
```

## 能力

| 类型 | 说明 |
| --- | --- |
| `ApiResponse<T>` | 统一返回体：`code`(+0 成功)/`message`/`data`/`traceId`；`success`/`error` 静态工厂 |
| `ResponseCode` | 统一错误码（`0` 成功；`1xxxx` 业务段；`2xxxx` 系统段）；`@GenerateCodeEnum` 生成 Codec |
| `RequestContextContributor` | 身份贡献 SPI：安全模块实现，把用户/租户写入 `RequestContext`（由 starter-web 的身份过滤器调度） |

错误体与 HTTP 状态映射见 [ADR-0002](../../docs/adr/0002-error-response-contract.md)；
序列化行为（CodeEnum code 映射、Long 字符串化）由 `starter-core` 装配。

## 示例

```java
return ApiResponse.success(order);
return ApiResponse.error(ResponseCode.BAD_REQUEST);           // code=10001
return ApiResponse.error(ResponseCode.FAILED, "库存不足");     // 自定义消息
```

## 边界

- 不包含任何 Web 运行时装配（过滤器/异常处理器在 starter-web）；
- 错误码新增只能追加，不得复用（对外契约纪律）。

## 测试

`mvn -pl common/web-core -am verify`。
