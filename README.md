# seedj

**seedj** 是 ByteForce 组织内的 Java 业务基础类库（base library）：业务团队以 Maven 依赖引入后，
即可获得统一的 API 契约、Web 运行时、安全接入、数据访问基线与分布式协调能力。

> 口径：项目统一称 **seedj**（groupId `cn.byteforce.seedj`）；历史“seed”指代工作目录 `jlib/seed`。

## 技术基线

| 项 | 版本 |
| --- | --- |
| JDK | 21（`maven.compiler.release=21`） |
| 构建 | Maven（`${revision}` + flatten） |
| 平台 | Spring Boot 4.1.1 / Spring Framework 7 / Spring Security 7 |
| JSON | Jackson 3（`tools.jackson`） |
| 数据 | MyBatis-Plus 3.5.17（+ jsqlparser 插件） |
| 协调 | coord-java-sdk 0.2.0（coord 家族） |

## 模块地图

| 模块 | 定位 |
| --- | --- |
| `bom` | 版本治理（Boot 平台 + 全部 seedj 模块 + 三方；只管版本不引包） |
| `codegen` | `@GenerateCodeEnum` 注解处理器（生成枚举 Codec 静态工厂） |
| `common/core` | `CodeEnum` 契约、异常体系（`BaseException`/`BizException`/`RemoteException`）、`RequestContext` |
| `common/web-core` | `ApiResponse` 统一返回体、`ResponseCode` 错误码、身份贡献 SPI |
| `common/security-core` | `CustomUserDetails` 用户详情模型 |
| `common/data-core` | 实体基类（审计 / 逻辑删除 / 多租户）、分页模型（`PageQuery`/`PageResult`） |
| `starter/core` | JSON 序列化契约（CodeEnum code 映射、Long 字符串化）——见 [ADR-0003](docs/adr/0003-json-serialization-contract.md) |
| `starter/web` | Web 运行时：全局异常处理（统一错误体）+ 请求上下文（traceId/用户/租户/MDC）——见 [ADR-0002](docs/adr/0002-error-response-contract.md) |
| `starter/resource` | JWT 资源服务：默认安全链、401/403 统一输出、JWT 身份进上下文 |
| `starter/authorization` | 授权服务：Boot 原生 OAuth2 授权服务器集成 + 组织缺省（issuer、令牌身份 claim 闭环） |
| `starter/data-mybatis` | MyBatis-Plus 基线：CodeEnum TypeHandler、审计填充、分页、多租户 |
| `starter/coord*` | coord 协调族（客户端/注册/配置/消费/观测 + 集成套件），见 [ROADMAP](starter/coord/ROADMAP.md) |

## 快速开始

### 1. 导入 BOM 与依赖

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cn.byteforce.seedj</groupId>
            <artifactId>bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

业务服务（Web + 鉴权 + 数据库）：

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-web</artifactId>
</dependency>
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-resource</artifactId>
</dependency>
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>starter-data-mybatis</artifactId>
</dependency>
```

授权服务器：`starter-authorization`（协议面向 Spring Boot 原生属性，
`spring.security.oauth2.authorizationserver.client.*`；应用提供 `JWKSource` 与 `UserDetailsService`）。

### 2. 业务代码示例

```java
// 成功：显式返回统一返回体
@GetMapping("/orders/{id}")
public ApiResponse<Order> get(@PathVariable Long id) {
    return ApiResponse.success(orderService.get(id));
}

// 失败：抛业务异常（HTTP 200 + body.code=10007，traceId 自动携带）
throw new BizException(ResponseCode.BIZ_ERROR, "库存不足");

// 上下文：当前用户 / 租户 / traceId
Optional<Long> tenantId = RequestContext.tenantId();

// 实体：审计字段自动填充
public class Order extends LogicDeletedEntity {
    private String orderNo;
}
```

## 关键配置

| 前缀 | 模块 | 说明 |
| --- | --- | --- |
| `byteforce.jackson` | starter-core | `enabled`（默认 true）；`long-as-string`（默认 true） |
| `byteforce.web` | starter-web | `enabled`；`error-handling-enabled`；`request-context-enabled`；`trace-id-header`（默认 `X-Trace-Id`）；`expose-trace-id` |
| `byteforce.security.resource` | starter-resource | `enabled`；`permit-paths`（默认放行 `/error`、健康检查）；`claims.user-id/tenant-id/username/authorities`（默认 `userId`/`tenantId`/`preferred_username`/`authorities`） |
| `byteforce.security.authorization` | starter-authorization | `enabled`；`issuer`；`claims.*`（与资源侧对齐） |
| `byteforce.mybatis` | starter-data-mybatis | `enabled`；`audit.enabled/use-request-context`；`pagination.enabled/max-limit`（默认 500）；`tenant.enabled/column/ignore-tables`；`code-enum.base-packages` |

JWT 解码器沿用 Spring Boot 原生配置：`spring.security.oauth2.resourceserver.jwt.*`。

## 构建与验证

```bash
# 全量构建 + 单测（含覆盖率门禁）
mvn -B -T1C verify

# coord 集成套件（需本机 Docker + coord 镜像）
mvn -pl starter/coord-it -am verify -Pit
```

**质量门禁**：common 域行覆盖率 ≥ 80%、starter 域 ≥ 70%（真实 class 路径匹配）；
`verify` 阶段有守卫拦截「有主源码但未执行任何测试」的模块
（确无测试需求时在模块 POM 声明 `seedj.jacoco.guard.skip=true` 并注明原因）。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ROADMAP.md](docs/ROADMAP.md) | v1 落地看板（里程碑与剩余队列） |
| [docs/adr/](docs/adr/README.md) | 架构决策记录（ADR-0001 coord / ADR-0002 错误契约 / ADR-0003 序列化契约） |
| [docs/assessment/](docs/assessment/README.md) | 阶段性评估快照（含首轮缺口评估） |
| `starter/*/README.md`、`common/*` 模块 README | 模块级用法、配置与边界 |

## 版本与发布

- 版本语义化（`MAJOR.MINOR.PATCH`），当前 `1.0.0-SNAPSHOT`；
- 发布由 CI/人工执行 `mvn deploy`（含 `-Prelease` 附加 sources/javadoc）；
- 对外 API 一经发布保持向后兼容，破坏性变更走大版本。
