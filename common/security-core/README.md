# common/security-core

安全模型模块：**用户详情（`UserDetails`）实现**，供授权服务用户体系与安全链路复用。

## 坐标

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>security-core</artifactId>
</dependency>
```

## 能力

- `CustomUserDetails`：`UserDetails` 实现，携带 `userId` / `tenantId` / `nickname` / `email` / `department`
  与权限集合；`starter-authorization` 的令牌定制器会将其身份写入访问令牌（与 `starter-resource` 解析闭环）。

## 示例

```java
return new CustomUserDetails(user.getId(), user.getUsername(), user.getPassword(),
        user.getNickname(), user.getEmail(), user.getTenantId(), user.getDepartment(), authorities);
```

## 边界

- 不含认证/授权流程实现（资源服务在 `starter-resource`，授权服务在 `starter-authorization`）；
- 账号状态默认全部有效（锁定/过期策略由业务数据与自定义 `UserDetailsService` 决定）。

## 测试

`mvn -pl common/security-core -am verify`。
