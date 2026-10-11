# Changelog

本文件记录 seedj 的版本变更；格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循语义化版本（SemVer）。

## [Unreleased]

### Added

- `starter-eis-core`（新模块）：EIS 核心运行时——命令受理（幂等键指纹 / 授权钩子 / 规则链 / 事件 / 审计 / 封口 同一 PostgreSQL 事务）、双时间轴对象存储（半开区间、封口相接、行锁串行）、outbox 与 SPIs（`EisRule`/`EisCommandHandler`/`EisAuthorizer`/`EisNotifyHook`/`EisAuditAppender`/`EisOutboxDispatcher`）、`POST/GET /commands` 契约端点与 `META-INF/eis/ddl/eis-core.ddl` 运行时物化。**授权缺省 fail-closed**：容器无 `EisAuthorizer` Bean 且未显式 `byteforce.eis.core.allow-all-authorizer=true` 时应用启动失败。
- `starter-eis-audit`（新模块）：EIS 审计哈希链——九元规范化（冻结向量 `447c6e29…7410` 逐位一致）、锚行串行分配（不依赖 BIGSERIAL 缺省分配）、四类错误验证器（对齐 `verify.tool`）、锚定 SPI（文件/日志）与 `/admin/audit/*` 管理端点、`eis-audit.ddl`。
- `starter-eis-temporal`（新模块）：EIS 时点查询端点族（`/objects`、`/time-travel`），半开区间语义与敏感列读投影剔除。
- `starter-eis-authz`（新模块）：EIS 授权解析——`/resolve` `/check` 引擎（代理展开、SoD warn/prevent、`reason_code`）、远程客户端与进程内 TTL 缓存（唯一判定权威，不接入 coord Policy）。
- `starter-eis-it`（新测试套件模块）：Testcontainers PG16 集成验证（双时间轴边界 / 审计链并发 / 命令闭环，统 22 例）；不引入 H2。
- `starter-eis-core`：`EisFieldKind` 新增 `DATE`（`LocalDate`）、`BIGINT`（`Long`）、`NUMERIC`（`BigDecimal`），与 `schema-compiler` 生成物对齐（`format: date` / `type: integer` / `type: number` / 金额与数量 pattern）；对象存储的写入、读取、NULL 绑定与等值条件查询全链路支持 `DATE` / `BIGINT` / `NUMERIC(p,s)` 列（`CHAR(3)` / `CHAR(7)` 列继续以 `TEXT` 注册读写）。
- BOM：登记 `starter-eis-core/audit/temporal/authz`（`starter-eis-it` 为测试套件模块不入 BOM）。
- 文档：`docs/adr/0004-eis-kernel-starters.md`（EIS 内核族设计）。

- 开源治理文件：`CONTRIBUTING.md`（贡献指南）、`CODE_OF_CONDUCT.md`（行为准则）、`SECURITY.md`（安全政策）、GitHub Issue 表单（Bug / Feature）与 Pull Request 模板；`README.md` 重写为开源风格。
- `ResponseCode` 新增 `NOT_ACCEPTABLE(10010)` / `PAYLOAD_TOO_LARGE(10011)`；`starter-web` 错误矩阵补 **406**（Accept 协商失败）/ **413**（上传超限）映射（含 MockMvc 契约用例）。
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

- **`starter-eis-core` 幂等重放/冲突响应缺少 `created` 引用**：受理结果的主对象指针（`created`）
  此前只在首次响应返回，同键重放、异型同键冲突的 `original_result` 与 `GET /commands/{id}`
  都无法恢复对象 ID（如账号 ID），消费方只能人工对账；现将指针持久化到新增部署侧辅助表
  `eis_command_created`（需部署侧重放 `META-INF/eis/ddl/eis-core.ddl`），并在重放响应、
  冲突比对与命令终态查询中原样回传。响应字段纯增量（MINOR 兼容）。

- **业务应用被迫重复装配安全链（「已声明未生效」）**：`starter-resource` 的 `claims.authorities`
  声明了自定义权限 claim 却未被默认链消费，`starter-authorization` 的 `@ConditionalOnMissingBean(OAuth2TokenCustomizer)`
  又会在应用注册任何令牌定制器时整体退让，导致下游应用只能重写安全链/手动组合定制器。现修复为：
  - `SeedjJwtClaimsCustomizer` 增加 `claims.authorities`（默认 `authorities`）——主体为 `CustomUserDetails`
    时把权限集合注入访问令牌；
  - `starter-resource` 默认链接入 `SeedjJwtAuthenticationConverter`（`seedjJwtAuthenticationConverter` Bean
    同名可覆盖）：缺省 `scope/scp` 映射之外消费 `claims.authorities` 并合并；
  - 默认链回归「开箱即用」：应用无需重写安全链即可用 `hasAuthority(...)` 断言组织权限。
- **配置元数据全量缺失（「已声明未生效」）**：根因 = 显式 `annotationProcessorPaths` 取代
  classpath 探测，10 个模块声明的 `spring-boot-configuration-processor` 从未执行；现将 processor
  加入根 POM processor path，含 `@ConfigurationProperties` 的模块产出
  `META-INF/spring-configuration-metadata.json`（含描述与默认值）。
- **测试 flake**：`CoordConfigRefreshManagerTest.startSubscribesEachPrefixAndMergesSnapshot`
  等待 rebind 计数后直接断言事件列表，与事件发布存在时序竞争；改为等待事件本身（8 连跑稳定）。
- **测试 flake**：`CoordAutoRegistrationTest` 等待 `isRegistered()` 可能在监听器回调前返回
  （生产先落地注册句柄、再通知监听器）；改为等待监听器收到注册通知。
- **静态分析（SpotBugs）**：`EisCommandRuntime` 构造期 fail-fast 校验触发 `CT_CONSTRUCTOR_THROW`；
  `final` 化消除 Finalizer 攻击面（应用经覆盖 Bean 定制，不依赖继承）。
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

- **开源前置清理**：模块更名 `starter-core` → `starter-json`（包名 `cn.byteforce.seedj.starter.json`、目录 `starter/json`、坐标与全部引用同步）；`codegen` 收敛为仅 `common/web-core` 编译期 provided 使用，不再随全部 common 模块传递；BOM 为 `error_prone_annotations` 补充「收敛锚点」注释（gRPC 传递链 scope 收敛，实测不可删）；`AGENTS.md` 更新不可变实现口径（基线不引入 Lombok）。
- `CodeEnumTypeHandler`：构造期预建 code 索引（避免逐行线性扫描），并增加数值类型漂移容错（JDBC 返回 Long/BigDecimal 与 Integer code 数值等价时按 code 命中，未知值仍 fail-fast）。
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

- 无用依赖与死配置（2026-10-07 评估后清理）：`common/web-core` 的 `spring-web`、`common/data-core` 的 `spring-data-commons`（均为零引用的编译依赖）；全仓零使用的 Lombok 工具链配置（根 POM `annotationProcessorPaths` 与 BOM 条目）。
- `starter-core` 旧 `JacksonConfig`（由 `SeedjJacksonAutoConfiguration` 取代，功能为修复而非兼容保留；
  本版本尚未发布，无下游影响）。
- BOM 移除 `starter-coord-it`（测试套件模块，不产出可消费构件；模块内已设置
  `maven.install.skip`/`maven.deploy.skip` 禁投）。

### 未发布说明

- 当前全模块版本 `1.0.0-SNAPSHOT`，尚未发布；以上变更将进入首个正式版本 `1.0.0`。
