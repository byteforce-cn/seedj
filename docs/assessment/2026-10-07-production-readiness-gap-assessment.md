# seedj 生产化（Production Readiness）缺口评估（2026-10-07）

> **评估对象**：seedj 仓库（工作目录 `jlib/seed`，统一口径 **seedj**）全量 20 个模块（聚合与制品模块合计），
> 快照版本 `1.0.0-SNAPSHOT`（尚未发布）。
> **评估视角**：**生产化**——类库从「功能可用」走向「生产可用」所需的发布工程、质量与兼容治理、
> 供应链合规、工程基建与运行时生产就绪能力。与《[业务服务基础 Starter 缺口评估](./2026-10-07-business-starter-gap-assessment.md)》
> 互补：前者评「业务能力面缺什么」，本报告评「工程化 / 生产侧缺什么」。
> **方法**：全量构建与发布演练实测（`mvn -B -T1C verify`、`-Prelease package` 制品演练、
> `-Pit` 集成复跑与 flake 复现）+ 仓库 / POM / 插件静态审计；全部结论给出证据，实测日期 **2026-10-07**。
> **口径**：生产化 = ① **可发布**（制品 / 通道 / 签名 / 追溯）② **可升级**（版本与兼容性治理）
> ③ **可信**（质量门 / 合规 / 供应链）④ **可运维**（观测 / 默认值 / 失败语义）⑤ **可持续**（CI / 仓库 / 文档基建）。

## 0. 结论摘要

**总体判断：功能面已具备 v1 形态（里程碑 M1–M5 关闭），工程底座好于同类项目，但「发布与治理」支柱整体缺位——
当前不具备按正式流程发布、也不具备被业务「正式依赖」的生产化条件。**

**已经成立的部分（本次实测）**：20/20 模块 `verify` 全绿；313 个单测与 33 个集成用例全绿；覆盖率实测
**87.0%**（行，1634/1878）；硬覆盖率门禁 + 「零测试守卫」；`-Prelease` 演练可产出 15 source + 15 javadoc 制品；
BOM / enforcer / flatten 治理与 ADR / 评估 / CHANGELOG 文档体系完整；10 个 starter 均带
`AutoConfiguration.imports`；代码卫生（无 `System.out` / `printStackTrace` / TODO）。

**不成立的部分（缺口）**：

| 级别 | 缺口 | 关键证据 |
| --- | --- | --- |
| **P0** | 仓库未版本化（非 git 仓库）：无历史 / 评审 / 追溯，发布与 CI 均无从挂载 | `git rev-parse` 实测非仓库（§3.1） |
| **P0** | 发布通道不可执行：无 `distributionManagement` / 签名 / staging / 发布手册；现状 `mvn deploy` 无目标仓库可投放 | 根 POM 审计（§3.2） |
| **P1** | 无 CI/CD：313 单测 + 33 IT + 发布演练全部依赖人工本地；IT 默认排除、`-Pit` 手工触发 | 无任何 CI 配置文件（§3.3） |
| **P1** | 许可合规实体缺位：POM 已声明 Apache-2.0，但无 `LICENSE` / `NOTICE` 文件、182 个源文件 0 许可头 | `ls` 实测 + grep 0 命中（§3.4） |
| **P1** | 配置元数据全量缺失（「已声明未生效」）：10 个模块声明 `configuration-processor`，全仓生成 **0 个** `spring-configuration-metadata.json` | `find` 0 命中 + 根因定位（§3.5） |
| **P1** | API 兼容性门禁缺失：无 japicmp / revapi；1.0.0 发布后无法守护向后兼容承诺 | POM 审计（§3.6；ROADMAP 已登记 P1） |
| **P1** | 测试稳定性无治理：实测 1 例间歇性失败（flake），无重试 / 隔离 / CI 复跑机制 | 实测复现（§3.7） |
| **P2** | 质量纵深：无静态分析接入（spotbugs / pmd 缺席；error-prone 仅 BOM 版本管理）；Javadoc 267 条 `no comment` 警告与 AGENTS 门规漂移 | §3.8 |
| **P2** | 供应链安全：无 CVE 扫描 / SBOM / 依赖分析产物 | §3.9 |
| **P2** | 文档与支持面：无发布手册 / 升级指南实践 / 支持政策；javadoc 站点未发布；AGENTS.md 的 IT 机制描述与实际不符 | §3.10 |
| **P2** | 仓库工程细节：无 `mvnw` / toolchains；`.idea/`、`*.iml`、`.flattened-pom.xml` 在工作树；POM SCM 元数据指向未落实的远端；JAR 无 `Automatic-Module-Name` | §3.11 |

（P3 储备项见 §3.12。）

## 1. 评估基准：生产化能力模型

| 域 | 关键能力 | 本仓库归属 |
| --- | --- | --- |
| A 发布工程 | 坐标 / 元数据、source / javadoc 制品、签名、制品仓库、staging、发布手册、版本标签 | root / `bom`（部分） |
| B 版本与兼容治理 | SemVer、CHANGELOG、兼容性门禁、弃用策略、升级指南、支持矩阵 | `docs/`、root（部分） |
| C CI/CD 质量门 | 流水线、单测 / IT 出口、覆盖率门禁、发布自动化 | 缺失 |
| D 供应链与合规 | LICENSE / NOTICE、许可清单、CVE 扫描、SBOM、版本收敛 | `bom` / enforcer（部分） |
| E 仓库基建 | git、分支 / 评审、`mvnw`、toolchains、忽略策略 | 部分就绪（`.gitignore`） |
| F 运行时生产就绪 | 自动配置注册、配置元数据、安全默认、失败语义、优雅停机、健康检查、观测 | `starter/*`（大体具备，§2.3） |
| G 文档与支持 | README、模块文档、ADR、评估、使用指南、配置参考、发布说明 | 大体具备；发布 / 升级面缺 |
| H 质量纵深 | 覆盖率、静态分析、风控（flake / 性能） | 覆盖率硬门禁；其余缺 |

## 2. 现状盘点（2026-10-07 实测基线）

### 2.1 实测数据

| 项 | 结果 | 说明 |
| --- | --- | --- |
| 全量构建 | **20/20 SUCCESS**（约 1:21 min，`-T1C`） | Maven 3.9.9 / OpenJDK 21（Oracle）/ macOS x86_64 |
| 单元测试 | **313 例 / 76 类，0 失败** | 模块内 surefire；分布 coord 38 / registry 36 / client 23 / config 21 / observability 9 / 其余各模块 |
| 集成测试 | **33 例 / 14 类，0 失败**（`-Pit`，Testcontainers 真实容器） | 默认 `verify` 不含；容器 / 镜像不可用硬失败、不静默跳过 |
| 覆盖率（汇总） | **87.0%** 行（1634/1878） | 各模块 `jacoco.csv` 汇总；门禁 common ≥80% / starter ≥70% + 零测试守卫 |
| 发布演练 | `-Prelease package` SUCCESS：**15 source + 15 javadoc** 制品 | 仅本地 package；无签名 / 无仓库投放（§3.2） |
| Javadoc | **267 条 `warning: no comment`**（构建不失败） | 与 AGENTS「public / protected 均须 Javadoc」存在漂移（§3.8） |
| 验证环境 | 仅本机 macOS x86_64 + JDK 21 | 无 CI、无多环境矩阵（§3.3 / §3.12） |

### 2.2 生产化能力矩阵

| 域 | 现状 | 判定 |
| --- | --- | --- |
| A 发布工程 | source / javadoc 制品可产出；无签名、无制品仓库、无 staging、无发布手册 | ❌ §3.2 |
| B 版本与兼容 | SemVer + CHANGELOG（Keep a Changelog）就绪；无兼容性门禁、无升级指南实践 | ⚠️ §3.6 |
| C CI/CD | 无 | ❌ §3.3 |
| D 供应链与合规 | BOM / enforcer 版本收敛；无 LICENSE / NOTICE、无扫描、无 SBOM | ❌ §3.4 / §3.9 |
| E 仓库基建 | `.gitignore` 就绪；非 git、无 `mvnw` / toolchains | ❌ §3.1 / §3.11 |
| F 运行时生产就绪 | 自动配置 / 健康检查 / 优雅停机 / 默认安全大体具备；配置元数据缺失 | ⚠️ §3.5 |
| G 文档与支持 | README ×15 / ADR ×3 / 评估 / CHANGELOG；发布与升级面缺 | ⚠️ §3.10 |
| H 质量纵深 | 覆盖率硬门禁 + 守卫；flake 无治理、无静态分析 | ⚠️ §3.7 / §3.8 |

### 2.3 已验证可用（正面清单）

1. **质量门真实有效（2026-10-07 修复后）**：JaCoCo `includes` 使用 class 路径模式、`check-core`/`check-starter`
   双档门槛 + antrun「零测试守卫」，本次全量构建下全部生效。
2. **发布制品可产出**：`-Prelease package` 实测 15 个 source jar + 15 个 javadoc jar（与 15 个可消费构件一一对应）。
3. **自动配置注册面完整**：10 个 starter 模块均含 `AutoConfiguration.imports`；`starter/coord-config` 另有
   `spring.factories`（ConfigData 机制所需）。
4. **依赖卫生规范**：可选能力用 `optional=true` 声明（`spring-boot-health` / micrometer / configuration-processor）；
   `starter-coord-it` 设置 `maven.install.skip` / `maven.deploy.skip` 双重禁投（测试件不入私服）。
5. **代码与运行卫生**：main 源码无 `System.out` / `printStackTrace` / TODO-FIXME；请求上下文过滤器在 `finally`
   中清理 MDC（`TraceContextFilter` / `IdentityContextFilter`），无线程串扰风险。
6. **文档体系**：CHANGELOG（Keep a Changelog + SemVer 声明）、15 个模块 README、ADR-0001~0003、评估体系、
   落地看板 `docs/ROADMAP.md`。

## 3. 缺口明细

> 严重度口径（面向生产化）：**P0** 阻塞「正式发布」的根基缺失，不解决则生产化无从谈起；
> **P1** 首次发布 / 首个接入方即需要（发布前最低清单）；**P2** 治理纵深与一致性（长期维护成本 / 体验 / 合规）；
> **P3** 增值与演进项。

### 3.1【P0】仓库未版本化（非 git 仓库）

**现象**：工作目录不是 git 仓库（`git rev-parse --is-inside-work-tree` → `fatal: not a git repository`），
根目录无 `.git/`；但仓库内已含 `.gitignore`、AGENTS.md 已定义分支 / 提交规范与「禁止直推 main」纪律，
CHANGELOG 有 `[Unreleased]` 块——**规范先行、载体缺失**。

**影响**：
- 版本历史 / 变更追溯 / 代码评审 / 权限控制全部无从承载；发布无法从 tag 重建（provenance 断链）；
- CI、兼容性门禁、发布流水线（§3.3 / §3.6）均以 git 为挂载点，无法先于本项落地；
- POM 的 `url` / `scm` 元数据指向 `github.com/byteforce-cn/seedj`，与「尚未版本化」的事实不一致。

**证据**：`git rev-parse` 实测；根目录 `ls -la`（无 `.git`，有 `.gitignore`）；根 POM `<scm>`。

**建议**：`git init` + 首推远端（决定真实托管位置并与 POM 元数据对齐；`.gitignore` 已覆盖
`target/` / `.idea/` / `*.iml` / `.flattened-pom.xml`，可直接使用）；补首个 tag 策略（见 §3.2 发布手册）。

### 3.2【P0】发布通道不可执行

**现象**：根 POM 全文审计——无 `<distributionManagement>`、无 `maven-gpg-plugin`、无
`nexus-staging-maven-plugin` / 等价发布插件；`release` profile 仅做 source / javadoc 制品 attach。
按现状执行 `mvn deploy` **无法定位目标仓库**（除非人工 `-DaltDeploymentRepository` 临时绕过）。
另：AGENTS.md 明确「Agent 不得自动 deploy」「不修改私服地址 / 签名密钥」，属人工 / CI 职责——
但当前**没有任何发布决策记录与操作手册**。

**已具备**（演练实测）：`mvn -B -Prelease -DskipTests package` → BUILD SUCCESS，15 source + 15 javadoc jar；
`${revision}` + flatten 机制工作正常，制品坐标可用。

**影响**：里程碑 M6（首个正式版本 `1.0.0`）无可执行的发布路径；下游业务拿不到正式制品，
只能依赖本地 `install` 的 SNAPSHOT——不存在「可信来源」的正式依赖方式。

**建议（按序）**：
1. 决策并记录：制品仓库接入方式（settings.xml 侧定义 mirror / repo 由运维掌握，或 POM 侧
   `distributionManagement` 引用 repo id）、是否要求 GPG 签名、staging / 审批口径；
2. 新增 `docs/releasing.md` 发布手册（版本号变更 → CHANGELOG 定稿 → tag → 构建 → 签名 →
   投放 → 验证 → 回滚）；
3. 以**临时 file 仓库**做一次全链路 dry-run（`deploy` 到 `/tmp` 仓库），验证签名与制品完整性，
   再接入真实私服。

### 3.3【P1】无 CI/CD 流水线

**现象**：根目录无 `.github/`、无 `.gitlab-ci.yml`、无 `Jenkinsfile`（实测均不存在）。
313 单测 + 33 集成用例 + 覆盖率门禁 + 发布演练全部依赖开发者本机人工执行；
集成套件默认被 surefire 排除，仅 `-Pit` 手工触发（且需本机 Docker + 预拉镜像）。

**影响**：
- 质量门只在「有人跑」时成立：合并前无强制验证，回归只能靠自觉；
- 本次评估即遇到「单测间歇失败导致 reactor 提前中断、IT 未执行」的链路（§3.7）——在 CI 中可被
  分层执行策略消解（允许 IT 在单测失败独立反馈），在人工链路中则直接丢失 IT 信号；
- 发布无自动化载体（§3.2 的 CI 侧不可承接），AGENTS「发布由 CI / 人工执行」的后半句恰好被现状签中。

**建议**：待 git 化后建立三档流水线：
1. **PR 门**：`mvn -B -T1C verify`（含覆盖率门禁 + 零测试守卫）；
2. **主干 / 夜间**：追加 `-Pit` 集成档（runner 需支持 Docker / Testcontainers）与（后续）兼容性门禁（§3.6）；
3. **Release**：dry-run（含签名）→ 人工审批 → `deploy`（遵守 AGENTS：Agent 不自动执行 deploy）。

### 3.4【P1】许可合规实体缺位

**现象**：
- 根 POM `<licenses>` 声明 **Apache License, Version 2.0**，但仓库内**无 `LICENSE` 文件、无 `NOTICE` 文件**（`ls` 实测不存在）；
- 源文件许可头：182 个 `.java`（common / starter / codegen 下 main + test）中 **0 个**含许可头（grep 0 命中）；
- 无第三方依赖许可清单（transitive 含 coord-java-sdk、MyBatis-Plus、Jackson 3、Boot 平台等）。

**影响**：声明的许可与实际交付不一致——下游按 POM 元数据理解授权，但无许可文本可依；
对外分发 / 开源治理场景为发布阻断项。AGENTS.md 的「不修改 LICENSE」规则当前缺乏作用对象。

**建议**：补 `LICENSE`（Apache-2.0 全文）与 `NOTICE`（如有需归属组件）；用构建侧（如
`license-maven-plugin` / `dependency:list` 人工整理）生成**第三方许可清单**存档 `docs/` 或随发布物；
是否统一加源文件许可头由组织策略拍板（现状 0 头，与「补全」不是同一承诺，可分批）。

### 3.5【P1】配置元数据全量缺失（「已声明未生效」）

**现象**：10 个 starter 模块在 POM 中声明 `spring-boot-configuration-processor`（`optional=true`），
部分还带注释「生成 `spring-configuration-metadata.json`，提供 IDE 属性提示」；
但实测 **全仓 0 个 `spring-configuration-metadata.json` / `additional-spring-configuration-metadata.json`**
（`find` 0 命中；`starter/web/target/classes/META-INF/` 下仅 `spring/` 目录），即声明的能力从未生效。

**根因（已定位）**：根 POM `maven-compiler-plugin` 配置了显式 `annotationProcessorPaths`（仅
lombok + codegen）。显式 processor path **取代** classpath 探测——声明在依赖里的
configuration-processor 不会被 javac 发现，于是注解处理阶段从不执行。

**影响面**：约 13 个配置属性载体类（`SeedjWebProperties` / `SeedjJacksonProperties` /
`ResourceServerProperties` / `AuthorizationServerProperties` / `MybatisBaselineProperties` /
`CodeEnumMybatisProperties` / coord 家族 7 个 Properties）对应的全部 `byteforce.*` 前缀：
IDE 无属性补全、无默认值提示、无法自动生成配置参考文档——对一个「开箱即用」定位的基础类库而言，
这是对使用体验与可运维性的系统性削弱。

**建议**：将 `spring-boot-configuration-processor` 加入（根或各模块）`annotationProcessorPaths`；
验证元数据随 jar 打包；对重点前缀用 `additional-spring-configuration-metadata.json` 补 description / default；
把「元数据存在性」纳入构建校验（小成本、高收益）。

### 3.6【P1】API 兼容性门禁缺失

**现象**：全仓无 japicmp / revapi 配置（grep 0 命中）；`docs/ROADMAP.md` 已将
「API 兼容性门禁（japicmp/revapi）P1 首次发布前落地」登记为剩余队列。

**影响**：AGENTS.md 承诺「对外 API 一经发布保持向后兼容，破坏性变更走大版本」——该承诺当前无机械门禁；
1.0.0 发布后，任何后续变更（含 minor 内意外删改 public 成员）都不会被构建拦截。

**建议**：1.0.0 自身即作为**基线**（发布时归档制品或首个 tag）；CI 中对
`1.0.1+` 引入 japicmp（mode 对比基线、severity 阈值、白名单流程）；豁免策略先行成文。

### 3.7【P1】测试稳定性无治理（flake 实证）

**现象（本次亲历复现）**：一次 `mvn -B -pl starter/coord-it -am verify -Pit` 全链路运行中，
`CoordConfigRefreshManagerTest.startSubscribesEachPrefixAndMergesSnapshot` 失败：

```text
Expected size: 1 but was: 0 in:
[CoordConfigRefreshedEvent[source=CoordConfigRefreshManager@...]]
```

失败前日志含 `配置 watch 终止（prefix=app.orders），将重建订阅: stream reset`——为 watch 重建
与事件收集的**时序竞争**。随后对 `starter/coord-config` 连跑 3 次，21/21 全绿 → **间歇性（flake）**，
非确定性缺陷。

**影响**：测试套件是当前唯一的发布门（§2.3-1），flake 会造成随机红灯 → 阻塞发布、
诱发「跳过测试」的坏习惯；且本次首跑因 fail-fast 直接中断 reactor、IT 未执行——测试信号分层缺失。

**建议**：优先定位测试自身时序（显式同步 / Awaitility / 可控时钟注入，而非简单加 sleep）；
CI 中引入复跑检测与隔离（quarantine）策略并成文；保留失败现场日志。**不建议**在根因未明前直接加重试掩盖。

### 3.8【P2】质量纵深：静态分析与 Javadoc 门规

**静态分析缺席**：无 spotbugs / pmd / checkstyle / error-prone 接入；`error_prone_annotations` 仅存在于
BOM 版本管理（未接线到编译链）。当前质量信号只剩「测试 + 覆盖率」。

**Javadoc 漂移**：`-Prelease package` 实测 **267 条 `warning: no comment`**（集中在 coord 家族、
`security-core`、`starter-core`、`data-mybatis` 等；构建不失败，无 `failOnWarnings` 策略）。
AGENTS.md §3 要求「所有 public / protected 类型、方法、字段必须有 Javadoc」——执行面与规约存在系统性差距。

**建议**：静态分析先上低噪声工具（spotbugs 或按组织规范），并注意与 `annotationProcessorPaths`
的兼容（error-prone 亦走 processor path，需与 lombok / codegen 共置）；Javadoc 定「阈值 + burn-down」
策略（短期不阻断构建、发布前定量收敛，或按模块设 failOnWarnings 白名单）。

### 3.9【P2】供应链安全与合规纵深

**现象**：无 CVE / 漏洞扫描（OWASP dependency-check、组织安全平台、Dependabot 类机器人均无）；
无 SBOM 产物（如 CycloneDX）；无 `dependency:analyze` 类依赖健康检查。
现有底座：BOM 统一版本 + enforcer `dependencyConvergence` / `banDuplicatePomDependencyVersions`（收敛与去重已覆盖）。

**影响**：Boot 4.1.1 / Jackson 3 / coord-java-sdk 0.2.0 / MyBatis-Plus 3.5.17 等版本升级与
漏洞响应依赖人工发现；无法向安全审计提供「此版本包含哪些组件」的制品级清单。

**建议**：接入漏洞扫描（含 CI 定时任务）；生成并归档 SBOM；版本升级走 Renovate / Dependabot 类 PR 流程
（当前为手工改 BOM）。

### 3.10【P2】文档与支持面

**缺失**：无发布手册（`docs/releasing.md`）、无升级指南实践（AGENTS 约定 `docs/upgrade-x.y.md`，尚无文件——
首个大版本前按需）、无支持政策（受支持版本 / EOL 口径）；javadoc 站点未发布（仅有 jar 制品能力）。

**漂移（需修正）**：AGENTS.md §4 描述「集成测试 `*IT.java`，通过 Maven Failsafe 运行」——
实际为 `*IntegrationTest.java`（15 个文件，14 个用例类）经 surefire `-Pit` 双档运行；
仓库内 0 个 `*IT.java` 文件。实现已固化且工作正常，建议修正文档描述（或明确评审后反向对齐，二选一并留痕）。

**建议**：随 §3.2 发布决策补齐 `docs/releasing.md`；修正 AGENTS.md 的 IT 机制描述；javadoc 站点按需
（内网静态托管 / GitLab Pages 类）列入发布演练；支持政策与首版一并定稿。

### 3.11【P2】仓库工程细节

| 项 | 现状 | 影响 / 建议 |
| --- | --- | --- |
| Maven wrapper | 无 `mvnw` / `.mvn/`；enforcer 仅约束 `[3.9.0,)` 下限 | 构建工具版本随开发者环境漂移；建议 wrapper 固定 3.9.x |
| Toolchains | 无 `toolchains.xml` 模板；enforcer `requireJavaVersion [21,)` 无上限 | 构建 JDK 来源不可控；CI 化时一并固定（JDK 21） |
| 工作树残留 | `.idea/`、6 个 `*.iml`、根 `.flattened-pom.xml` 在树内 | 均已被 `.gitignore` 覆盖；git 化时建议物理清理，保持工作区整洁 |
| SCM 元数据 | POM `url` / `scm` 指向 `github.com/byteforce-cn/seedj` | 与「非 git 仓库」现状不符；git 化时与实际托管对齐（§3.1） |
| JAR 清单 | 仅 `Manifest-Version` / `Created-By` / `Build-Jdk-Spec` | 无 `Automatic-Module-Name`（JPMS 消费方无法稳定 `requires`）与 `Implementation-Version`；如无模块化需求可列 P3 |
| 编辑器基线 | 无 `.editorconfig`（AGENTS 目前为「若存在以其为准」） | 可选：多模块统一格式基线 |

### 3.12【P3】储备项（按需启动）

- **可观测性基线**：OTel SDK 接入示例、指标命名规范、日志格式约定（`docs/ROADMAP.md` 已登记；当前仅 coord 观测桥）。
- **性能 / 容量基线**：starter 引入的启动开销、过滤器链路开销基准；coord SDK 契约数值已有文档兜底。
- **验证矩阵扩展**：Linux runner、JDK patch 版本、容器运行时差异（CI 落地后自然获得）。
- **发布节奏与快照治理**：SNAPSHOT 投放策略（现全模块 `1.0.0-SNAPSHOT`）、版本发布窗口。
- **供应链纵深**：制品签名验证的消费端指引、依赖锁定策略。

## 4. 发布前最低行动清单（建议，按依赖序）

1. **git 化**并正名远端，对齐 POM `url` / `scm`（§3.1）——所有后续项的前置。
2. **发布决策**（仓库接入 / 签名 / staging 口径）+ `docs/releasing.md` + 临时 file 仓库 dry-run（§3.2）。
3. **LICENSE / NOTICE 落地** + 第三方许可清单（§3.4）。
4. **CI 三档流水线**（PR 门 / 夜间含 IT / Release dry-run）（§3.3）。
5. **配置元数据修复**（§3.5）——成本极低、收益直接，可与任何一轮合并。
6. **兼容性基线固化**：发布 1.0.0 即建立 japicmp 基线并接入 CI（§3.6）。
7. **flake 治理**（§3.7）：定位时序根因 + CI 复跑检测机制。
8. 完成以上后执行 **M6 发布演练**（staging / 签名 / 投放 / 回滚全链路），放行 1.0.0。

> 本清单与 `docs/ROADMAP.md` 剩余队列衔接：完成后可将其裁剪为「已关闭」并进入版本发布节奏。

## 附录 A：本次实测命令与关键输出

```text
mvn -B -T1C verify                      # 20/20 SUCCESS（~1:21）；313 单测 0 失败；覆盖率汇总 87.0%
mvn -B -pl starter/coord-it -am verify -Pit
                                        # IT 14 类 / 33 例全绿（Testcontainers 真实容器）
                                        # 首跑命中 1 例 flake（CoordConfigRefreshManagerTest），复跑 3 次全绿
mvn -B -Prelease -DskipTests package    # SUCCESS；15 source + 15 javadoc jar；267 条 javadoc no-comment 警告
git rev-parse --is-inside-work-tree     # fatal: not a git repository
ls LICENSE NOTICE .github mvnw          # 均不存在
find . -name spring-configuration-metadata.json   # 0 命中（10 个模块已声明 processor）
grep -rl 'Licensed under' --include='*.java' ...  # 0 / 182
find . -name '*IT.java' | wc -l         # 0（实际为 *IntegrationTest.java）
```
