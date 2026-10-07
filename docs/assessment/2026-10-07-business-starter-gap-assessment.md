# 业务服务基础 Starter 缺口评估（2026-10-07）

> **评估对象**：seedj 仓库（工作目录 `jlib/seed`，项目统一口径 **seedj**）全量 19 个模块。
> **评估视角**：以「业务服务基础 starter」的职责基准——业务团队引入后即可获得统一契约、Web 运行时、
> 安全接入、数据访问、分布式与工程治理能力——审视当前实现的覆盖度与缺口。
> **方法**：逐模块源码核对 ＋ 全量构建实测（`mvn -B -T1C verify`）＋ 关键行为 jshell 复现；
> 全部结论均给出证据（文件 / 实测输出），实测日期 **2026-10-07**。
> **范围说明**：coord 家族的能力边界与分期由 [seedj ADR-0001](../adr/0001-coord-starter-spring-cloud-replacement.md)
> 与 [starter/coord/ROADMAP.md](../../starter/coord/ROADMAP.md) 管理；本报告只盘点其对外可用性，不重复其内部计划。

## 0. 结论摘要

**总体判断：工程骨架已经成形，但能力面严重倾斜。** 19 模块可构建、BOM/插件/规则体系齐备，coord
分布式家族达到可交付级；而业务服务**开工即触达**的三块基础面——统一契约（Web 返回体/异常）、
安全接入、数据访问基线——处于**空壳或损坏**状态。**当前不具备发布 v1 业务基础 starter 的条件。**

| 级别 | 缺口 | 证据 |
| --- | --- | --- |
| **P0** | `ApiResponse` 无字段、构造器丢弃入参，实测序列化为 `{}` | 源码 ＋ 实测（§3.1） |
| **P0** | `starter-core` Jackson 定制链三段全断：未注册 → 注册即抛 → 反序列化静默失效（数字输入按 ordinal 静默错映射） | 源码 ＋ 实测（§3.2） |
| **P1** | 无异常体系与全局异常处理，错误输出无统一契约 | 全仓 grep 为空（§3.3） |
| **P1** | `starter-resource` / `starter-authorization` 为空壳（`@Enable*` 仅导入空配置类） | 源码（§3.4） |
| **P1** | 数据访问基线缺失（BaseEntity/审计/分页/逻辑删除/租户）；`data-core` 零源码 | 源码（§3.5） |
| **P1** | 请求上下文（当前用户/租户/traceId）缺失 | 全仓 grep 为空（§3.6） |
| **P1** | BOM 未纳管 `starter-data-mybatis` | `bom/pom.xml`（§3.7） |
| **P1** | 除 coord 家族外全模块零测试；JaCoCo 门禁对无测试模块静默跳过 | 构建实测（§3.8） |
| **P2** | 非 git 仓库；缺 README/LICENSE/CHANGELOG/CI/mvnw/.gitignore；文档仅覆盖 coord 家族；空 jar 模块等 | §3.9 |

（P3 储备项见 §3.10。）

## 1. 评估基准：业务服务基础 starter 能力模型

| 域 | 典型能力 | 本仓库归属 |
| --- | --- | --- |
| A 统一契约 | 统一返回体、错误码、异常体系、分页模型 | `common/core`、`common/web-core` |
| B Web 运行时 | 全局异常处理、参数校验、序列化约定、i18n、CORS、请求上下文 | `starter-core`（+ 待建 web starter） |
| C 安全 | 资源服务接入（JWT）、当前用户、401/403 统一输出、服务间凭据 | `starter-resource` / `starter-authorization` / `security-core` |
| D 数据访问 | ORM 基线、审计填充、逻辑删除、乐观锁、分页、多租户、类型处理器 | `data-core` / `starter-data-mybatis` |
| E 分布式 | 注册发现、配置中心、负载均衡、锁、限流、观测 | coord 家族（5 个能力模块 + IT 套件） |
| F 可观测性 | 日志规范、指标、追踪、健康检查 | `coord-observability`（仅 coord RPC 面）；全局基线缺失 |
| G 可靠性横切 | 幂等、缓存、重试/超时约定 | 缺失（列 P3） |
| H 工程治理 | BOM、版本策略、兼容性门禁、CI/发布、文档 | `bom` / `root`（部分具备） |
| I 测试支撑 | 单测、切片、IT 基座 | `coord-it`（仅家族内） |

## 2. 现状盘点

### 2.1 实测基线（2026-10-07）

- 构建：`mvn -B -T1C verify` → **19/19 模块 BUILD SUCCESS**（约 40s）。
- 工具链：Maven 3.9.9 / OpenJDK 21（Oracle）/ Spring Boot 4.1.1 / Jackson 3.1.5（`tools.jackson`）/
  MyBatis-Plus 3.5.17 / coord-java-sdk 0.2.0；版本均由 `bom` 统一管理。
- 测试：**单测 127 例，全部位于 coord 家族**（coord 38 / registry 36 / config 21 / client 23 /
  observability 9）；集成套件 `starter/coord-it` 14 个测试类（注解计数约 38 例，`-Pit` 才运行，
  Testcontainers 真实 Agent）。
- 版本状态：全模块 `1.0.0-SNAPSHOT`，尚未发布。

### 2.2 模块完成度矩阵

| 模块 | 定位 | 完成度 | 证据/备注 |
| --- | --- | --- | --- |
| `codegen` | `@GenerateCodeEnum` 注解处理器 | ✅ | `ResponseCodeCodec` 生成实证 |
| `common/core` | CodeEnum / CodeEnums | ⚠️ | 枚举工具语义可用；无异常体系/工具类；Javadoc 缺 |
| `common/web-core` | ApiResponse / ResponseCode | ❌ | `ApiResponse` 损坏（P0）；`ResponseCode` 可用 |
| `common/security-core` | CustomUserDetails | ⚠️ | 类本身可用，但未被任何自动配置装配 |
| `common/data-core` | 数据基础模型（规划中） | ❌ | 零源码；空 jar |
| `starter/core` | Jackson 定制 | ❌ | 未注册 ＋ 与 Jackson 3 不兼容（P0） |
| `starter/resource` | OAuth2 资源服务接入 | ❌ | 空壳（`@Enable*` 导入空类） |
| `starter/authorization` | OAuth2 授权服务接入 | ❌ | 空壳 |
| `starter/data-mybatis` | MyBatis-Plus / TypeHandler 基线 | ⚠️ | 仅 CodeEnum 注册可用；无测试；BOM 缺项 |
| `starter/coord` | 客户端/生命周期/健康 + 锁/限流 | ✅ | 38 单测 |
| `starter/coord-registry` | 服务注册/发现 | ✅ | 36 单测 |
| `starter/coord-config` | 配置导入/运行期刷新 | ✅ | 21 单测 |
| `starter/coord-client` | 客户端 LB / 声明式 HTTP | ✅ | 23 单测 |
| `starter/coord-observability` | Micrometer 观测桥 | ✅ | 9 单测 |
| `starter/coord-it` | 集成测试套件 | ✅/⚠️ | 14 类 IT（`-Pit`）；测试件模块产出空 jar |
| `bom` | 版本治理 | ⚠️ | 缺 `starter-data-mybatis` 条目 |

### 2.3 已验证可用（正面清单）

1. **codegen**：`@GenerateCodeEnum` → `ResponseCodeCodec` 自动生成（`target/generated-sources` 实证），
   生成物为 `final class` + 静态工厂，符合仓库风格。
2. **common/core**：`CodeEnum<C>` / `CodeEnums`（`of` / `ofRequired` / `toMap`）语义清晰，是全仓
   `code ↔ 枚举` 契约的基座（codegen、MyBatis、Jackson 三处联动）。
3. **coord 家族**：5 个能力模块 + 集成测试套件全部落地，README/ROADMAP/ADR 文档体系完整，
   是全仓工程化水平的参照范本。
4. **构建治理**：flatten（`${revision}`）、enforcer（版本/收敛/去重）、JaCoCo、source/javadoc
   发布插件已配置；BOM 统一管理 Boot 平台与三方版本。

## 3. 缺口明细

> 严重度口径：**P0** 现状下使用即出错/无法使用，且无替代路径；**P1** 业务服务开工即需要，
> 缺失会迫使各服务重复造轮子；**P2** 治理/一致性问题，影响长期维护成本；**P3** 增值与长期项。

### 3.1【P0】统一返回体 `ApiResponse` 损坏

**现象**：类没有任何字段；唯一构造器 `ApiResponse(int, String, T)` 为空方法体，**入参全部被丢弃**；
无任何访问器。

**实测**（Jackson 3.1.5）：

```text
ApiResponse.success(42)               -> {}
ApiResponse.error(ResponseCode.BAD_REQUEST) -> {}
```

**影响**：任何按预期使用统一返回体的业务服务，接口输出退化为 `{}`——数据、错误码、消息全部丢失；
"统一契约"承诺为零，且下游无法通过包装类补救（类本身缺陷）。

**证据**：`common/web-core/.../ApiResponse.java`；实测输出见附录 A。

**建议**：补齐 `final` 字段与访问器（仓库禁用 `record`，用 `final class` + 显式 getter 或 Lombok
`@Value`）、覆盖 `success/error` 全分支；补序列化单测。`ResponseCode`（code/label）本身可用，
可作为错误码基座一并纳入「错误响应契约」（§3.3）设计。

### 3.2【P0】`starter-core` Jackson 定制链三段全断

`CodeEnum` 的 code 契约要在 API 边界成立，依赖 Jackson 的序列化/反序列化两个方向；当前三个环节
依次断裂（全部经实测复现）：

1. **未注册（静默不生效）**：模块没有
   `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   （`resources` 目录为空，也无 `spring.factories`）；`JacksonConfig` 是普通 `@Configuration`，
   不会被业务应用扫描到。→ **引入 `starter-core` 实际拿不到任何 Jackson 定制，且无感知**。
2. **注册即抛（引入即失败）**：即使注册（手工 `@Import` 或将来补 imports 文件），现状代码在
   Jackson 3.1.5 下构建 mapper 时抛 `IllegalArgumentException`：

   ```text
   `ValueSerializer` of type `...CodeEnumSerializer` does not define valid handledType()
   -- must either register with method that takes type argument or make serializer extend 'StdSerializer'
   ```

   另需注意：把该行机械改写为 `module.addSerializer(CodeEnum.class, CodeEnumSerializer.INSTANCE)` 
   **无法通过编译**（`Class<CodeEnum>` 与 `Class<? extends CodeEnum<?>>` 不兼容）——修复必须动
   序列化器实现（继承 `StdSerializer` 或显式类型注册），不是补一行注册。
3. **反序列化静默失效（数据正确性风险）**：`addDeserializer(CodeEnum.class, …)` 按接口键注册
   **不会级联到具体枚举**。实测（枚举 `RED.code=1, BLUE.code=2`）：

   ```text
   {"c":1}    -> BLUE   （Jackson 默认把数字按 ordinal 解析：ordinal=1 是 BLUE）
   {"c":0}    -> RED    （ordinal=0；0 根本不是任何 code）
   {"c":"RED"}-> RED    （按枚举名命中，默认行为）
   ```

   即：数字输入被**静默按序号映射**，与 `CodeEnum.code` 语义相悖——若上游按 code 发数字，
   将得到错误的枚举值且无任何报错。

**修复路径（已实测通过，供实现参考）**：

- 序列化：让 `CodeEnumSerializer` 继承 `StdSerializer<CodeEnum<?>>` 后用
  `addSerializer(instance)` 注册（类型安全，推荐）；或以显式类型注册（raw 宽化转型，有
  unchecked 警告）。两者实测均输出 `{"c":1}`。
- 反序列化：`ValueDeserializerModifier#modifyEnumDeserializer` 中对
  `CodeEnum.isAssignableFrom(rawClass)` 的枚举返回 `CodeEnumDeserializer`；实测
  `{"c":1}` → RED、非法值 `{"c":404}` → `InvalidFormatException`，往返一致。

**建议**：按「修实现 + 补 auto-config 注册 + 补单测」一批完成；单测至少覆盖：code 往返、
非法 code 报错、**数字不得按 ordinal 静默命中**（护栏）、模块未开启时的优雅回退。

**顺带评审项**：`Long` 全局长整型字符串化（`LongToStringSerializer` / `LongDeserializer` 作用于
**所有** Long 字段）属于对外契约决策，建议评审是否收窄到 ID 语义字段（注解驱动），避免普通
数值字段的 wire 形态被无意改写；该对序列化器本身与 Jackson 3 兼容，但同样受「未注册」影响。

### 3.3【P1】无异常体系与全局异常处理

**现状**：全仓 `src/main` 无 `BaseException`/业务异常、无 `@RestControllerAdvice`/`@ExceptionHandler`、
无 `MessageSource`（grep 实证为空）；无任何 `spring-boot-starter-validation` 依赖。

**缺口**：
- 业务异常分类与错误码承载（参数/鉴权/业务/系统）；
- 参数校验错误统一映射（`MethodArgumentNotValidException` 等）；
- 框架错误（404/405/415）与兜底 500 的统一输出与日志口径；
- **错误响应格式本身是对外契约**，需与 `ApiResponse`、错误码（`ResponseCode`）、i18n 一并设计。

**影响**：各服务自行实现 → 错误结构/语义不一致；基础库「统一错误模型」缺位；与 §3.1 修复强耦合。

**建议**：先落「错误响应契约」ADR（涉及对外 API 与兼容策略），再实现：`common/core` 异常体系 +
web starter 全局处理器。

### 3.4【P1】安全两件套为空壳

**现状**：
- `starter-resource`：`EnableResourceServer`（`@Import` 空类）+ 空
  `ResourceServerAutoConfiguration`；依赖已含 Boot 4 的
  `spring-boot-starter-security-oauth2-resource-server`；
- `starter-authorization`：同样为空壳；依赖含 authorization-server starter；
- `security-core` 的 `CustomUserDetails` 未被任何自动配置装配（孤立类）。

**缺口**：
- 资源服务：JWT 解码/验签、issuer/audience 校验、authorities 映射、401/403 统一输出
  （与 §3.1/§3.3 的错误契约衔接）、服务间凭据（CCT）策略；
- 授权服务：clients 注册、令牌定制、与 coord 的 CCT 联动（coord ROADMAP 已明示「未实现」）。

**影响**：安全接入完全外包给业务团队各自实现，安全基线与输出一致性无从保证——对「基础
starter」而言属于核心职责缺位。

**建议**：定义 v1 范围（建议资源服务优先），立项安全基线 ADR（凭据来源、校验项、错误输出、
与 coord CCT 关系）后实现；`CustomUserDetails` 装配纳入资源服务链路。

### 3.5【P1】数据访问基线缺失

**现状**：`starter-data-mybatis` 仅提供 CodeEnum TypeHandler 的自动注册（属性 + registrar）；
`common/data-core` **零源码**（仅 pom，声明依赖 `spring-data-commons`），产出空 jar；
`starter-data-mybatis` 未入 BOM。

**缺口**（业务服务通用基线）：
- `BaseEntity`（主键/创建更新时间/操作人）与审计字段自动填充；
- 逻辑删除、乐观锁、分页模型（`PageResult`/`PageQuery`）等行为约定；
- 多租户字段与上下文联动（见 §3.6）；
- MyBatis-Plus 全局配置默认值与 SQL 日志规约；
- TypeHandler 注册的覆盖可靠性：当前默认包采用启发式（取首个 `@SpringBootApplication` 的包，
  否则回退 `cn.byteforce`），枚举定义在公共包/多入口场景会**漏注册且无告警**。

**影响**：每个业务服务重复搭建同一套数据层约定，字段命名与行为漂移，迁移与升级成本随时间放大。

**建议**：`data-core` 承载分页与基础模型（`final class`，禁用 record）；`starter-data-mybatis`
承载 MP 基线、填充器与注册失败告警；同步补 BOM 与单测。

### 3.6【P1】请求上下文横切缺失

**现状**：无当前用户访问器（`CurrentUser`）、无租户上下文、无 traceId/MDC 过滤器（grep 实证为空）。

**缺口**：ThreadLocal 上下文 + Web 过滤器/拦截器装配；与安全（§3.4）、数据（多租户）、
日志（MDC）联动；异步/线程池传播约定；traceId 响应头回传约定。

**影响**：业务服务获取当前用户/租户的方式各不相同；日志无法全链路关联，排障成本高。

**建议**：并入 web starter 首批能力，与「错误响应契约」同批判定（traceId 是否进入错误体）。

### 3.7【P1】BOM 与依赖治理

- **缺项**：`starter-data-mybatis` 未进入 `bom` 的 `dependencyManagement`（实证：`bom/pom.xml`
  未列该项），消费者需手写版本或对齐快照版本，违背「BOM 一处管版本」的仓库约定。
- **兼容性门禁缺位**：未配置 japicmp/revapi 等 API 兼容检查，与 AGENTS「对外 API 一经发布
  应保持向后兼容」的承诺不匹配（二进制/源码兼容无法被 CI 拦截）。

**建议**：补 BOM 条目；兼容性门禁在首次发布前落地（门槛与豁免策略先定 ADR/规范）。

### 3.8【P1】测试覆盖与质量门禁

**现状**：
- 单测 127 例全部在 coord 家族；`common/*`、`starter/core`、`starter/data-mybatis`、
  `starter/resource`、`starter/authorization` **零测试**；
- **JaCoCo 门禁对无测试模块静默跳过**——实测（`mvn -B -pl starter/core verify`）：

  ```text
  Skipping JaCoCo execution due to missing execution data file: .../starter/core/target/jacoco.exec
  BUILD SUCCESS
  ```

  即：没有测试的模块反而不会被覆盖率门槛拦截，门禁在「最需要它的地方」失效。

**影响**：P0/P1 缺陷（§3.1/§3.2 即是）在没有测试的情况下长期潜伏；质量信号失真。

**建议**：P0/P1 修复必须带测试（含 AutoConfiguration 加载测试）；评估 JaCoCo 空模块策略
（无 exec 数据应显式失败或告警，而非跳过）。

### 3.9【P2】工程治理与一致性

- **仓库基建**：当前目录**非 git 仓库**（`git rev-parse` 失败）；无 `.gitignore`、根 `README.md`、
  `LICENSE`（pom 已声明 Apache-2.0 但无文件）、`CHANGELOG.md`、`mvnw`、CI 配置；`.idea/` 与
  6 个 `*.iml` 位于源码树内。
- **文档覆盖不均**：全仓 8 个 README 中 6 个在 coord 家族；`common/*`、`starter/core`、
  `starter/resource`、`starter/authorization`、`starter/data-mybatis` 无模块文档；`common/*` 与
  `starter/core` 的 public 类型普遍缺 Javadoc（与 AGENTS §3 要求不符）。
- **空 jar 模块**：`data-core`（0 源码，jar 仅 META-INF 元数据）、`starter/coord-it`（仅测试
  源码，jar 为空）——发布定位需明确：建议 `coord-it` 跳过 jar/发布；`data-core` 尽快填充或
  暂移出 reactor/BOM。
- **IDE 元数据缺失**：`spring-boot-configuration-processor` 仅在 coord 家族启用；
  `starter/core`、`starter/data-mybatis` 的 `@ConfigurationProperties` 无提示元数据。
- **命名评审**：`starter-core` 描述为"核心服务 Starter"，实际仅 Jackson 定制，与 `common/core`
  语义易混；建议在补全 Web 运行时能力时同步校准命名/描述。

### 3.10【P3】储备与长期项

- **coord 家族未封装能力面**：cache/mq/event/featureflags/scheduler/workflow 的 Spring 化、
  与 authorization 的 CCT 联动、运行期刷新深度 —— 以
  [ROADMAP](../../starter/coord/ROADMAP.md) 为准（本报告不重复）。
- **可靠性工具箱**：幂等键、缓存抽象（Spring Cache 接入）、统一重试/超时约定。
- **测试支撑**：为业务服务提供测试 fixtures / 测试切片 / Testcontainers 基座。
- **可观测性基线**：日志规范（结构/字段/MDC）、OTel 追踪接入示例、指标命名约定。

## 4. 建议路线（分批）

**批次 1 — 修复类（不依赖新契约决策，建议立即）**

1. `ApiResponse` 修复 + 序列化单测（§3.1）；
2. `starter-core` Jackson：实现修复（`StdSerializer` / modifier 路径）＋ 补
   `AutoConfiguration.imports` ＋ 单测（含 ordinal 陷阱护栏）；顺带定稿 Long 字符串化策略（§3.2）；
3. `bom` 补 `starter-data-mybatis`；明确 `data-core` / `coord-it` 空 jar 处置（§3.7/§3.9）；
4. JaCoCo 空模块策略定案（§3.8）。

**批次 2 — 核心能力（先契约决策，后实现）**

1. 「错误响应契约」ADR（异常体系、错误码、校验/404/405/500 映射、i18n 与 traceId 取舍）；
2. Web starter：全局异常处理 + 请求上下文（用户/租户/traceId）（§3.3/§3.6）；
3. 安全资源服务 v1（含 401/403 统一输出）（§3.4）；
4. 数据访问基线（BaseEntity/审计/分页/多租户）＋ `data-core` 填充（§3.5）。

**批次 3 — 治理补齐**

1. git 仓库化 + 根文档 / CI / mvnw / .gitignore / CHANGELOG（§3.9）；
2. API 兼容性门禁（japicmp）、configuration-processor 全量启用、模块文档与 Javadoc 补全；
3. coord 路线图接力（Phase 3/4）与测试支撑包（§3.10）。

## 附录 A：实测记录（2026-10-07）

**环境**：macOS；Apache Maven 3.9.9；OpenJDK 21（Oracle）；Spring Boot 4.1.1；Jackson 3.1.5
（`tools.jackson`）。

1. **全量构建**：`mvn -B -T1C verify` → `BUILD SUCCESS`（19/19 模块，约 40s）。
2. **Jackson 复现**（jshell，classpath = `starter/core` 编译产物 + 依赖）关键输出：
   - `module.addSerializer(CodeEnumSerializer.INSTANCE)` →
     `IllegalArgumentException: ... does not define valid handledType() -- must either register with
     method that takes type argument or make serializer extend 'StdSerializer'`；
   - 接口键注册反序列化：`{"c":1}` → BLUE（ordinal）；`{"c":0}` → RED（ordinal）；`{"c":"RED"}` → RED（按名）；
   - 修复验证：serializer 改 `StdSerializer`（或显式类型注册）后输出 `{"c":1}`；`ValueDeserializerModifier`
     接管后 `{"c":1}` → RED、`{"c":404}` → `InvalidFormatException`；序列化/反序列化往返一致。
3. **`ApiResponse`**：`success(42)` → `{}`；`error(BAD_REQUEST)` → `{}`。
4. **JaCoCo 跳过**：`mvn -B -pl starter/core verify` →
   `Skipping JaCoCo execution due to missing execution data file`，`BUILD SUCCESS`。
5. **测试统计**：单测注解计数 coord 38 / registry 36 / config 21 / client 23 / observability 9；
   `coord-it` 14 个 `*IntegrationTest` 类。

## 附录 B：相关文档

- [seedj ADR-0001：coord-starter 承接 Spring Cloud 替代的架构约束](../adr/0001-coord-starter-spring-cloud-replacement.md)
- [starter/coord/ROADMAP.md](../../starter/coord/ROADMAP.md)
- coord 家族 README：[coord](../../starter/coord/README.md)、[registry](../../starter/coord-registry/README.md)、
  [config](../../starter/coord-config/README.md)、[client](../../starter/coord-client/README.md)、
  [observability](../../starter/coord-observability/README.md)、[it](../../starter/coord-it/README.md)

## 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-10-07 | 首版：全仓盘点 + 实测复现（P0 × 2、P1 × 6、P2/P3 若干）。 |
