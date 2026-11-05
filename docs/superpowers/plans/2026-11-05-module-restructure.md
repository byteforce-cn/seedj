# 模块拓扑重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `core / domain-* / workflow-* / seedj-outbox-starter / starter` 重构为 `core / seedj-infra-local / seedj-jdbc-postgresql / starters / servers / seedj-it`，并让三个参考服务可独立启动、集成测试全绿。

**Architecture:** `core` 只放契约；`seedj-infra-local` 与 `seedj-jdbc-postgresql` 是并列实现模块，只依赖 `core`；`seedj-iam-starter` / `seedj-biz-starter` 负责装配；`seedj-server/{iam,hr,im}` 是可运行宿主；`seedj-it` 只做集成测试与夹具。

**Tech Stack:** Java 21、Maven 3.9、Spring Boot 4.1.1、PostgreSQL/Testcontainers、JUnit 5、AssertJ。

**Spec:** `docs/adr/ADR-0008-module-topology.md`

## Global Constraints

- `core` 零 Spring、零 seedj 依赖。
- 两个实现模块只依赖 `core`，彼此不依赖；Spring `provided`，第三方库 `optional`/`provided`。
- `seedj-iam-starter` 与 `seedj-biz-starter` 互不依赖。
- `seedj-server-iam` 不得依赖 `seedj-biz-starter`；`seedj-server-hr` / `seedj-server-im` 不得依赖 `seedj-iam-starter`。
- server 包名 `cn.byteforce.seedj.server.<name>`。
- server 不进入对外 BOM；`seedj-it` 对 server 的依赖为 `test` scope。
- 仓库当前不是 git 工作区，本计划不执行 commit 步骤。

## Review Focus

- **多实例验证**：当前 `ClusterExtension` 每个服务只启动 1 个实例，尚不能验证 outbox 竞争、eventbus 消费组、分布式锁与 ID 分配；后续 IT 扩展时必须先补这一项。
- **server 夹具边界**：测试用户、测试控制器只能留在 `seedj-it/src/test`，不得进入 server 的 `src/main`。
- **outbox 合并后的依赖方向**：`seedj-jdbc-postgresql` 的 relay 需要 `IdGenerator` / `EventBus` 时只能注入 core 契约，不能 import `seedj-infra-local`。
- **exec classifier**：server 的主制品必须是 thin jar，`seedj-it` 才能以普通依赖启动；发布脚本不要误用 `-exec.jar`。
- **workflow 自动装配**：`WorkflowAutoConfiguration` 原本未登记到 `AutoConfiguration.imports`，合并后保持原行为，不趁机激活。

---

### Task 1: 重命名实现模块并合并 workflow

**Files:**
- Rename: `domain-infra-memory/` → `seedj-infra-local/`
- Merge: `workflow-infra-memory/src/main/java/cn/byteforce/seedj/workflow/memory/` → `seedj-infra-local/src/main/java/cn/byteforce/seedj/workflow/memory/`
- Merge: `workflow-infra-memory/src/test/java/cn/byteforce/seedj/workflow/memory/` → `seedj-infra-local/src/test/java/cn/byteforce/seedj/workflow/memory/`
- Modify: `seedj-infra-local/pom.xml`
- Modify: `pom.xml`, `bom/pom.xml`

- [x] 重命名目录并复制 workflow main/test 源码
- [x] `seedj-infra-local/pom.xml` 改 artifactId / name / description
- [x] 根 POM 与 BOM 移除 `workflow-infra-memory`，加入 `seedj-infra-local`
- [x] 验证：`mvn -DskipTests -pl seedj-infra-local -am install`

### Task 2: 合并 outbox 到 PostgreSQL 实现模块

**Files:**
- Rename: `domain-jdbc-postgresql/` → `seedj-jdbc-postgresql/`
- Merge: `seedj-outbox-starter/src/main/java/cn/byteforce/seedj/outbox/wiring/` → `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox/wiring/`
- Merge: `seedj-outbox-starter/src/test/java/cn/byteforce/seedj/outbox/wiring/` → `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/outbox/wiring/`
- Modify: `seedj-jdbc-postgresql/pom.xml`（加入 `micrometer-core` optional）
- Modify: `seedj-jdbc-postgresql/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（追加 `OutboxAutoConfiguration`）
- Modify: `seedj-iam-starter/pom.xml`、`seedj-biz-starter/pom.xml`（依赖改到 `seedj-jdbc-postgresql`）
- Delete: `seedj-outbox-starter/`、重复的 `OutboxStarterPackageIndependenceTest`

- [x] 合并源码、测试与 `AutoConfiguration.imports`
- [x] 更新 starter 依赖与 BOM
- [x] 保留 `PostgresOutboxPackageIndependenceTest` 作为 outbox 不依赖 eventbus 的门禁
- [x] 验证：`mvn -DskipTests -pl seedj-jdbc-postgresql,seedj-iam-starter,seedj-biz-starter -am install`

### Task 3: 拆出可运行 server 模块

**Files:**
- Create: `seedj-server/pom.xml`
- Create: `seedj-server/iam/pom.xml`、`hr/pom.xml`、`im/pom.xml`
- Create: `seedj-server/{iam,hr,im}/src/main/java/cn/byteforce/seedj/server/{iam,hr,im}/*Server.java`
- Create: `seedj-server/{iam,hr,im}/src/main/resources/application.yml`
- Move: `seedj-it/src/test/.../iam/IamServer.java`、`hr/HrServer.java`、`im/ImServer.java` 的 main 入口

- [x] 创建 `seedj-server` 聚合 POM，配置 `spring-boot-maven-plugin` 与 `exec` classifier
- [x] 三个 server 只保留启动类与最小 `application.yml`
- [x] server POM 显式声明 starter + 两个实现模块 + actuator + JDBC driver
- [x] `seedj-server-iam` 不依赖 `seedj-biz-starter`；`hr/im` 不依赖 `seedj-iam-starter`
- [x] 验证：`mvn -DskipTests install`，确认 thin jar 与 `-exec.jar` 同时产出

### Task 4: `starter` 改名 `seedj-it`，迁移测试夹具

**Files:**
- Rename: `starter/` → `seedj-it/`
- Move fixtures: `IamServer` 中的测试用户 → `seedj-it/src/test/java/cn/byteforce/seedj/it/fixture/iam/IamTestUsers.java`
- Move fixtures: `hr/HrTestController`、`HrEventSubscriber`、`HrOrderPlaced` → `seedj-it/src/test/java/cn/byteforce/seedj/it/fixture/hr/`
- Move fixtures: `im/ImTestController` → `seedj-it/src/test/java/cn/byteforce/seedj/it/fixture/im/`
- Move tests: `IamFixtureWiringTest`、`BizIamChainOrderTest`、`SeedjIT` → `cn.byteforce.seedj.it`
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/it/extension/ClusterExtension.java`

- [x] `seedj-it/pom.xml` 改为依赖三个 `seedj-server-*`（test scope）+ 两个实现模块
- [x] `ServiceDefinition` 增加 `sources`，通过 `SpringApplicationBuilder.sources(...)` 注入夹具
- [x] `OidcIT` 的常量引用从 `IamServer` 改为 `IamTestUsers`
- [x] `EventConsumeIT` 引用改为 `cn.byteforce.seedj.it.fixture.hr.HrEventSubscriber`
- [x] 验证：`mvn test`，`IamFixtureWiringTest` / `BizIamChainOrderTest` 通过

### Task 5: 更新 BOM / 文档 / 兼容门禁并跑全量验证

**Files:**
- Modify: `bom/pom.xml`、`pom.xml`
- Modify: `docs/spi/README.md`、`docs/biz/README.md`
- Create: `docs/adr/ADR-0008-module-topology.md`
- Modify: `core/src/test/java/cn/byteforce/seedj/OptionsDefaultsGateTest.java`

- [x] BOM 只保留库模块；根 POM modules 更新
- [x] 文档中的旧模块名、`starter`、outbox 归属全部更新
- [x] `OptionsDefaultsGateTest` 扫描新模块路径
- [x] 验证：`mvn -DskipTests install`
- [x] 验证：`mvn test`（全部单元测试通过）
- [x] 验证：`mvn -pl seedj-it -am verify`（110 个集成测试通过）

## 验证记录

| 命令 | 结果 |
|---|---|
| `mvn -DskipTests install` | BUILD SUCCESS，12 个模块（含 3 个 server） |
| `mvn test` | BUILD SUCCESS，库模块与 `seedj-it` 非 Docker 测试通过 |
| `mvn -pl seedj-it -am verify` | BUILD SUCCESS，`Tests run: 110, Failures: 0, Errors: 0` |
