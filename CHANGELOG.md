# Changelog

本文件记录 seedj 的版本变更；格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循语义化版本（SemVer）。

## [Unreleased]

### Added

- 生产化发布工程（1.0.0 发布前）：`LICENSE`（Apache-2.0 全文）/`NOTICE`（并入各制品 `META-INF`）、
  发布手册 `docs/releasing.md`、API 兼容性政策 `docs/api-compatibility.md`、支持政策 `docs/SUPPORT.md`、
  第三方许可清单 `docs/third-party-licenses.md`、`.gitattributes`（统一 LF）。
- 构建 profile（均 opt-in）：`sign`（GPG 签名）、`compat`（japicmp 二进制兼容门禁，基线
  `-Dseedj.compat.baseline`，测试套件模块豁免）、`sbom`（CycloneDX 聚合 SBOM）、
  `analysis`（SpotBugs 静态分析，已接入 CI 主门禁；排除策略见 `config/spotbugs-exclude.xml`）。
- CI（GitHub Actions）：`ci.yml`（PR/主干门禁：verify + JaCoCo + 零测试守卫 + SpotBugs）、
  `it.yml`（夜间/手动：`-Pit` Testcontainers 集成档）、`release.yml`（手动发布：dry-run 默认，
  deploy 走受保护环境人工审批）。
- Maven Wrapper（`./mvnw`，固定 Maven 3.9.9）；SBOM 与第三方许可清单随发布归档（dry-run 实测）。

- `common/core`：异常体系（`BaseException`/`BizException`/`RemoteException`）与 `RequestContext`
  （traceId/用户/租户、MDC 键约定、异步 `wrap` 传播）；`CodeEnum`/`CodeEnums` 补 Javadoc。
- `common/web-core`：`RequestContextContributor` 身份贡献 SPI；`ResponseCode` 新增 `UNSUPPORTED_MEDIA_TYPE(10009)`。
- `starter/web`（新模块）：全局异常处理（统一错误体 / HTTP 状态映射 / traceId）、请求上下文过滤器
  （`TraceContextFilter` / `IdentityContextFilter`）、校验基线（Bean Validation）——落地 ADR-0002。
- `starter/resource`：JWT 资源服务 v1——默认安全链（无状态、permit-paths）、401/403 统一输出、
  JWT claim 身份注入 `RequestContext`；`EnableResourceServer` 保持向后兼容。
- `starter/authorization`：授权服务 v1——Boot 原生 OAuth2 授权服务器集成（issuer 缺省、`CustomUserDetails`
  身份 claim 注入访问令牌，与资源侧闭环）；client_credentials 冒烟实证。
- `common/data-core`：`BaseEntity`（审计填充）、`LogicDeletedEntity`、`TenantEntity`、`PageQuery`/`PageResult`。
- `starter/data-mybatis`：审计填充器（`AuditMetaObjectHandler`）、分页插件（max-limit）、租户插件
  （fail-closed，`byteforce.mybatis.tenant.*`）、`PageUtils`；CodeEnum 扫描默认包改用
  `AutoConfigurationPackages` 并对空扫描告警。
- BOM：补录 `starter-web`、`starter-data-mybatis`、`starter-coord-observability` 等模块与
  `mybatis-plus-annotation/core/jsqlparser`、`compile-testing` 版本管理。
- 文档：`README.md`、`CHANGELOG.md`、`docs/ROADMAP.md`、ADR-0002（错误响应契约）、ADR-0003（JSON 序列化契约）、
  9 个模块 README。

### Fixed

- **配置元数据全量缺失（「已声明未生效」）**：根因 = 显式 `annotationProcessorPaths` 取代
  classpath 探测，10 个模块声明的 `spring-boot-configuration-processor` 从未执行；现将 processor
  加入根 POM processor path，含 `@ConfigurationProperties` 的模块产出
  `META-INF/spring-configuration-metadata.json`（含描述与默认值）。
- **测试 flake**：`CoordConfigRefreshManagerTest.startSubscribesEachPrefixAndMergesSnapshot`
  等待 rebind 计数后直接断言事件列表，与事件发布存在时序竞争；改为等待事件本身（8 连跑稳定）。
- **静态分析试点发现（SpotBugs）**：`CoordRateLimitInterceptor` 双重调用 `getThis()` 的 NPE
  面（单次捕获修复）；`CustomUserDetails` 权限集合与 `PageResult` 记录集防御性不可变化。
- **Javadoc**：declared-member `no comment` 104→0（含 codegen 类/注解、coord 家族配置类访问器等）；
  生成的 `*Codec` 源码模板补齐 `@param`/`@return`。
- **`ApiResponse`（P0）**：补齐 `code/message/data/traceId` 字段与访问器，修复构造器丢弃入参导致序列化为 `{}` 的缺陷；
  序列化契约测试固化。
- **starter-core Jackson 定制链（P0）**：
  - 序列化器继承 `StdSerializer`，修复「注册即抛 `IllegalArgumentException`」；
  - 反序列化改经 `ValueDeserializerModifier#modifyEnumDeserializer` 按具体枚举类型接管，
    修复「按接口键注册静默失效」；
  - 修复**数字输入按 ordinal 静默错映射**（未知 code 现在报 400，护栏用例固化）；
  - 补 `AutoConfiguration.imports`，定制随依赖自动生效；新增 `byteforce.jackson.*` 开关。
- **JaCoCo 门禁真空**：`check-core`/`check-starter` 的 include 模式由点分包名改为 class 文件路径
  （原模式永远匹配 0 个类，门禁从未生效）；新增 verify 阶段守卫，拦截「有主源码但零测试执行」的模块。

### Changed

- `spring-boot.version` 单一维护点收敛至根 POM（bom 经父链继承，不再自持）。
- `AGENTS.md` 基线同步：构建统一 `./mvnw`；集成测试机制描述修正（`*IntegrationTest` + surefire
  `-Pit`，非 Failsafe/*IT）；§5 增补发布/兼容政策指针。
- 文档索引 `docs/README.md` 增补发布/兼容/支持/第三方许可条目。
- 统一项目口径为 **seedj**：根 POM name/description/url/scm、BOM 描述、coord 家族 POM 描述、
  文档与源码注释中的历史“seed”引用全部收敛（坐标与包名 `cn.byteforce.seedj` 本就一致）。
- 源码/测试补全：`codegen`（compile-testing 处理器测试）、`common/security-core`、`common/core`、
  `common/web-core`、`starter/core`、`starter/web`、`starter/resource`、`starter/authorization`、
  `data-core`、`starter/data-mybatis` 均新增单元测试。

### Removed

- `starter-core` 旧 `JacksonConfig`（由 `SeedjJacksonAutoConfiguration` 取代，功能为修复而非兼容保留；
  本版本尚未发布，无下游影响）。
- BOM 移除 `starter-coord-it`（测试套件模块，不产出可消费构件；模块内已设置
  `maven.install.skip`/`maven.deploy.skip` 禁投）。

### 未发布说明

- 当前全模块版本 `1.0.0-SNAPSHOT`，尚未发布；以上变更将进入首个正式版本 `1.0.0`。
