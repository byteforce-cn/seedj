<div align="center">

# seedj

**ByteForce 的 Java 业务基础类库（Base Library）**

统一契约 · Web 运行时 · 安全接入 · 数据访问基线 · 分布式协调

[![CI](https://github.com/byteforce-cn/seedj/actions/workflows/ci.yml/badge.svg)](https://github.com/byteforce-cn/seedj/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21%2B-orange.svg)](pom.xml)

</div>

> **English**：seedj is ByteForce's Java base library for business services, built on JDK 21,
> Spring Boot 4 / Spring Security 7 and Jackson 3. It standardizes the API contract (unified
> response & error codes), the web runtime (error handling, request context / traceId), JWT
> security, the MyBatis-Plus data baseline and distributed coordination — so every service
> starts from the same conventions.

## 简介

seedj 把业务服务「开工即需要」的横切能力沉淀为可复用的 Maven 构件：

- **契约先行**：统一返回体、错误码与 HTTP 状态映射、JSON wire 契约（CodeEnum code 语义、Long 字符串化）均有 ADR 与契约测试固化；
- **自动装配**：所有 starter 经 `AutoConfiguration.imports` 自动生效；逐能力 kill-switch，全部组件可被业务同类型 Bean 覆盖；
- **安全默认值**：未知枚举 code 直接报错（绝不按 ordinal 静默映射）、多租户 fail-closed、错误响应不泄露内部细节、401 保留 `WWW-Authenticate`；
- **工程质量可验证**：覆盖率硬门禁（common ≥ 80% / starter ≥ 70%）、零测试守卫、SpotBugs、japicmp 兼容门禁、Testcontainers 集成套件。

## 模块地图

| 模块 | 定位 |
| --- | --- |
| `bom` | 版本治理（Boot 平台 + 全部 seedj 模块 + 三方；只管版本不引包） |
| `codegen` | `@GenerateCodeEnum` 注解处理器（为 CodeEnum 生成 Codec 静态工厂） |
| `common/core` | `CodeEnum` 契约、异常体系（`BaseException`/`BizException`/`RemoteException`）、`RequestContext` |
| `common/web-core` | `ApiResponse` 统一返回体、`ResponseCode` 错误码、身份贡献 SPI |
| `common/security-core` | `CustomUserDetails` 用户详情模型 |
| `common/data-core` | 实体基类（审计 / 逻辑删除 / 多租户）、分页模型（`PageQuery`/`PageResult`） |
| `starter-json` | JSON 序列化契约（CodeEnum code 映射、Long 字符串化）——[ADR-0003](docs/adr/0003-json-serialization-contract.md) |
| `starter-web` | Web 运行时：全局异常处理（统一错误体）+ 请求上下文（traceId/用户/租户/MDC）——[ADR-0002](docs/adr/0002-error-response-contract.md) |
| `starter-resource` | JWT 资源服务：默认安全链、401/403 统一输出、JWT 身份进上下文 |
| `starter-authorization` | 授权服务：Boot 原生 OAuth2 授权服务器集成（issuer、令牌身份 claim 闭环） |
| `starter-data-mybatis` | MyBatis-Plus 基线：CodeEnum TypeHandler、审计填充、分页、多租户 |
| `starter-coord*` | coord 协调族（客户端/注册/配置/观测 + 集成套件），见 [ROADMAP](starter/coord/ROADMAP.md) |
| `starter-eis-core` | EIS 核心运行时：命令受理（幂等/授权钩子/规则链/事件/审计/封口同事务）、双时间轴对象存储、outbox |
| `starter-eis-audit` | EIS 审计哈希链：冻结向量逐位一致、锚行串行写入、验证器与锚定 SPI |
| `starter-eis-temporal` | EIS 时点查询：`/objects`、`/time-travel`（半开区间、敏感列读投影剔除） |
| `starter-eis-authz` | EIS 授权解析：`/resolve` `/check` 引擎（唯一判定权威）+ 远程客户端与缓存 |
| `starter-eis-it` | EIS 集成套件（Testcontainers PG16；不引入 H2） |

## 技术基线

| 项 | 版本 |
| --- | --- |
| JDK | 21（`maven.compiler.release=21`） |
| 构建 | Maven Wrapper（`./mvnw`，固定 3.9.x；`${revision}` + flatten） |
| 平台 | Spring Boot 4.1 / Spring Framework 7 / Spring Security 7 |
| JSON | Jackson 3（`tools.jackson`） |
| 数据 | MyBatis-Plus 3.5（+ jsqlparser 插件） |
| 协调 | coord-java-sdk 0.3.5（coord 家族） |

## 快速开始

### 1. 导入 BOM 与依赖

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cn.byteforce.seedj</groupId>
            <artifactId>bom</artifactId>
            <version>1.0.0</version> <!-- 尚未发布：当前快照为 1.0.0-SNAPSHOT -->
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

授权服务器：引入 `starter-authorization`，协议面沿用 Spring Boot 原生属性
（`spring.security.oauth2.authorizationserver.client.*`）；应用提供 `JWKSource` 与 `UserDetailsService`。

### 2. 业务代码示例

```java
// 成功：显式返回统一返回体
@GetMapping("/orders/{id}")
public ApiResponse<Order> get(@PathVariable Long id) {
    return ApiResponse.success(orderService.get(id));
}

// 失败：抛业务异常（HTTP 200 + body.code=10007；traceId 自动携带）
throw new BizException(ResponseCode.BIZ_ERROR, "库存不足");

// 上下文：当前用户 / 租户 / traceId
Optional<Long> tenantId = RequestContext.tenantId();

// 实体：审计字段自动填充
public class Order extends LogicDeletedEntity {
    private String orderNo;
}
```

### 3. 关键配置（速查）

| 前缀 | 模块 | 说明 |
| --- | --- | --- |
| `byteforce.jackson` | starter-json | `enabled`（默认 true）；`long-as-string`（默认 true） |
| `byteforce.web` | starter-web | `enabled`；`error-handling-enabled`；`request-context-enabled`；`trace-id-header`（默认 `X-Trace-Id`）；`expose-trace-id` |
| `byteforce.security.resource` | starter-resource | `enabled`；`permit-paths`（默认放行 `/error`、健康检查）；`claims.*`（默认 `userId`/`tenantId`/`preferred_username`/`authorities`） |
| `byteforce.security.authorization` | starter-authorization | `enabled`；`issuer`；`claims.*`（与资源侧对齐） |
| `byteforce.mybatis` | starter-data-mybatis | `enabled`；`audit.*`；`pagination.*`（max-limit 默认 500）；`tenant.*`（默认关闭，开启后 fail-closed）；`code-enum.base-packages` |
| `byteforce.eis.core` | starter-eis-core | `enabled`；对象类型与处理器/规则经 Bean/SPI 注册；授权缺省 fail-closed（无 `EisAuthorizer` Bean 即启动失败），`allow-all-authorizer`（默认 false）仅在受控环境显式开启全放行 |
| `byteforce.eis.audit` | starter-eis-audit | `enabled`；`anchor-file`（为空=日志锚定） |

JWT 解码器沿用 Spring Boot 原生配置：`spring.security.oauth2.resourceserver.jwt.*`。

## 从源码构建

```bash
# 环境：JDK 21+；无需预装 Maven（使用 wrapper）
./mvnw -B -T1C verify                            # 全量构建 + 单测 + 覆盖率门禁

./mvnw -B -Panalysis -DskipTests verify          # SpotBugs 静态分析
./mvnw -B -pl starter/coord-it -am verify -Pit   # coord 集成套件（需 Docker + coord 镜像）
./mvnw -B -pl starter/eis-it -am verify -Pit     # EIS 集成套件（需 Docker + postgres:16 镜像）
```

**质量门禁**：common 域行覆盖率 ≥ 80%、starter 域 ≥ 70%；`verify` 阶段守卫拦截
「有主源码但未执行任何测试」的模块（确无测试需求时在模块 POM 声明 `seedj.jacoco.guard.skip=true` 并注明原因）。
构建/测试日志建议写入 `/tmp/seedj-build/`，不要留在仓库内。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ROADMAP.md](docs/ROADMAP.md) | v1 落地看板（里程碑与剩余队列） |
| [docs/adr/](docs/adr/README.md) | 架构决策记录（ADR-0001 coord / ADR-0002 错误契约 / ADR-0003 序列化契约 / ADR-0004 EIS 内核族） |
| [docs/releasing.md](docs/releasing.md) | 发布手册（含 file 仓库 dry-run 演练） |
| [docs/api-compatibility.md](docs/api-compatibility.md) | API 兼容性政策与 japicmp 门禁 |
| [docs/known-limitations.md](docs/known-limitations.md) | 已知限制与安全边界（EIS 授权/端点、数据一致性、依赖版本、工具门禁） |
| [docs/assessment/](docs/assessment/README.md) | 阶段性评估快照 |
| 各模块 `README.md` | 模块级用法、配置与边界 |

## 版本与发布

- 版本语义化（`MAJOR.MINOR.PATCH`），当前 `1.0.0-SNAPSHOT`（尚未发布）；
- 对外 API 一经发布保持向后兼容，破坏性变更进入下一个 MAJOR 并提供迁移说明；
- 发布流程见 [docs/releasing.md](docs/releasing.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 参与贡献

- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 行为准则：[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- 安全漏洞报告：[SECURITY.md](SECURITY.md)（请勿公开 issue）
- 仓库自动化约定（Agent/脚本）：[AGENTS.md](AGENTS.md)

## 许可

[Apache License 2.0](LICENSE)；版权与项目标识见 [NOTICE](NOTICE)。
