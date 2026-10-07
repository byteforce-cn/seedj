# seedj 落地看板（v1）

> 本文件是 seedj **基础类库 v1** 的滚动落地看板：里程碑、状态与后续队列。
> 架构约束与详细分期见 [`docs/adr/`](./adr/README.md) 与 [`starter/coord/ROADMAP.md`](../starter/coord/ROADMAP.md)（coord 家族分期）。

## 里程碑（v1）

| # | 里程碑 | 状态 | 说明 |
| --- | --- | --- | --- |
| M1 | P0 修复：`ApiResponse` / Jackson 定制链 / 契约 ADR | ✅ 2026-10-07 | ADR-0002 / ADR-0003 落地；ordinal 静默映射护栏用例固话 |
| M2 | Web 运行时：全局异常处理 + 请求上下文（traceId/用户/租户，MDC） | ✅ 2026-10-07 | 新增 `starter-web`；`RequestContext` + 过滤器 + 统一错误体 |
| M3 | 安全接入：JWT 资源服务 v1（401/403 统一输出、身份 claim 闭环） | ✅ 2026-10-07 | 重写 `starter-resource`；授权服务 v1（`starter-authorization`）承接 Boot 原生协议面 |
| M4 | 数据访问基线：实体基类（审计/逻辑删除/多租户）、分页、TypeHandler | ✅ 2026-10-07 | `data-core` 落代码；`starter-data-mybatis` 插件与填充器 |
| M5 | 工程治理：BOM 补全、JaCoCo 硬门禁、空 jar 处置、文档体系 | ✅ 2026-10-07 | 门禁修复（点分 include 真空 + 零测试守卫）；`starter-coord-it` 不发布 |
| M6 | 发布演练：`1.0.0` 首个正式版本 | 🟡 就绪 | 发布基建全量落地（LICENSE/发布手册/CI 三档/wrapper/compat/SBOM/SpotBugs；file 仓库 dry-run 实测通过）；剩余动作：运维接入私服 → Release dry-run → deploy → tag（见 [releasing.md](./releasing.md)） |

## 剩余队列（按需启动）

| 项 | 级别 | 说明 |
| --- | --- | --- |
| 远端托管与 CI 首跑 | P2 | 本地 git 已初始化；推远端后验证 Actions 三档与 coord 镜像拉取权限 |
| Javadoc 完整性 burn-down | P2 | no-comment 已归零（104→0）；剩余 156 条 doclet 完整性提示（构造器摘要 22 / 方法标签 87 / 隐式默认构造器 47），不阻断构建（评估 §3.8 口径）；复现：`./mvnw -Prelease package` |
| coord 家族接力（cache/mq/event/scheduler 等封装面） | P2 | 以 `starter/coord/ROADMAP.md` 为准 |
| 可靠性工具箱（幂等、缓存抽象、重试约定） | P3 | 评估 §3.10 储备 |
| 可观测性基线（日志规范、OTel 接入示例、指标命名） | P3 | 评估 §3.10 储备 |
| `BigDecimal`/时间类型 wire 形态统一约定 | P3 | ADR-0003 未决事项 |
| i18n（错误消息多语言） | P3 | ADR-0002 未决事项 |
| 源码许可头策略（182 文件 0 头） | P3 | LICENSE/NOTICE 已交付；是否统一加头按组织策略定 |
| 静态分析纵深（error-prone / 自定义规则） | P3 | SpotBugs 已接入；评估后续规则集 |
| JAR `Automatic-Module-Name` | P3 | JPMS 消费方稳定 `requires`；按需 |

### 本轮关闭（2026-10-07 生产化落地轮）

- **API 兼容性门禁**：`-Pcompat`（japicmp 0.26.3）落地，基线 `seedj.compat.baseline`（默认 1.0.0），
  策略与豁免见 [`api-compatibility.md`](./api-compatibility.md)；
- **git 仓库化 + 根基建**：本地 git 初始化并完成首个提交；CI 三档（GitHub Actions）、`./mvnw`、
  `LICENSE`/`NOTICE` 就位（远端推送由团队执行）；
- **发布通道**：[`releasing.md`](./releasing.md) + file 仓库 dry-run 实测（45 jar / 19 pom / 校验和完整，
  `starter-coord-it` 零泄漏）；私服接入按 settings.xml + `altDeploymentRepository` 口径（POM 无 distributionManagement）；
- **配置元数据**：根 POM processor path 修复，全量含属性模块产出 `spring-configuration-metadata.json`；
- **测试 flake**：`CoordConfigRefreshManagerTest` 时序竞争修复（8 连跑稳定）；
- **静态分析**：SpotBugs（Medium）接入 CI 主门禁，排除策略 `config/spotbugs-exclude.xml`（全仓 0 发现）。

## 口径

- 项目统一口径 **seedj**（groupId `cn.byteforce.seedj`）；历史文档中的“seed”引用仅指代工作目录 `jlib/seed`。
- 评估类文档为时间点快照，修订走新增；本看板随里程碑滚动更新。

## 修订记录

| 日期 | 修订 |
| --- | --- |
| 2026-10-07 | 初稿：M1–M5 关闭、M6 待排期、剩余队列登记 |
| 2026-10-07 | 生产化落地轮：发布工程全量落地（LICENSE/发布手册/CI/mvnw/compat/SBOM/SpotBugs/配置元数据/flake/Javadoc）；M6 置「就绪」；剩余队列重排（关闭项移入「本轮关闭」） |
