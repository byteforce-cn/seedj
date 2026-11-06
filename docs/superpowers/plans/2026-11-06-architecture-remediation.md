# seedj2 A- 架构整改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在四个评估维度（架构合理性、工程规范、业务认知负担、架构整洁）全部达到 A-，并把 `seedj-server/hr` 从“能力验收样例”改造成“业务项目模板”。

**Architecture:** 以双时间轴命令为业务默认写路径；用 `CommandDescriptor` 统一命令级权限/校验/脱敏；用 `ObjectSchema` 作为业务字段单一来源；补通用 list/search 与类型化查询模板；把实现包移出契约命名空间；用 CI、覆盖率 ratchet、ArchUnit、Public API allowlist、DocLink、FileSize 门禁把标准变成可执行证据。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Maven 3.9+、JUnit 5、AssertJ、MockMvc、Testcontainers PostgreSQL、ArchUnit、JaCoCo、SpotBugs、Spotless、GitHub Actions（或等价 CI）。

**Spec:** `docs/assessment/2026-11-06-architecture-remediation-spec.md`

## Global Constraints

- Java 21、Spring Boot 4.1.1、Maven 3.9+；不得降低版本。
- 线格式 `/commands/{type}`、`/objects/{type}/{id}`、错误信封 `{code,message,details}` 保持兼容；新增字段只能可选。
- 1.0.0 尚未发布，允许破坏性变更；每个破坏性变更必须写 ADR 并更新 `docs/conformance/change-list-template.md`。
- 每个任务先写失败测试，再实现，再跑模块测试；一个任务一个 commit。
- 新运行时依赖必须写 ADR；测试依赖不受此限。
- 公共 API 变更必须更新 `config/public-api.txt`，否则 `PublicApiSurfaceTest` 失败；新增 public 类型后运行 `-Dseedj.api.update=true` 并提交 allowlist diff。
- 包命名：`cn.byteforce.seedj.<cap>` = 契约（core）；`cn.byteforce.seedj.infra.<vendor>.<cap>` = 适配器；`cn.byteforce.seedj.starter.<area>` = Spring 装配/Web；`cn.byteforce.seedj.server.<name>` = 宿主。
- 禁止 `ThreadLocal` 传递业务状态；禁止 `AtomicLong` 生成持久业务标识；禁止静默降级锁/事务/outbox。
- 生产 profile 禁止 `test-only-key`、禁止默认 `CORS *`、禁止无权限执行命令。
- 所有 Markdown 相对链接必须可解析；`DocLinkTest` 必须绿。
- 覆盖率：新增/变更行 ≥ 90%；模块 floor 见 T0.2；最终阈值见 T4.3。

## Review Focus

- 非 UUID IdP：`Caller.id()` 是 `"u-1"` 时，命令应通过 `CallerIdCodec` 工作，或返回明确 401，不得在每条命令上静默失败。
- 缺锁/事务/outbox：默认启动失败；只有显式 `seedj.command.degraded-mode=true`（local/test）才允许 `direct/disabled/discard`，并打印 WARN。
- Schema 漂移：加字段只改 `ObjectSchema` + command record；漏改事件/DDL/redactor 必须被 `SchemaConformanceTest` 或 `HrEmployeeSchemaTest` 抓住。
- 未授权命令：返回 403 `EIS-403-001`，不是 401/500；业务拒绝不是 503。
- 并发创建：两个实例同时创建同一 ID，一个 202、一个 409/400，不得 500；锁与唯一约束都要有测试。
- 事件丢失：没有 outbox 时不得返回 `event_ids` 后静默丢弃；必须失败或显式 `discard()`。
- 空列表/分页边界：`page=1&size=0`、`size>200`、未知 filter、未知 sort 字段必须 400，不得 SQL 注入或 500。
- HR 本地启动：`local` profile 不依赖外部 PostgreSQL；`postgres` profile 有迁移与 Testcontainers 测试。

---

## 里程碑

| 里程碑 | 范围 | 退出条件 |
|---|---|---|
| M0 事实与门禁 | T0.1–T0.4 | CI 存在；覆盖率不再是假绿；ArchUnit/DocLink/PublicApi/FileSize 以 allowlist 方式开始运行 |
| M1 黄金路径 | T1.1–T1.8 | HR local 可启动；create/update/get + 权限 + 校验 + 错误矩阵 + Postgres 迁移通过 |
| M2 读侧与安全 | T2.1–T2.5 | 通用 list/search、类型化查询、Postgres 锁、安全默认值通过 |
| M3 整洁架构 | T3.1–T3.6 | split package/包环/public API/死代码/god class 门禁转绿 |
| M4 文档与发布 | T4.1–T4.4 | 全量 CI、A- 矩阵、验收记录完成 |

---

## M0：事实与门禁

### Task 0.1：仓库卫生（LICENSE/NOTICE、Maven Wrapper、Spotless、DocLink）

**Files:**
- Create: `LICENSE`、`NOTICE`、`.editorconfig`、`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`
- Create: `config/ci/check-doc-links.sh`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/DocLinkTest.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/LicensePresenceTest.java`
- Modify: `pom.xml`（Spotless、`project.build.outputTimestamp` 可后置）
- Modify: `.gitignore`（`.mvn/wrapper/maven-wrapper.jar` 按官方建议处理）

**Interfaces:**
- Consumes: 无
- Produces: `DocLinkTest`、`LicensePresenceTest`、`mvnw`、Spotless 配置

- [ ] **Step 1：写失败测试 `DocLinkTest`**

  扫描 `README.md`、`docs/**/*.md`、`**/README.md`（排除 `target`、`.git`），解析 `[text](path)` 与 `[ref]: path`；扫描前去掉 fenced code block 与 inline code，避免把示例 `[text](path)` 当链接；忽略 `http(s)://`、`mailto:`、`#anchor`；相对路径必须存在。断言：`assertThat(missing).isEmpty()`。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=DocLinkTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL，列出当前 15 个失效链接。

- [ ] **Step 3：补 LICENSE/NOTICE、Maven Wrapper、Spotless**

  - `LICENSE` 使用 Apache-2.0 全文；`NOTICE` 写 `Byteforce Seedj` 版权行。
  - 运行 `mvn wrapper:wrapper -Dmaven=3.9.9` 生成 wrapper。
  - `pom.xml` 增加 `spotless-maven-plugin`：`googleJavaFormat("1.22.0").aosp()` + `removeUnusedImports()` + `trimTrailingWhitespace()` + `endWithNewline()`；绑定 `check` 到 `verify`，`apply` 手动执行。
  - `.editorconfig`：UTF-8、LF、4 spaces、100 cols、Java 不强制 wildcard import。
  - 增加 `LicensePresenceTest`：断言仓库根存在 `LICENSE`、`NOTICE`，且 `seedj-server/hr/target/*.jar` 的 `META-INF` 包含两者（用 `JarFile`，无 jar 时跳过并 `Assumptions.assumeTrue`）。

- [ ] **Step 4：修复文档链接**

  运行 `DocLinkTest` 拿到全部缺失链接，逐一修复：`docs/api-compatibility.md`、`docs/releasing.md`、`docs/superpowers/specs/2026-11-05-contract-conformance-design.md`，以及 ADR/README 中引用的其余设计/计划页。可以创建最小索引页（指向 ADR 与 conformance 目录）或删除过期引用。更新 `README.md` 只保留真实存在的链接。不得用空文件占位；每个文件至少说明目的与权威来源。

- [ ] **Step 5：运行测试与格式化**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=DocLinkTest,LicensePresenceTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS
  Run: `./mvnw -o -B spotless:apply`
  Run: `./mvnw -o -B -pl core test`
  Expected: PASS

- [ ] **Step 6：Commit**

  ```bash
  git add LICENSE NOTICE .editorconfig mvnw mvnw.cmd .mvn pom.xml config/ci docs seedj-it/src/test/java/cn/byteforce/seedj/conformance
  git commit -m "chore: add license, wrapper, formatting and doc link guards"
  ```

### Task 0.2：覆盖率门禁从假绿改为真实 ratchet

**Files:**
- Modify: `pom.xml`（删除 `check-core` 0.00 与错误的 `check-starter` include）
- Modify: `core/pom.xml`、`seedj-biz-starter/pom.xml`、`seedj-infra-local/pom.xml`、`seedj-jdbc-postgresql/pom.xml`、`seedj-iam-starter/pom.xml`（各自 `jacoco:check`）
- Create: `config/coverage-baseline.json`
- Create: `config/ci/check-coverage.sh`
- Modify: `docs/testing.md`（新建）

**Interfaces:**
- Consumes: JaCoCo `target/site/jacoco/jacoco.xml`
- Produces: 每模块 floor + 变更行 90% 门禁

- [ ] **Step 1：修正 include 与阈值**

  删除根 `pom.xml` 的 `check-core` / `check-starter` 两个 execution。每个有主源码的模块 POM 增加：
  ```xml
  <plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
      <execution>
        <id>check-coverage</id>
        <phase>verify</phase>
        <goals><goal>check</goal></goals>
        <configuration>
          <rules>
            <rule>
              <element>BUNDLE</element>
              <limits>
                <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>${seedj.coverage.line}</minimum></limit>
                <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>${seedj.coverage.branch}</minimum></limit>
              </limits>
            </rule>
          </rules>
        </configuration>
      </execution>
    </executions>
  </plugin>
  ```
  `seedj.coverage.line` / `branch` 放在各模块 `<properties>`；初值用当前基线（见 Step 2），T4.3 再提高。

- [ ] **Step 2：记录当前基线**

  当前实测：core line 52.4% / branch 42.1%；infra-local 71.5% / 52.6%；jdbc-postgresql 73.3% / 54.5%；biz-starter 55.4% / 46.4%；iam-starter 本轮未生成报告，先在 CI 生成后取实测值。
  写入 `config/coverage-baseline.json`：
  ```json
  {
    "core": {"line": 0.52, "branch": 0.42},
    "seedj-infra-local": {"line": 0.71, "branch": 0.52},
    "seedj-jdbc-postgresql": {"line": 0.73, "branch": 0.54},
    "seedj-biz-starter": {"line": 0.55, "branch": 0.46},
    "seedj-iam-starter": {"line": 0.60, "branch": 0.45}
  }
  ```
  初值只允许上浮，不允许下调；下调必须改本文件并在 PR 说明原因。

- [ ] **Step 3：写 `config/ci/check-coverage.sh`**

  解析每个模块的 `target/site/jacoco/jacoco.xml`，与 `config/coverage-baseline.json` 比较；任何模块低于基线 0.5 个百分点即失败。缺失报告（例如只构建部分模块）打印 WARN 并跳过；CI 全量构建必须无缺失。若安装 `diff-cover`，再执行：
  ```bash
  diff-cover **/target/site/jacoco/jacoco.xml --compare-branch=origin/main --fail-under=90
  ```
  本地没有 `diff-cover` 时打印 SKIP，CI 必须安装并执行。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl core verify`
  Expected: PASS，且 `core/target/site/jacoco/jacoco.xml` 存在
  Run: `./mvnw -o -B -pl seedj-biz-starter -am verify`
  Expected: PASS；四模块均生成报告
  Run: `bash config/ci/check-coverage.sh`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add pom.xml */pom.xml config/coverage-baseline.json config/ci/check-coverage.sh docs/testing.md
  git commit -m "build: make coverage gates real with per-module ratchet"
  ```

### Task 0.3：架构门禁（ArchUnit / SplitPackage / FileSize / PublicApi）

**Files:**
- Modify: `seedj-it/pom.xml`（ArchUnit test deps）
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ArchitectureTest.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/SplitPackageTest.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/PackageCycleTest.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/FileSizeGuardTest.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/PublicApiSurfaceTest.java`
- Create: `config/public-api.txt`
- Create: `config/archunit/file-size-allowlist.txt`
- Create: `config/archunit/split-package-allowlist.txt`
- Create: `config/archunit/package-cycle-allowlist.txt`

**Interfaces:**
- Consumes: 各模块 `target/classes` 与 `src/main/java`
- Produces: `PublicApiSurfaceTest` 读取/写入 `config/public-api.txt`；allowlist 文件在 M3 逐步清空

- [ ] **Step 1：写 ArchUnit 规则**

  先用 `new ClassFileImporter().importPath(Path.of("../core/target/classes"))` 等逐模块导入，再对导入的 `JavaClasses` 应用规则。不要用全局 classpath：当前存在 split package，全局导入会把 starter 的类也算进 core。

  `ArchitectureTest` 至少包含（对 `coreClasses` 应用）：
  ```java
  @ArchTest static final ArchRule coreIsFrameworkFree = noClasses()
      .that().resideInAPackage("cn.byteforce.seedj.core..")
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..");
  @ArchTest static final ArchRule bizDoesNotDependOnIam = noClasses()
      .that().resideInAPackage("cn.byteforce.seedj.biz..")
      .should().dependOnClassesThat().resideInAPackage("cn.byteforce.seedj.iam..");
  @ArchTest static final ArchRule infraLocalDoesNotDependOnJdbc = noClasses()
      .that().resideInAPackage("cn.byteforce.seedj..infra.local..")
      .should().dependOnClassesThat().resideInAPackage("cn.byteforce.seedj..infra.jdbc..");
  ```
  另加一条：`starter.cqrs.web` 不得依赖事件溯源 `CommandAgent`，确保通用 HTTP 入口只暴露默认双时间轴路径。当前 M0 允许用 `config/archunit/allowlist` 记录已知违规；T3.1–T3.5 清空。

- [ ] **Step 2：写 `SplitPackageTest`**

  对每个 Maven 模块扫描 `../<module>/target/classes`，构建 `package -> Set<module>`；断言任何 package 只属于一个模块。当前违规写入 `split-package-allowlist.txt`，每行 `package|moduleA,moduleB`；T3.2 删除全部条目。

- [ ] **Step 3：写 `PackageCycleTest`**

  用 ArchUnit `slices().matching("cn.byteforce.seedj.(*)..")` 检查顶层包环；再对 `core` 的子包用 `slices().matching("cn.byteforce.seedj.(*..)")` 检查。当前 4 个环写入 `package-cycle-allowlist.txt`；T3.3 只保留允许的 `parent↔spi`。

- [ ] **Step 4：写 `FileSizeGuardTest`**

  扫描所有 `src/main/java/**/*.java`，行数 > 600 即失败，除非在 `file-size-allowlist.txt`。当前只允许 `InMemoryWorkflowProvider.java`；T3.5 删除。

- [ ] **Step 5：写 `PublicApiSurfaceTest`**

  扫描各模块 `target/classes` 的 public 顶层类型，生成 `module|package|SimpleName`；与 `config/public-api.txt` 精确比较。提供 `-Dseedj.api.update=true` 时写回文件（开发者显式更新并提交）。首次运行生成 305 个 core 类型 + 其他模块的完整清单。

- [ ] **Step 6：验证**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=ArchitectureTest,SplitPackageTest,PackageCycleTest,FileSizeGuardTest,PublicApiSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS（当前违规均在 allowlist 中）
  Run: `git diff --exit-code config/public-api.txt`
  Expected: 无输出

- [ ] **Step 7：Commit**

  ```bash
  git add seedj-it/pom.xml seedj-it/src/test/java/cn/byteforce/seedj/conformance config/public-api.txt config/archunit
  git commit -m "test: add architecture, split-package, file-size and public-api guards"
  ```

### Task 0.4：CI 与本地同源脚本

**Files:**
- Create: `config/ci/verify.sh`
- Create: `config/ci/conformance.sh`
- Create: `config/ci/release.sh`
- Create: `.github/workflows/ci.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: T0.1–T0.3 的脚本与测试
- Produces: CI 与本地同一套命令；README 可复现

- [ ] **Step 1：写 `verify.sh`**

  ```bash
  #!/usr/bin/env bash
  set -euo pipefail
  MAVEN_FLAGS="${MAVEN_FLAGS:--o}"   # 本地默认离线；CI 设置 MAVEN_FLAGS="" 走在线仓库
  ./mvnw $MAVEN_FLAGS -B -Panalysis verify
  bash config/ci/check-doc-links.sh
  bash config/ci/check-coverage.sh
  ./mvnw $MAVEN_FLAGS -B spotless:check
  ```
  说明：`-Panalysis` 运行 SpotBugs；`verify` 包含 Testcontainers IT（CI 有 Docker）。CI 不得使用 `-o`，除非缓存已预热。

- [ ] **Step 2：写 `conformance.sh`**

  ```bash
  MAVEN_FLAGS="${MAVEN_FLAGS:--o}"
  ./mvnw $MAVEN_FLAGS -B -Pconformance -DskipTests verify
  ```
  仅在有规范仓库的 job 执行；README 写明 `-Dspec.root`。

- [ ] **Step 3：写 `release.sh`**

  ```bash
  MAVEN_FLAGS="${MAVEN_FLAGS:--o}"
  ./mvnw $MAVEN_FLAGS -B -Psbom -DskipTests package
  ./mvnw $MAVEN_FLAGS -B -Pcompat -DskipTests verify
  ```
  `compat` 需要已发布的 1.0.0 基线；在 T4.3 启用。

- [ ] **Step 4：写 GitHub Actions**

  jobs：`verify`（Temurin 21、Maven cache、Docker）、`conformance`（可选、nightly）、`sbom`（tag）、`compat`（tag）。`verify` 必须运行 `config/ci/verify.sh`。如果实际 CI 不是 GitHub，保留脚本作为唯一入口，`ci.yml` 作为参考。

- [ ] **Step 5：验证**

  Run: `bash config/ci/verify.sh`
  Expected: PASS（首次可能因 Spotless 需要 `spotless:apply`；先 apply 再提交）
  Run: `bash config/ci/conformance.sh`
  Expected: 有规范仓库时 PASS；无仓库时按 POM 显式失败，README 说明

- [ ] **Step 6：Commit**

  ```bash
  git add config/ci .github/workflows/ci.yml README.md
  git commit -m "ci: add reproducible verify, conformance and release gates"
  ```

---

## M1：黄金路径

### Task 1.1：`CommandDescriptor` / `CommandValidator` / `CommandPolicy`

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandDescriptor.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandValidator.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandPolicy.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/TemporalCommandAgent.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/outbox/OutboxWriter.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandDescriptorTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/TemporalCommandAgentTest.java`
- Test: `seedj-infra-local/src/test/java/cn/byteforce/seedj/cqrs/CommandRuntimeRedactionTest.java`

**Interfaces:**
- `CommandDescriptor(String type, String objectType, Class<?> payloadType, String permission, CommandValidator validator, CommandPayloadRedactor redactor)`
- `CommandValidator.validate(Object command)`；`CommandValidator.none()`
- `CommandPolicy.authorize(CommandDescriptor descriptor, Caller caller, Map<String,Object> payload)`；`CommandPolicy.permissions()`
- `TemporalCommandAgent(CommandRuntime, CommandDescriptor)`；`descriptor()`；`redact(Map)`
- `TemporalCommandAgent.append(C command, UUID targetId, Optional<TemporalRow> current, Instant now, UUID actor)`（默认委托旧方法）
- `CommandRuntime.Work.redact(Map<String,Object>)`；`CommandRuntime.Options` 全参构造 + `forTests(...)`
- `OutboxWriter.discard()`

- [ ] **Step 1：写失败测试 `CommandDescriptorTest`**

  断言：type 必须匹配 `^[a-z][a-z0-9_.]*$`；objectType/payloadType 非空；permission 可为空但 `permissions()` policy 对空 permission 抛出 `IllegalArgumentException`（启动期）；builder 缺字段失败。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl core test -Dtest=CommandDescriptorTest`
  Expected: FAIL（类不存在）

- [ ] **Step 3：实现 descriptor/validator/policy**

  `CommandDescriptor` 用 record + builder；`CommandValidator` 是 `@FunctionalInterface`；`CommandPolicy.permissions()` 检查 `caller.hasPermission(descriptor.permission())`，失败抛 `ContractException(ContractErrorCode.FORBIDDEN, "missing permission: " + permission)`。

- [ ] **Step 4：改造 `TemporalCommandAgent` 与 `CommandRuntime`**

  - `TemporalCommandAgent` 新构造器保存 descriptor；`commandType()`/`objectType()` 从 descriptor 取；`decode` 用 `descriptor.payloadType()`；`validate` 默认调用 `descriptor.validator().validate(command)`；`redact` 调用 descriptor.redactor()。
  - `execute` 先算 `UUID targetId = targetId(command)`，再构造 `Work`；`append` 使用新重载，业务子类可拿到 targetId。
  - `CommandRuntime` 用 `work.redact(submission.payload())` 替代 `options.payloadRedactor()`；删除 `Options.payloadRedactor`；全参构造要求 `outboxWriter`/`temporalAudit` 非 null。
  - `CommandRuntime.Options.forTests(clock, ids, commands, objects)` 显式使用 `TransactionRunner.direct()`、`LockProvider.disabled()`、`OutboxWriter.discard()`、`TemporalCommandAudit.noop()`。
  - `OutboxWriter.discard()` 返回空实现，javadoc 标明仅测试/本地。
  - `TemporalCommandAutoConfiguration` 删除 `ObjectProvider<CommandPayloadRedactor>` 注入与构造参数，改用 `work.redact`；为保证 T1.1 提交后仍可启动，暂时用 `outbox.getIfAvailable(OutboxWriter::discard)`、`audit.getIfAvailable(TemporalCommandAudit::noop)` 满足非空构造；T1.6 再改为 fail-fast/degraded-mode。

- [ ] **Step 5：更新现有测试**

  `TemporalCommandAgentTest`、`CommandRuntimeRedactionTest`、`CommandControllerTest` 中的匿名 agent 改用 descriptor；`CommandRuntimeRedactionTest` 改为两个 agent 各自 redactor，断言互不影响。

- [ ] **Step 6：验证**

  Run: `./mvnw -o -B -pl core test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-infra-local -am test`
  Expected: PASS

- [ ] **Step 7：Commit**

  ```bash
  git add core/src/main core/src/test seedj-infra-local/src/test seedj-biz-starter/src/main
  git commit -m "feat: add command descriptor, validator and policy seams"
  ```

### Task 1.2：命令目录与 HTTP 边界接入权限/校验/调用方编解码

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandCatalog.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/CallerIdCodec.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/web/CommandController.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/validation/BeanValidationCommandValidator.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfiguration.java`（注册 `ContractErrorHandler`）
- Modify: `seedj-biz-starter/pom.xml`（增加 `spring-boot-starter-validation`）
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/web/CommandControllerTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandCatalogTest.java`

**Interfaces:**
- `CommandCatalog(List<TemporalCommandAgent<?>>, CommandPolicy)`；`execute(CommandSubmission, Caller)`；`descriptors()`
- `CallerIdCodec.toActor(Caller) -> UUID`；`uuid()`；`nameUuid()`
- `CommandController(CommandCatalog, CommandStore, CallerProvider, CallerIdCodec)`
- `CommandValidator` bean：`BeanValidationCommandValidator(jakarta.validation.Validator)`；`TemporalCommandAutoConfiguration` 在存在 `jakarta.validation.Validator` 时提供，`@ConditionalOnMissingBean`

- [ ] **Step 1：写失败测试**

  `CommandCatalogTest`：无权限 caller 抛 `ContractException(FORBIDDEN)`；未知类型抛 `INVALID_PAYLOAD`；`descriptors()` 返回全部；descriptor 的 `permission` 为空时构造 `CommandCatalog` 立即失败。
  `CommandControllerTest`：`@WithMockUser` 无 `PERM_hr.employee.write` → 403 `EIS-403-001`；有权限 → 202；`Caller("u-1")` + `nameUuid()` → 202；默认 `uuid()` → 401。
  `BeanValidationCommandValidatorTest`：`@NotBlank` 字段为空 → `ContractException(INVALID_PAYLOAD)`，错误消息包含字段名。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test -Dtest=CommandControllerTest,CommandCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `CommandCatalog` 构造器复制 agents，`Map.copyOf`；descriptor 的 `permission` 为空则抛 `IllegalArgumentException`（启动期）；`execute` 先 `policy.authorize(agent.descriptor(), caller, submission.payload())`，再 `agent.execute`。
  - `CommandController` 从 `callers.find()` 取 caller，缺失 → `UNAUTHENTICATED`；`issuer` 用 `CallerIdCodec`；`ContractException` 的 `FORBIDDEN` 直接 rethrow 交给 `ContractErrorHandler`，`IDEMPOTENCY_CONFLICT` 走 409，其余按现有规则。
  - 新增 `BeanValidationCommandValidator`：校验失败抛 `ContractException(INVALID_PAYLOAD, "field: message")`；`seedj-biz-starter` 增加 `spring-boot-starter-validation`；auto-config 提供 `CommandValidator` bean。
  - `BizSecurityAutoConfiguration` 注册 `@Bean @ConditionalOnMissingBean ContractErrorHandler contractErrorHandler()`，让 T1.5 的 403/400 测试在真实上下文里拿到契约信封。
  - `TemporalCommandAutoConfiguration` 提供 `CommandPolicy.permissions()`、`CallerIdCodec.uuid()` 与 `CommandValidator` 的 `@ConditionalOnMissingBean`；`commandCatalog(List, policy)`、`CommandController(..., callerIdCodec)` 的 bean 签名同步更新。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main seedj-biz-starter/src/main seedj-biz-starter/src/test seedj-biz-starter/pom.xml
  git commit -m "feat: enforce command permission, validation and caller id codec at HTTP boundary"
  ```

### Task 1.3：`ObjectSchema` 单一来源与 JDBC 类型转换

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/FieldType.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/FieldDefinition.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/ObjectSchema.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/ObjectSchemaRegistry.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalTable.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalDdl.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalValues.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStore.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Test: `core/src/test/java/cn/byteforce/seedj/domain/ObjectSchemaTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/TemporalTableTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/TemporalDdlTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStoreContractTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfigurationTest.java`（改为提供 `ObjectSchema`）

**Interfaces:**
- `FieldType`：`TEXT/INTEGER/LONG/DECIMAL/BOOLEAN/DATE/TIMESTAMP/UUID`（JSON 后置；本轮不引入未测试类型）
- `FieldType.coerce(Object) -> Object`：HTTP 字符串 → 类型化值；非法值抛 `ContractException(INVALID_PAYLOAD)`
- `FieldDefinition(String name, FieldType type, boolean required, boolean writeOnly, boolean eventVisible, boolean filterable)`
- `ObjectSchema(String objectType, String tableName, List<FieldDefinition> fields)` + `builder`；`field(name)`、`writeOnlyFields()`、`eventFields()`、`requiredFields()`、`filterableFields()`、`validate(Map<String,Object>)`
- `ObjectSchemaRegistry(List<ObjectSchema>)`；`empty()`、`require(String)`、`find(String)`、`all()`
- 保留 `TemporalTable(String, String, Map, Set)` 四参构造器（旧代码可编译）；新增 `TemporalTable.from(ObjectSchema)`、`filterableColumns()`、`fieldTypes()`
- `TemporalValues.toJdbc(FieldType, Object)` / `fromJdbc(FieldType, Object)`

- [ ] **Step 1：写失败测试**

  `ObjectSchemaTest`：字段名/表名小写标识符；重复字段失败；`writeOnly && eventVisible` 失败；required 缺失失败；unknown field 失败；`eventFields()` 只含 eventVisible。
  `TemporalTableTest`：`TemporalTable.from(schema).columns()` 的 SQL 类型映射正确；`filterableColumns()` 正确。
  `PostgresTemporalStoreContractTest`：增加 `LocalDate`/`UUID` 字段，插入后按类型读回。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl core test -Dtest=ObjectSchemaTest`
  Expected: FAIL
  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test -Dtest=TemporalTableTest,PostgresTemporalStoreContractTest`
  Expected: FAIL

- [ ] **Step 3：实现 schema 与派生表**

  - `ObjectSchema` 校验：表名/字段名 `^[a-z][a-z0-9_]*$`；字段不与 10 个元字段冲突；writeOnly 不能 eventVisible；保存不可变列表。
  - `FieldType.coerce` 处理 String → Long/Integer/BigDecimal/Boolean/LocalDate/Instant/UUID；`ObjectSchema.validate` 与两个 store 的 filter 都复用它。
  - `TemporalTable` 保留旧四参构造器，新增 `filterableColumns` 与 `fieldTypes`；`from(ObjectSchema)` 用 `LinkedHashMap` 派生；SQL 类型映射：TEXT→TEXT、INTEGER→INTEGER、LONG→BIGINT、DECIMAL→NUMERIC(18,4)、BOOLEAN→BOOLEAN、DATE→DATE、TIMESTAMP→TIMESTAMPTZ(6)、UUID→UUID。
  - `TemporalDdl` 继续用 `TemporalTable.columns()`，无需改 SQL 结构。
  - `TemporalValues` 处理 DATE/TIMESTAMP/UUID/DECIMAL；`PostgresTemporalStore.insert/toRow` 调用。
  - `ObjectSchemaRegistry` 放 core，持有 `Map<String,ObjectSchema>`；`TemporalTables.from(registry)` 在 jdbc 侧派生。
  - `TemporalCommandAutoConfiguration` 增加 `@Bean @ConditionalOnMissingBean ObjectSchemaRegistry`，并由 registry 派生 `TemporalTables`；HR 只声明 `ObjectSchema`，不再声明 `TemporalTable`。
  - 更新 `TemporalCommandAutoConfigurationTest`：原先提供 `TemporalTable` bean 的用例改为提供 `ObjectSchema`；provider 选择行为不变。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test`
  Expected: PASS（Testcontainers）
  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main core/src/test seedj-jdbc-postgresql/src seedj-biz-starter/src/main
  git commit -m "feat: derive temporal tables and ddl from a single object schema"
  ```

### Task 1.4：HR 样板改用 descriptor/schema，移除 ThreadLocal 与 AtomicLong

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/DerivedFieldRegistry.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeSchema.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeConfiguration.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeCreateAgent.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeUpdateAgent.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeFields.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeCreate.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeUpdate.java`
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/it/HrEmployeeSampleTest.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/it/fixture/hr/HrEmployeeTestSchema.java`
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/it/HrEmployeeCreateAgentTest.java`

**Interfaces:**
- `DerivedFieldRegistry(Map<String,DerivedField>)` + `require(String)`
- `HrEmployeeSchema.schema() -> ObjectSchema`
- `HrEmployeeCreateAgent(CommandRuntime, CommandDescriptor, DerivedFieldRegistry, IdGenerator<String>)`
- `HrEmployeeUpdateAgent(CommandRuntime, CommandDescriptor, DerivedFieldRegistry)`
- `HrEmployeeFields` 方法接收 `ObjectSchema` / `DerivedFieldRegistry`

- [ ] **Step 1：写失败测试**

  `HrEmployeeCreateAgentTest` 放在 `seedj-it`（已依赖 HR）：创建后 `employee_no` 来自 `IdGenerator<String>`；无 `ThreadLocal` 字段（反射断言 `HrEmployeeCreateAgent` 不含 `ThreadLocal` 类型字段）；`hire_date` 用 `LocalDate`；非法日期由 validator 返回 400（在 T1.5 的 HTTP 测试断言）。
  `HrEmployeeSampleTest`：改用 `HrEmployeeTestSchema`，字段用 `LocalDate`；断言 schema 派生的事件白名单不含 `id_number_hash`。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=HrEmployeeSampleTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 编译失败（构造器/字段变化）

- [ ] **Step 3：实现**

  - `HrEmployeeSchema`：`employee_no` TEXT required eventVisible filterable；`name` TEXT required；`status` TEXT required；`hire_date` DATE；`employment_type` TEXT；`work_location` TEXT；`id_number_hash` TEXT writeOnly（不 eventVisible）；`org_unit_id` TEXT。
  - `HrEmployeeConfiguration`：删除 `hrEmployeeTable()`（由 `ObjectSchemaRegistry` 派生 `TemporalTables`），新增：
    ```java
    @Bean ObjectSchema hrEmployeeSchema() { return new HrEmployeeSchema().schema(); }
    @Bean DerivedFieldRegistry hrDerivedFields(DerivedField idNumberHash) { ... }
    @Bean CommandDescriptor hrCreateDescriptor(ObjectSchema schema, CommandValidator validator) { ... permission("hr.employee.write").redactor(...) }
    ```
  - `HrEmployeeConfiguration` 的 derived field 用 `@Qualifier` 注入并注册为逻辑名 `id_number_hash`；agent 只依赖 `DerivedFieldRegistry`，避免多个 `DerivedField` bean 注入歧义。
  - `HrEmployeeCreate`/`Update`：`hireDate` 改为 `LocalDate`，去掉 `@JsonProperty("hire_date")` 字符串；仍保留 snake_case 注解用于其他字段。
  - `HrEmployeeCreateAgent`：删除 `ThreadLocal`；构造器接收 `IdGenerator<String>`；`append(command, targetId, current, now, actor)` 用 `targetId` 作为对象 ID；`employeeNo = "EMP-" + ids.id()`；`validFrom = command.hireDate() == null ? now : command.hireDate().atStartOfDay(ZoneOffset.UTC).toInstant()`（不再 `LocalDate.parse(String)`）；`HrEmployeeFields.create(command, employeeNo, schema, derived)`。
  - `HrEmployeeUpdateAgent`：`append(command, targetId, current, now, actor)`；`HrEmployeeFields.merge(...)`。
  - 两个 agent 的 `validate` 如保留，首行必须 `super.validate(command)`；更推荐删除手写 `if`，改用 `@NotBlank`/`@NotNull` 与 `BeanValidationCommandValidator`。
  - `HrEmployeeFields`：用 schema 校验与事件裁剪；事件 payload 用 `schema.eventFields()`；不再手写白名单。
  - `HrEmployeeConfiguration.hrPayloadRedactor` 删除；redactor 放到 descriptor。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-server/hr -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=HrEmployeeSampleTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main seedj-server/hr seedj-it/src/test
  git commit -m "refactor: make hr sample use command descriptor and object schema"
  ```

### Task 1.5：HR local profile 与 HTTP 集成测试

**Files:**
- Create: `seedj-server/hr/src/main/resources/application-local.yml`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/LocalDevAuthenticationFilter.java`（`@ConditionalOnProperty(seedj.biz.dev-auth.enabled)`）
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/LocalDevSecurityConfiguration.java`（`@ConditionalOnProperty(seedj.biz.dev-auth.enabled)`）
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/LocalDevJwtConfiguration.java`（`@Profile("local")`）
- Create: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeHttpTest.java`
- Create: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeSecurityTest.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/JwtCallerProvider.java`（支持 `Caller` principal）
- Modify: `seedj-server/hr/pom.xml`（test deps：`spring-boot-starter-test`、`spring-security-test`）
- Modify: `seedj-server/pom.xml`（HR 覆盖 `seedj.jacoco.guard.skip=false`）
- Create: `docs/quickstart.md`

**Interfaces:**
- `application-local.yml`：`spring.autoconfigure.exclude` DataSource/JdbcTemplate/DataSourceTransactionManager；`seedj.command.provider=memory`、`seedj.object.provider=memory`、`seedj.command.degraded-mode=true`、`seedj.audit.enabled=false`、`seedj.lock.memory.enabled=true`、`seedj.eventbus.enabled=false`、`seedj.outbox.enabled=false`、`seedj.biz.dev-auth.enabled=true`、`seedj.security.derived-field-keys=local-dev-key`。
- `LocalDevAuthenticationFilter` 由 `seedj.biz.dev-auth.enabled=true` 激活（不依赖 profile），设置 `Caller` 为 `local-admin`，权限 `hr.employee.write`、`hr.employee.read`。
- `LocalDevSecurityConfiguration` 由 `seedj.biz.dev-auth.enabled=true` 激活，提供 `@Order(5)` 的本地过滤链（`permitAll` + `addFilterBefore(devFilter, AnonymousAuthenticationFilter.class)`）；`false` 时整条本地链不生效，回落到 `BizSecurityAutoConfiguration` 的 order 6 链。
- `LocalDevJwtConfiguration` 仅在 `local` profile 提供 `JwtDecoder` 占位 bean，避免 `HrEmployeeSecurityTest` 关闭 dev-auth 后因缺 `JwtDecoder` 启动失败；Postgres IT 在测试配置里自带占位 bean。

- [ ] **Step 1：写失败测试 `HrEmployeeSecurityTest`**

  `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("local")`，关闭 dev auth（`seedj.biz.dev-auth.enabled=false`）：
  - `GET /commands/...` 无 token → 401 `EIS-401-001`；
  - 有认证但无 `PERM_hr.employee.write` → 403 `EIS-403-001`；
  - 有权限 → 202。

  用 `MockMvc` + `SecurityMockMvcRequestPostProcessors.authentication` 传 `UsernamePasswordAuthenticationToken(new Caller(...), null, authorities)`；`JwtCallerProvider.fromAuthorities` 优先识别 `Caller` principal，避免 username 反推 id。无权限 case 用没有 `hr.employee.write` 的 `Caller`；有权限 case 用带 `hr.employee.write` 的 `Caller`。非 UUID `Caller` 的 401 行为单独用 `CommandControllerTest` 覆盖。

- [ ] **Step 2：写失败测试 `HrEmployeeHttpTest`**

  `@SpringBootTest` + `@ActiveProfiles("local")` + `@AutoConfigureMockMvc`，使用 dev auth 或 mock authentication：
  - create `202`，返回 `locator`/`version=1`；
  - get `200`，`employee_no` 非空，`id_number_hash` 不返回；
  - update `202`，`version=2`；`as_of` 读回 v1 name；
  - 同 key 同 payload 重放 → 同 `command_id`；
  - 同 key 不同 payload → 409；
  - 非法 `hire_date` → 400 `EIS-400-001`；
  - 未知 command type → 400；
  - 不存在对象 update → 400 `EIS-404-001`（按命令拒绝语义）。

- [ ] **Step 3：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-server/hr -am test -Dtest=HrEmployeeHttpTest,HrEmployeeSecurityTest`
  Expected: FAIL（profile/过滤器/断言未实现）

- [ ] **Step 4：实现 local profile 与 dev auth**

  - `application-local.yml` 显式排除数据源自动配置；设置 memory providers、degraded-mode、audit off、lock memory on、eventbus off、outbox off、dev auth on、derived-field-keys。
  - `JwtCallerProvider.fromAuthorities` 增加：`if (authentication.getPrincipal() instanceof Caller caller) return caller;`，让测试/dev 用 `Caller` principal，不必用 username 反推 id。
  - `LocalDevAuthenticationFilter`：`@ConditionalOnProperty(seedj.biz.dev-auth.enabled, matchIfMissing=false)`（不依赖 profile）；为请求设置 `UsernamePasswordAuthenticationToken`，principal 用 `Caller`（id 为固定 UUID，username 为 `local-admin`，权限 `hr.employee.write`/`hr.employee.read`），authorities 用 `PERM_hr.employee.write`、`PERM_hr.employee.read`、`ROLE_ADMIN`；已有 authentication 时不覆盖。这样默认 `CallerIdCodec.uuid()` 不会 401。
  - `LocalDevSecurityConfiguration`：`@ConditionalOnProperty(seedj.biz.dev-auth.enabled, matchIfMissing=false)`；定义 `@Order(5)` `SecurityFilterChain`，`permitAll`，`addFilterBefore(devFilter, AnonymousAuthenticationFilter.class)`。
  - `LocalDevJwtConfiguration`：`@Profile("local")`，提供 `JwtDecoder` 占位 bean（解码抛 `JwtException("local dev auth")`），避免无 IdP 时启动失败；dev-auth 关闭时仍然存在，供 security 测试使用。
  - `HrServer` 启动不因 `spring-boot-starter-jdbc` 失败。
  - `docs/quickstart.md` 写：
    ```bash
    ./mvnw -pl seedj-server/hr -am spring-boot:run -Dspring-boot.run.profiles=local
    curl -X POST localhost:8080/commands/hr.employee.create ...
    ```

- [ ] **Step 5：验证**

  Run: `./mvnw -o -B -pl seedj-server/hr -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-server/hr -am spring-boot:run -Dspring-boot.run.profiles=local`（另开终端）
  Expected: 30 秒内启动；`curl /actuator/health` 200

- [ ] **Step 6：Commit**

  ```bash
  git add seedj-server/hr seedj-server/pom.xml docs/quickstart.md
  git commit -m "feat: make hr sample runnable locally with http integration tests"
  ```

### Task 1.6：依赖缺失 fail-fast 与 degraded-mode

**Files:**
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/CommandRuntimeProperties.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/CommandAgentAutoConfiguration.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/outbox/OutboxWriter.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfigurationTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/wiring/CommandAgentAutoConfigurationTest.java`

**Interfaces:**
- `CommandRuntimeProperties`：`degradedMode`（默认 false）、`requireOutbox`（默认 true）
- `OutboxWriter.discard()`

- [ ] **Step 1：写失败测试**

  `ApplicationContextRunner` 缺 `TransactionRunner`/`LockProvider`/`OutboxWriter`：
  - 默认 → context 启动失败，消息包含 `seedj.command.degraded-mode`；
  - `degraded-mode=true` → 启动成功，捕获 WARN 日志（`OutputCaptureExtension` 或 `ListAppender`）；
  - `require-outbox=false` → 允许无 outbox，但 `degraded-mode` 仍为 true。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test -Dtest=TemporalCommandAutoConfigurationTest,CommandAgentAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `TemporalCommandAutoConfiguration.commandRuntime` 注入 `ObjectProvider`，按 `degradedMode` 决定 `direct/disabled/discard`；默认缺任一即 `throw new IllegalStateException("seedj.command requires ... set seedj.command.degraded-mode=true only for local/test")`。
  - 启动 WARN：`"seedj: command runtime running in degraded mode: tx=direct lock=disabled outbox=discard"`。
  - `CommandAgentAutoConfiguration` 同样对 `LockProvider` 缺失做 fail-fast；`degradedMode` 复用同一属性。
  - 测试与 `application-local.yml` 设置 `degraded-mode=true`。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-server/hr -am test`
  Expected: PASS（local profile 显式 degraded）

- [ ] **Step 5：Commit**

  ```bash
  git add seedj-biz-starter core/src/main seedj-server/hr
  git commit -m "fix: fail fast when command runtime dependencies are missing"
  ```

### Task 1.7：PostgreSQL 迁移与 HR Postgres 集成测试

**Files:**
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/ObjectSchemaCapability.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeSchemaCapability.java`
- Create: `seedj-server/hr/src/main/resources/application-postgres.yml`
- Create: `docker-compose.yml`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/it/HrServerPostgresIT.java`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/it/fixture/hr/HrEmployeeSchemaConformanceTest.java`
- Modify: `seedj-server/hr/pom.xml`（testcontainers postgresql test scope）

**Interfaces:**
- `ObjectSchemaCapability(String name, JdbcMigrationRunner runner, ObjectSchema schema, List<Migration> migrations)`；`getOrder()=200`
- `HrEmployeeSchemaCapability` 注册 V1 `TemporalDdl.createTable(TemporalTable.from(schema))`
- `application-postgres.yml`：datasource env、`seedj.command.provider=postgres`、`seedj.object.provider=postgres`、`seedj.audit.enabled=true`、`seedj.outbox.enabled=true`、`seedj.lock.memory.enabled=true`（单实例；T2.5 改为 postgres 锁）

- [ ] **Step 1：写失败测试 `HrServerPostgresIT`**

  Testcontainers `postgres:17-alpine`；启动 HR `postgres` profile；断言：
  - Flyway 风格迁移表 `seedj_schema_history` 含 `hr-employee` scope；
  - `information_schema.columns` 包含 schema 全部字段；
  - create → 202；get → 200；`as_of` → v1；
  - 两个并发 create 同一目标（可用一个带固定 ID 的测试命令或直接调用 store）→ 一个成功、一个 409/400，不是 500。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=HrServerPostgresIT -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL（无表/无迁移）

- [ ] **Step 3：实现迁移**

  - `ObjectSchemaCapability` 包装 `JdbcMigrationRunner.migrate(scope, migrations)`，`isReady`/`validate` 转发；`getOrder()=200` 确保在 `TemporalSchemaCapability(120)` 之后。
  - HR `@Profile("postgres")` 注册 capability；V1 使用 `TemporalDdl.createTable`。
  - `application-postgres.yml` 读取 `SPRING_DATASOURCE_URL` 等 env；本地 `docker-compose.yml` 提供 postgres:17。
  - Postgres profile 在 T2.5 前使用内存锁（单实例）；T2.5 切换到 `PostgresLockProvider`。IT 用 `@SpringBootTest(properties={"seedj.biz.dev-auth.enabled=true", "seedj.security.derived-field-keys=test-key"})`，并在 `@TestConfiguration` 提供 `JwtDecoder` 占位 bean；不激活 `local` profile，避免 DataSource 排除。
  - `HrEmployeeSchemaConformanceTest` 在迁移后比较 `ObjectSchema.fields()` 与 `information_schema.columns`。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=HrServerPostgresIT,HrEmployeeSchemaConformanceTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS（Docker）
  Run: `docker compose up -d && ./mvnw -pl seedj-server/hr spring-boot:run -Dspring-boot.run.profiles=postgres`
  Expected: 启动成功

- [ ] **Step 5：Commit**

  ```bash
  git add seedj-jdbc-postgresql seedj-server/hr seedj-it docker-compose.yml
  git commit -m "feat: add postgres schema migrations and hr postgres integration test"
  ```

### Task 1.8：错误模型矩阵

**Files:**
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/web/ContractErrorHandler.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/core/exception/ContractException.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/exception/AggregateNotFoundException.java`
- Create: `docs/errors.md`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/biz/web/ContractErrorHandlerTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/web/CommandControllerTest.java`

**Interfaces:**
- `CqrsException` → `COMMAND_REJECTED` 400；`CONCURRENCY_CONFLICT` 409；`INVALID_*` 400；`STORE_UNAVAILABLE` 503；其余 500。
- `ContractException` 去掉 `final`；`AggregateNotFoundException extends ContractException(OBJECT_NOT_FOUND)`。
- `ContractErrorHandler` 已在 T1.2 注册；本任务只补 `CqrsException`/`AggregateNotFoundException` 映射。
- `BaseException` 兼容 handler（如 T3.4 删除则不需要）。

- [ ] **Step 1：写失败测试**

  `ContractErrorHandlerTest` 增加：`CqrsException(COMMAND_REJECTED)` → 400 `EIS-400-002`；`CqrsException(CONCURRENCY_CONFLICT)` → 409 `EIS-409-002`；`AggregateNotFoundException` → 404 `EIS-404-001`；`BizException` → 旧 code/status（若保留）。
  `CommandControllerTest`：业务拒绝 `CqrsException` 不得变 503。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test -Dtest=ContractErrorHandlerTest,CommandControllerTest`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `ContractException` 去掉 `final`；`AggregateNotFoundException` 改为 `extends ContractException`，构造器传 `OBJECT_NOT_FOUND` 与 details。
  - `ContractErrorHandler` 增加 `@ExceptionHandler(CqrsException.class)`，按 `getErrorCode()` 映射；`ContractErrorHandler` bean 已在 T1.2 注册。
  - `HrEmployeeHttpTest` 增加 `invalidObjectIdUsesContractEnvelope`：`GET /objects/employee/not-a-uuid` → 400 且响应含 `code`/`message`/`details`。
  - `docs/errors.md` 表：异常 → HTTP → code → 触发条件 → 调用方动作。
  - 更新 `docs/biz/README.md` 的 403 示例与响应形状（T4.1 再统一，但本任务先改这一处）。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-infra-local -am test`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main seedj-biz-starter/src docs/errors.md docs/biz/README.md
  git commit -m "fix: map cqrs and aggregate errors to contract http semantics"
  ```

---

## M2：读侧与安全

### Task 2.1：`TemporalStore.search`（过滤/分页/排序/时点）

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/PageRequest.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/Page.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/SortDirection.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/TemporalSearch.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/TemporalStore.java`
- Modify: `seedj-infra-local/src/main/java/cn/byteforce/seedj/domain/memory/InMemoryTemporalStore.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStore.java`
- Test: `core/src/test/java/cn/byteforce/seedj/domain/TemporalStoreContractTest.java`

**Interfaces:**
- `PageRequest(int page, int size)`：page 1-based，size 1..200
- `Page<T>(List<T> items, long total, int page, int size)`
- `SortDirection`：`ASC/DESC`
- `TemporalSearch(String objectType, Map<String,Object> equals, Instant asOf, Instant recordedAsOf, Long minVersion, String sortField, SortDirection direction, PageRequest page)`
- `TemporalStore.search(TemporalSearch) -> Page<TemporalRow>`
- `InMemoryTemporalStore(Clock, ObjectSchemaRegistry)`；保留 `InMemoryTemporalStore(Clock)` 便捷构造器（`empty()` registry）；`ObjectSchemaRegistry.empty()`

- [ ] **Step 1：写失败测试**

  `TemporalStoreContractTest` 增加：
  - `searchFiltersByEqualsAndPages`：3 个对象，filter `status=active`，page=1&size=1，total=2；
  - `searchSortsByFilterableField`：按 `employee_no` 升/降；
  - `searchAsOfReturnsHistoricalRow`；
  - `searchRejectsUnknownFilter`、`searchRejectsUnknownSort`、`searchRejectsSizeOver200`；
  - `searchRejectsNonFilterableColumn`；
  - `searchCoercesStringFilters`：`hire_date=2026-10-01` 命中 `LocalDate` 字段；非法日期 → 400。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-infra-local -am test -Dtest=InMemoryTemporalStoreContractTest`
  Expected: FAIL
  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test -Dtest=PostgresTemporalStoreContractTest`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `InMemoryTemporalStore`：构造器接收 `ObjectSchemaRegistry`（可为 `empty()`），用 `filterableColumns()` 校验 filter/sort；从内存表取所有当前/时点行，按 filter 过滤，排序，分页。
  - `TemporalCommandAutoConfiguration` memory 分支传 `ObjectSchemaRegistry`；`HrEmployeeSampleTest` 传 `HrEmployeeTestSchema` 构造的 `ObjectSchemaRegistry`。
  - `PostgresTemporalStore`：用 `TemporalTable.filterableColumns()` 校验 filter/sort；SQL 形状：
    ```sql
    SELECT * FROM (
      SELECT DISTINCT ON (id) * FROM <table>
      WHERE <equals> AND <asof predicates>
      ORDER BY id, version DESC
    ) t ORDER BY <sort> <dir> LIMIT ? OFFSET ?
    ```
    count 用 `SELECT COUNT(*) FROM (SELECT DISTINCT ON (id) id FROM <table> WHERE ...) t`。
  - `TemporalSearch` 构造期校验 page/size/filter key；未知列抛 `ContractException(INVALID_PAYLOAD)`；filter 值在 store 内用 `schema.field(name).type().coerce(value)` 转换，非法值 → `INVALID_PAYLOAD`。
  - `minVersion` 在列表搜索中不适用，传非空直接 `INVALID_PAYLOAD`（或仅在 find 使用）。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-infra-local -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main core/src/test seedj-infra-local seedj-jdbc-postgresql seedj-biz-starter/src/main
  git commit -m "feat: add temporal search with filters, paging and sorting"
  ```

### Task 2.2：`GET /objects/{objectType}` 列表接口

**Files:**
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web/ObjectQueryController.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web/ObjectPageResponse.java`
- Modify: `docs/conformance/api/query.openapi.yaml`
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ApiAlignmentTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/domain/web/ObjectQueryControllerTest.java`

**Interfaces:**
- `GET /objects/{objectType}?page=1&size=20&sort=employee_no&direction=asc&status=active&as_of=...`
- 响应：`{items:[ObjectQueryResponse], page, size, total}`
- `ObjectQueryController(TemporalStore, ObjectSchemaRegistry)`；`redact` 用 `registry.require(objectType).writeOnlyFields()`
- 未知参数 → 400；`size>200` → 400。

- [ ] **Step 1：写失败测试**

  `ObjectQueryControllerTest.listReturnsPage`：2 个对象、size=1 → items=1、total=2、page=1。
  `listRejectsUnknownFilter`、`listRejectsSizeOver200`、`listAppliesAsOf`。
  `ApiAlignmentTest` 反射 `list` 方法路径与参数名。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test -Dtest=ObjectQueryControllerTest,ApiAlignmentTest`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `list` 收集 `@RequestParam Map<String,String> params`；保留字 `page/size/sort/direction/as_of/recorded_as_of`；其余作为 equals filter。
  - 解析 `as_of`/`recorded_as_of` 复用 `parse`；`size` 默认 20，范围 1..200；`page` 默认 1；`NumberFormatException`/非法日期统一转 `ContractException(INVALID_PAYLOAD)`，不得 500。
  - 调 `store.search`，`items` 用 `ObjectQueryResponse.from`；`redact` 用 `ObjectSchemaRegistry`，不再依赖 jdbc 的 `TemporalTables`。
  - `TemporalCommandAutoConfiguration.WebConfiguration` 的 `ObjectQueryController` 构造参数改为 `ObjectSchemaRegistry`。
  - `query.openapi.yaml` 增加路径与 `ObjectPage` schema；`ApiAlignmentTest` 增加断言。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=ApiAlignmentTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add seedj-biz-starter/src docs/conformance/api/query.openapi.yaml seedj-it/src/test
  git commit -m "feat: expose object list with filters and pagination"
  ```

### Task 2.3：类型化查询模板与 HR 查询示例

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/TemporalQueryRepository.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeView.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeQueryService.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeQueryController.java`
- Test: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeQueryServiceTest.java`
- Test: `seedj-server/hr/src/test/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeHttpTest.java`（补 list/get typed）

**Interfaces:**
- `TemporalQueryRepository<T>(TemporalStore, ObjectSchema)`；`find(UUID, TemporalQuery) -> Optional<T>`；`search(TemporalSearch) -> Page<T>`；`protected abstract T map(TemporalRow)`
- `HrEmployeeQueryService extends TemporalQueryRepository<HrEmployeeView>`；`byId(UUID)`、`list(PageRequest)`
- `GET /api/hr/employees/{id}`、`GET /api/hr/employees?page=...`

- [ ] **Step 1：写失败测试**

  `HrEmployeeQueryServiceTest`：创建 2 个员工，`list(new PageRequest(1,1))` 返回 1 条、total=2；`byId` 字段映射正确、无 `id_number_hash`。
  `HrEmployeeHttpTest`：`GET /api/hr/employees?page=1&size=20` 200，`GET /api/hr/employees/{id}` 200，权限 `hr.employee.read` 缺失 → 403。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-server/hr -am test -Dtest=HrEmployeeQueryServiceTest,HrEmployeeHttpTest`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `TemporalQueryRepository` 构造期非空；`find` 调 `store.find` 并 `map`；`search` 调 `store.search` 并 `map` items。
  - `HrEmployeeView` 只包含可读字段（不含 `id_number_hash`）；`map` 从 `TemporalRow.fields()` 取值，处理 `LocalDate`。
  - `HrEmployeeQueryController` 加 `@PreAuthorize("hasAuthority('PERM_hr.employee.read')")` 或 descriptor policy；保持与通用命令口径一致。
  - 保留通用 `/objects` 作为内部/服务面；HR 自有读面作为业务模板。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-server/hr -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=HrEmployeeSampleTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main seedj-server/hr
  git commit -m "feat: add typed temporal query template and hr list endpoints"
  ```

### Task 2.4：安全默认值（派生密钥、CORS、CallerIdCodec）

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/core/security/DerivedFieldKeyRing.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityProperties.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfiguration.java`
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeConfiguration.java`
- Test: `core/src/test/java/cn/byteforce/seedj/core/security/DerivedFieldKeyRingTest.java`
- Test: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/biz/security/BizSecurityAutoConfigurationTest.java`

**Interfaces:**
- `DerivedFieldKeyRing.fromEnvironment()`：缺失 env → `IllegalStateException`；`DerivedFieldKeyRing.forTests(String...)` 仅测试。
- `HrEmployeeConfiguration.hrIdNumberHash` 优先读 `seedj.security.derived-field-keys`，为空才 `fromEnvironment()`；local profile 用 `local-dev-key`。
- `BizSecurityProperties.corsAllowedOrigins`：默认空；`seedj.biz.cors.allowed-origins` 必须显式配置；空 + `prod` profile → 启动失败。
- `CallerIdCodec` 默认 `uuid()`；业务可覆盖 `nameUuid()`。

- [ ] **Step 1：写失败测试**

  `DerivedFieldKeyRingTest`：`fromEnvironment()` 在无 env 时抛异常；`forTests` 可用；`HrEmployeeConfiguration` 测试上下文在无 env 时启动失败。
  `BizSecurityAutoConfigurationTest`：无 CORS 配置 + `prod` profile → 启动失败；`allowed-origins` 配置 → 成功；默认不允许 `*`。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl core test -Dtest=DerivedFieldKeyRingTest`
  Expected: FAIL
  Run: `./mvnw -o -B -pl seedj-biz-starter -am test -Dtest=BizSecurityAutoConfigurationTest`
  Expected: FAIL

- [ ] **Step 3：实现**

  - `fromEnvironment` 删除 `"test-only-key"` 回退；`forTests` 构造明确测试密钥。
  - `HrEmployeeConfiguration.hrIdNumberHash(@Value("${seedj.security.derived-field-keys:}") String keys)`：非空用 `new DerivedFieldKeyRing(keys.split(","))`；为空回退 `fromEnvironment()`。
  - HR 测试用 `forTests`；生产配置要求 `SEEDJ_FIELD_HASH_KEYS`。
  - CORS 默认 `List.of()`；`allowedOriginPatterns` 不再 `*`；`prod` profile 下空配置抛 `IllegalStateException`。
  - `BizSecurityAutoConfiguration` 在 `seedj.biz.dev-auth.enabled=true` 且 `prod` profile 激活时启动失败（防止 local 开发链泄漏到生产）；测试覆盖该组合。
  - `application-local.yml` 设置 `seedj.biz.cors.allowed-origins: http://localhost:3000` 与 `seedj.security.derived-field-keys: local-dev-key`。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl core test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-server/hr -am test`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add core/src/main core/src/test seedj-biz-starter seedj-server/hr
  git commit -m "fix: make derived-field keys and cors fail closed"
  ```

### Task 2.5：`PostgresLockProvider`（生产分布式锁）

**Files:**
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/lock/jdbc/PostgresLockProvider.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/lock/jdbc/PostgresLockMigrations.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/lock/jdbc/PostgresLockSchemaCapability.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/lock/jdbc/PostgresLockAutoConfiguration.java`
- Modify: `seedj-jdbc-postgresql/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `seedj-server/hr/src/main/resources/application-postgres.yml`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/lock/jdbc/PostgresLockProviderContractTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/lock/jdbc/PostgresLockAutoConfigurationTest.java`

**Interfaces:**
- `PostgresLockProvider(DataSource)` implements `LockProvider`（DB `now()` 作为租约时间，避免应用时钟漂移）
- `tryAcquire(String key, Duration ttl) -> Optional<LockLease>`
- 表：
  ```sql
  CREATE TABLE seedj_lock_lease (
    lock_key   VARCHAR(255) PRIMARY KEY,
    owner      UUID         NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL
  );
  CREATE INDEX idx_seedj_lock_lease_expires ON seedj_lock_lease(expires_at);
  ```
- 配置：`seedj.lock.postgres.enabled=true`

- [ ] **Step 1：写失败测试**

  `PostgresLockProviderContractTest` 继承 `LockProviderContractTest`（Testcontainers）：空闲可获取、同键互斥、异键独立、释放可重入、租约到期可重入、`acquire` 占用抛 `LOCK_BUSY`、空白 key/非法 TTL 失败。
  额外 `twoProvidersRaceForSameKey`：两个 provider 同时 `tryAcquire`，只有一个成功。
  `PostgresLockAutoConfigurationTest`：有 DataSource + property → bean；无 DataSource → 不创建；迁移幂等。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test -Dtest=PostgresLockProviderContractTest,PostgresLockAutoConfigurationTest`
  Expected: FAIL

- [ ] **Step 3：实现**

  - acquire SQL：
    ```sql
    INSERT INTO seedj_lock_lease(lock_key, owner, expires_at)
    VALUES (?, ?, now() + (? * interval '1 millisecond'))
    ON CONFLICT (lock_key) DO UPDATE
      SET owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at
      WHERE seedj_lock_lease.expires_at < now()
    RETURNING owner
    ```
    若返回 owner 等于本次 token → 获取成功；否则 empty。
  - release：`DELETE FROM seedj_lock_lease WHERE lock_key=? AND owner=?`。
  - `LockLease.close()` 幂等；provider close 不清表。
  - `PostgresLockSchemaCapability` 使用 `JdbcMigrationRunner`，scope `lock`，V1 建表；`getOrder()=150`。
  - `PostgresLockAutoConfiguration` 加 `@ConditionalOnMissingBean(LockProvider.class)`；`application-postgres.yml` 启用 `seedj.lock.postgres.enabled=true`，并把 T1.7 的 `seedj.lock.memory.enabled` 删除或改为 false。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test`
  Expected: PASS（Docker）
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=HrServerPostgresIT -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add seedj-jdbc-postgresql seedj-server/hr
  git commit -m "feat: add postgres lease-based distributed lock provider"
  ```

---

## M3：整洁架构

### Task 3.1：把 PG clock 实现移出 core，schema.sql 移入资源与迁移

**Files:**
- Move: `core/src/main/java/cn/byteforce/seedj/core/clock/spi/pg/PgHybridStateStore.java` → `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/clock/PgHybridStateStore.java`（包名同步）
- Move: `core/src/main/java/cn/byteforce/seedj/core/clock/spi/pg/PgGlobalSequencer.java` → `.../infra/jdbc/clock/PgGlobalSequencer.java`
- Move: `core/src/main/java/cn/byteforce/seedj/core/clock/schema.sql` → `seedj-jdbc-postgresql/src/main/resources/cn/byteforce/seedj/infra/jdbc/clock/schema.sql`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/infra/jdbc/clock/ClockMigrations.java`
- Modify: `core/pom.xml`（如无引用则删除 lombok? 保留）
- Modify: `seedj-jdbc-postgresql/pom.xml`（无需新增依赖）
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/infra/jdbc/clock/PgClockMigrationTest.java`
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ArchitectureTest.java`（core 规则转绿）

**Interfaces:**
- `PgHybridStateStore(DataSource)` / `PgGlobalSequencer(DataSource)` 签名不变，仅包名变化。
- `ClockMigrations.all()` 创建 `sys_hybrid_state`、`sys_global_seq`。

- [ ] **Step 1：写失败测试**

  `PgClockMigrationTest`：Testcontainers 执行 `ClockMigrations.all()`，断言表和 sequence 存在；`PgHybridStateStore` load/save 可用；`PgGlobalSequencer.next()` 递增。
  `ArchitectureTest.coreIsFrameworkFree` 去掉 allowlist 后应通过。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test -Dtest=PgClockMigrationTest`
  Expected: FAIL（类/迁移不存在）

- [ ] **Step 3：移动并实现**

  - `git mv` 两个类与 SQL；更新包名。
  - `ClockMigrations` 用 `Migration(1, "clock state", schemaSql)`，SQL 从 classpath 资源读取或内联；`ClockSchemaCapability` 可选，若当前没有自动装配则只提供迁移类与测试。
  - 更新 `docs` 中的包名引用。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-jdbc-postgresql -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS（core 规则）

- [ ] **Step 5：Commit**

  ```bash
  git add core seedj-jdbc-postgresql docs
  git commit -m "refactor: move postgres clock adapters out of core"
  ```

### Task 3.2：消除跨模块 split package（机械重构，按模块提交）

**Files:**
- Move: 按下表逐模块 `git mv` 并重写 package/import
- Modify: 所有 `AutoConfiguration.imports`
- Modify: `config/public-api.txt` 与 `seedj-it/.../PublicApiSurfaceTest` 的包名期望
- Modify: `docs/**/*.md` 中的包名
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/SplitPackageTest.java`（清空 allowlist）

**硬约束：** 替换只在目标模块源码与直接引用它的 server/it 文件中进行。`core/src/main/java/cn/byteforce/seedj/<cap>` 的契约包（`id`、`pki`、`transit`、`audit`、`cqrs`、`domain`、`eventbus`、`iam`、`lock`、`outbox`、`workflow`）不得被全局 `sed` 改写；否则会把契约 import 指向实现包。

**包映射（必须逐条执行）：**

| 模块 | 旧包 | 新包 |
|---|---|---|
| infra-local | `cn.byteforce.seedj.cqrs.memory` | `cn.byteforce.seedj.infra.local.cqrs` |
| infra-local | `cn.byteforce.seedj.domain.memory` | `cn.byteforce.seedj.infra.local.domain` |
| infra-local | `cn.byteforce.seedj.eventbus.transport` | `cn.byteforce.seedj.infra.local.eventbus` |
| infra-local | `cn.byteforce.seedj.lock.memory` | `cn.byteforce.seedj.infra.local.lock` |
| infra-local | `cn.byteforce.seedj.id` | `cn.byteforce.seedj.infra.local.id` |
| infra-local | `cn.byteforce.seedj.pki` | `cn.byteforce.seedj.infra.local.pki` |
| infra-local | `cn.byteforce.seedj.transit` | `cn.byteforce.seedj.infra.local.transit` |
| infra-local | `cn.byteforce.seedj.audit.memory` | `cn.byteforce.seedj.infra.local.audit` |
| infra-local | `cn.byteforce.seedj.security.memory` | `cn.byteforce.seedj.infra.local.security` |
| infra-local | `cn.byteforce.seedj.iam.memory` | `cn.byteforce.seedj.infra.local.iam` |
| infra-local | `cn.byteforce.seedj.workflow.memory` | `cn.byteforce.seedj.infra.local.workflow` |
| jdbc | `cn.byteforce.seedj.audit.jdbc` | `cn.byteforce.seedj.infra.jdbc.audit` |
| jdbc | `cn.byteforce.seedj.cqrs.jdbc` | `cn.byteforce.seedj.infra.jdbc.cqrs` |
| jdbc | `cn.byteforce.seedj.domain.jdbc` | `cn.byteforce.seedj.infra.jdbc.domain` |
| jdbc | `cn.byteforce.seedj.iam.jdbc` | `cn.byteforce.seedj.infra.jdbc.iam` |
| jdbc | `cn.byteforce.seedj.jdbc` | `cn.byteforce.seedj.infra.jdbc` |
| jdbc | `cn.byteforce.seedj.outbox.jdbc` | `cn.byteforce.seedj.infra.jdbc.outbox` |
| jdbc | `cn.byteforce.seedj.outbox.wiring` | `cn.byteforce.seedj.infra.jdbc.outbox.wiring` |
| jdbc | `cn.byteforce.seedj.lock.jdbc` | `cn.byteforce.seedj.infra.jdbc.lock` |
| biz-starter | `cn.byteforce.seedj.audit.wiring` | `cn.byteforce.seedj.starter.audit` |
| biz-starter | `cn.byteforce.seedj.cqrs.web` | `cn.byteforce.seedj.starter.cqrs.web` |
| biz-starter | `cn.byteforce.seedj.cqrs.wiring` | `cn.byteforce.seedj.starter.cqrs.wiring` |
| biz-starter | `cn.byteforce.seedj.domain.web` | `cn.byteforce.seedj.starter.domain.web` |
| biz-starter | `cn.byteforce.seedj.eventbus.wiring` | `cn.byteforce.seedj.starter.eventbus` |
| biz-starter | `cn.byteforce.seedj.observability.wiring` | `cn.byteforce.seedj.starter.observability` |
| iam-starter | `cn.byteforce.seedj.iam.**` | `cn.byteforce.seedj.starter.iam.**` |

- [ ] **Step 1：先做 infra-local**

  `git mv` 目录；`sed -i` 替换 package/import；更新其 `AutoConfiguration.imports`；运行 `./mvnw -o -B -pl seedj-infra-local -am test`；提交。

- [ ] **Step 2：再做 jdbc**

  同上；更新 `AutoConfiguration.imports`；运行 `./mvnw -o -B -pl seedj-jdbc-postgresql -am test`；提交。

- [ ] **Step 3：再做 biz-starter**

  同上；更新 `AutoConfiguration.imports`；运行 `./mvnw -o -B -pl seedj-biz-starter -am test`；提交。

- [ ] **Step 4：最后做 iam-starter**

  `git mv` 目录；`sed -i` 只替换 `seedj-iam-starter/src`、`seedj-server/iam/src`、`seedj-it/src/test` 中的 package/import；更新其 `AutoConfiguration.imports`；除 import 外，grep `"cn.byteforce.seedj.iam` 字符串常量（如 `ScimExceptionHandler` 的 `basePackages`）并同步；运行 `./mvnw -o -B -pl seedj-iam-starter -am test`；提交。

- [ ] **Step 5：清空 SplitPackage allowlist 并验证**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=SplitPackageTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS
  Run: `git diff -U0 core/src/main | grep '^+package'`
  Expected: 无输出（core 契约包未被改写）
  Run: `./mvnw -o -B -Panalysis verify`
  Expected: PASS

- [ ] **Step 6：Commit（最终）**

  ```bash
  git add -A
  git commit -m "refactor: remove cross-module split packages"
  ```

### Task 3.3：拆 IAM 四包环，收紧包环门禁

**Files:**
- Move: `core/src/main/java/cn/byteforce/seedj/iam/` 中 policy 模型类型 → `cn.byteforce.seedj.iam.policy`（按依赖方向）
- Move: `IamPolicyDecisionPoint`/`IamPolicy` 等按设计归位
- Modify: `core/src/main/java/cn/byteforce/seedj/iam/policy/DefaultIamPolicyDecisionPoint.java`
- Modify: `seedj-iam-starter` 相关 import
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/PackageCycleTest.java`（移除 IAM 环 allowlist）

**Interfaces:**
- 目标：`iam`（主体/角色/权限快照）不依赖 `iam.policy`；`iam.policy` 依赖 `iam` + `iam.spi`；`iam.scim` 只依赖 `iam` + `iam.spi`，不反向被 `iam` 依赖。

- [ ] **Step 1：用 `jdeps`/测试确认环**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=PackageCycleTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: 当前 IAM 四包环在 allowlist 中

- [ ] **Step 2：移动 policy 模型**

  将 `PolicyCondition`、`PolicyOperand`、`PolicyOperandSource`、`PolicyComparisonOperator` 等只被 policy 使用的类型留在 `iam.policy`；把 `IamPolicy`/`IamPolicyEffect` 等跨包类型移到 `iam.policy` 或 `iam.spi`，确保 `iam` 不 import `iam.policy`。用 IDE move + import 修复。

- [ ] **Step 3：验证**

  Run: `./mvnw -o -B -pl seedj-iam-starter -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=PackageCycleTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS（只剩 parent↔spi 白名单）

- [ ] **Step 4：Commit**

  ```bash
  git add core seedj-iam-starter seedj-it config/archunit
  git commit -m "refactor: break iam package cycle"
  ```

### Task 3.4：删除死代码、收窄公开面

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/core/exception/BaseException.java`（删除）
- Modify: `core/src/main/java/cn/byteforce/seedj/core/exception/BizException.java`（删除）
- Modify: `core/src/main/java/cn/byteforce/seedj/core/exception/RemoteException.java`（删除）
- Modify: `core/src/main/java/cn/byteforce/seedj/core/exception/ApiError.java`（保留）
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/exception/AggregateNotFoundException.java`（已改）
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`（删除兼容构造器）
- Modify: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeCreate.java`（删除 NONE）
- Modify: `core/src/main/java/cn/byteforce/seedj/core/exception/ContractErrorCode.java`（COMMAND_REJECTED 用于 Cqrs 映射）
- Modify: `config/public-api.txt`
- Test: `core/src/test/java/cn/byteforce/seedj/core/exception/BaseExceptionTest.java`（删除/改写）
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/PublicApiSurfaceTest.java`

**Interfaces:**
- 删除 `BaseException`/`BizException`/`RemoteException`；`AggregateNotFoundException` 已是 `ContractException`。
- 删除 `CommandRuntime.Options` 8/9 参构造器，只保留全参与 `forTests`。
- 删除 `HrEmployeeCreate.NONE`。

- [ ] **Step 1：写/改测试**

  `PublicApiSurfaceTest` 在删除后运行，`config/public-api.txt` 必须更新；`ContractErrorHandlerTest` 不再引用 `BizException`；`AggregateNotFoundException` 仍映射 404。

- [ ] **Step 2：运行测试确认失败（允许清单未更新）**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=PublicApiSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL，列出删除的类型

- [ ] **Step 3：删除并更新**

  删除类与测试；`grep -rn 'BaseException\\|BizException\\|RemoteException'` 清空；更新 `docs`；运行 `-Dseedj.api.update=true` 生成新 `config/public-api.txt`。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -Panalysis verify`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=PublicApiSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add -A
  git commit -m "refactor: remove legacy exception model and dead code"
  ```

### Task 3.5：拆 `InMemoryWorkflowProvider`，恢复文件大小门禁

**Files:**
- Split: `seedj-infra-local/src/main/java/cn/byteforce/seedj/workflow/memory/InMemoryWorkflowProvider.java` →
  - `InMemoryWorkflowDefinitionStore.java`
  - `InMemoryWorkflowInstanceEngine.java`
  - `InMemoryHumanTaskService.java`
  - `InMemoryWorkflowQueryService.java`
  - `InMemoryWorkflowProvider.java`（facade，委托）
- Test: `seedj-infra-local/src/test/java/cn/byteforce/seedj/workflow/memory/InMemoryWorkflowProviderContractTest.java`（保持通过）
- Modify: `config/archunit/file-size-allowlist.txt`（删除条目）

**Interfaces:**
- `InMemoryWorkflowProvider` 继续实现 `WorkflowProvider`；构造器内部创建 4 个协作者；对外签名不变。

- [ ] **Step 1：写失败测试**

  `FileSizeGuardTest` 在 allowlist 清空后应失败（当前 2052 行）。

- [ ] **Step 2：按职责拆分**

  定义：
  - `InMemoryWorkflowDefinitionStore`：definition/version CRUD。
  - `InMemoryWorkflowInstanceEngine`：start/action/transition。
  - `InMemoryHumanTaskService`：task/assignment/claim。
  - `InMemoryWorkflowQueryService`：search/history。
  - facade 只做参数校验与委托。

- [ ] **Step 3：验证**

  Run: `./mvnw -o -B -pl seedj-infra-local -am test`
  Expected: PASS
  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=FileSizeGuardTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 4：Commit**

  ```bash
  git add seedj-infra-local config/archunit
  git commit -m "refactor: split in-memory workflow provider by responsibility"
  ```

### Task 3.6（Stretch）：core 去 Jackson 或写 ADR 明确 provided

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/core/spi/PayloadCodec.java`（若执行）
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/json/JacksonPayloadCodec.java`
- Modify: `core/pom.xml`（移除 `tools.jackson`）
- Modify: `CommandRuntime`、`TemporalCommandAgent`、`IdempotencyFingerprint`、`AuditChain` 等
- Create/Modify: `docs/adr/ADR-0012-core-payload-codec.md`（若执行）或 `ADR-0012-core-provided-jackson.md`（若不执行）

**Interfaces:**
- `PayloadCodec`：`<T> T decode(Map<String,Object>, Class<T>)`、`Map<String,Object> encode(Object)`、`String canonicalJson(Object)`。
- 若执行：core 不再 import `tools.jackson`；starter/jdbc 提供实现。
- 若不执行：ADR 明确 “core 零 Spring/零 JDBC；Jackson 为 provided 的线格式依赖”，并让 `CorePurityTest` 的规则与 POM 一致。

- [ ] **Step 1：决策**

  评估工作量；如果 T3.1–T3.5 已耗尽本轮预算，选择“ADR + POM 注释 + CorePurityTest 白名单”，不强行改。A- 允许 provided Jackson，但文档必须一致。

- [ ] **Step 2：若执行，写失败测试**

  `core` 编译时不应解析 `tools.jackson`；`PayloadCodec` 的 Jackson 实现测试；审计哈希向量仍通过。

- [ ] **Step 3：验证**

  Run: `./mvnw -o -B -pl core test`
  Run: `./mvnw -o -B -pl seedj-biz-starter -am test`
  Expected: PASS

- [ ] **Step 4：Commit**

  ```bash
  git add core seedj-biz-starter docs/adr
  git commit -m "refactor: isolate json behind payload codec (or document provided jackson)"
  ```

---

## M4：文档与发布

### Task 4.1：开发者文档重写与文档链接清零

**Files:**
- Modify: `README.md`
- Create: `docs/decision-guide.md`
- Create: `docs/command-contract.md`
- Create: `docs/read-model.md`
- Create: `docs/security.md`
- Create: `docs/testing.md`
- Modify: `docs/biz/README.md`
- Modify: `core/README.md`
- Modify: `seedj-jdbc-postgresql/README.md`
- Modify: `seedj-infra-local/README.md`
- Modify: `seedj-it/README.md`
- Modify: `docs/spi/README.md`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/PackageInfoTest.java`
- Create: 各模块至少一个 `src/main/java/**/package-info.java`（core 已有部分；infra/jdbc/biz/iam/server 补齐）
- Test: `DocLinkTest`

**Interfaces:**
- 文档必须与代码一致；所有示例可复制运行；不再出现旧模块名/旧包名/旧错误形状。

- [ ] **Step 1：写 `DocLinkTest` 扩展**

  增加断言：README 不包含旧模块名 `domain-jdbc-postgresql`、`seedj-outbox-starter`、`common/core`；`docs/biz` 的 403 示例使用 `ContractException(FORBIDDEN)` 或 `AccessDeniedException`；响应体为 `{code,message,details}`。
  `PackageInfoTest` 扫描每个模块的 `src/main/java`，断言至少存在一个 `package-info.java`，且内容包含“职责”和“依赖方向”两个关键词。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=DocLinkTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL

- [ ] **Step 3：重写文档**

  - `README.md`：一句话定位、模块图、quickstart、决策指南链接、已知偏差、CI 命令；不再把规范验收表放在首屏。
  - `docs/quickstart.md`：15 分钟本地跑 HR；create/get/update/list curl。
  - `docs/decision-guide.md`：temporal vs event-sourced 矩阵；默认 temporal；advanced 事件溯源。
  - `docs/command-contract.md`：descriptor、permission、validator、redactor、错误矩阵。
  - `docs/read-model.md`：通用 `/objects` vs 类型化查询；分页/过滤/时区。
  - `docs/security.md`：Caller、权限、CORS、派生密钥、dev auth。
  - `docs/testing.md`：测试金字塔、契约测试、Testcontainers、覆盖率 ratchet。
  - 修复 `docs/biz` 的 403 示例与响应体；修复 `core`/`jdbc`/`infra` README 的旧名称。
  - 为每个模块补至少一个 `package-info.java`，说明该模块职责、允许依赖、禁止依赖；作为 S7 证据。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=DocLinkTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add README.md docs core/README.md seedj-jdbc-postgresql/README.md seedj-infra-local/README.md seedj-it/README.md
  git commit -m "docs: rewrite developer docs and fix all links"
  ```

### Task 4.2：命令目录与 OpenAPI 对照

**Files:**
- Create: `config/conformance/commands.yaml`
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/CommandApiConformanceTest.java`
- Modify: `docs/conformance/api/command.openapi.yaml`
- Modify: `docs/conformance/api/query.openapi.yaml`

**Interfaces:**
- `commands.yaml` 每项：`type`、`permission`、`payloadSchema`、`objectType`、`eventTypes`。
- `CommandApiConformanceTest` 用 `CommandCatalog.descriptors()` 与 YAML 比较；缺项/多项失败。

- [ ] **Step 1：写失败测试**

  `CommandApiConformanceTest` 读取 `config/conformance/commands.yaml`，断言 catalog 类型集合相等；每个 descriptor 的 permission/objectType/payload simple name 与 YAML 一致；未知项失败。

- [ ] **Step 2：运行测试确认失败**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=CommandApiConformanceTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL（文件不存在）

- [ ] **Step 3：实现**

  - 新增 HR 两条命令的 YAML；`query.openapi.yaml` 增加 list 路径与 `ObjectPage`；`command.openapi.yaml` 增加 `CommandDescriptor` 元数据说明（不破坏既有路径）。
  - 如果未来命令多，允许测试生成 `commands.yaml` 的排序稳定输出。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=CommandApiConformanceTest,ApiAlignmentTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add config/conformance docs/conformance seedj-it/src/test
  git commit -m "test: bind command catalog to conformance api registry"
  ```

### Task 4.3：覆盖率终值、SpotBugs、依赖扫描、SBOM、API 兼容

**Files:**
- Modify: `pom.xml` / 各模块 POM（覆盖率终值）
- Modify: `config/coverage-baseline.json`
- Create: `config/ci/coverage-report.sh`
- Create: `config/ci/dependency-check.sh`
- Create: `docs/releasing.md`
- Create: `docs/api-compatibility.md`
- Modify: `.github/workflows/ci.yml`（release job）

**Interfaces:**
- 覆盖率终值：core/starter line ≥ 0.80、branch ≥ 0.60；infra/jdbc line ≥ 0.78、branch ≥ 0.60；iam line ≥ 0.75、branch ≥ 0.55。
- SpotBugs `-Panalysis` 在 CI 必跑；Medium 及以上零发现。
- 依赖扫描：`org.owasp:dependency-check-maven`，`failBuildOnCVSS=7`。
- SBOM：`-Psbom package`。
- API 兼容：发布 1.0.0 后 `-Pcompat` 比较。

- [ ] **Step 1：覆盖率提升 playbook**

  1. `config/ci/coverage-report.sh` 解析 `jacoco.xml`，按包输出 line/branch 与 missed lines 排序，生成 `target/coverage-hotspots.md`。
  2. 按 hotspot 补测试，优先级：`core/cqrs`（CommandRuntime/CommandCatalog/Idempotency）、`core/domain`（Sealing/TemporalRules/ObjectSchema/Page）、`core/audit`（CanonicalHasher/AuditChain）、`core/lock`/`core/outbox`、`core/iam`（policy/SCIM parser）、`core/workflow`（SPI validation）。
  3. 每个类至少覆盖：正常路径、边界值、非法输入、异常映射；record 类测试其构造期约束，不测试 getter。
  4. 对时间/哈希/分页使用 `@ParameterizedTest` + `@MethodSource`；对 `Sealing`/`ObjectVersion`/`CanonicalHasher` 加性质测试（同一输入稳定、半开区间、微秒单调）。
  5. 每完成一个包，更新 `config/coverage-baseline.json` 并提升模块 `<minimum>`；每次只提升到“当前实测 - 1%”，避免长期红灯。
  6. T4.3 完成时达到终值（core/starter 80/60，infra/jdbc 78/60，iam 75/55）；未达到则记录差距并延长 milestone，不得伪造。

- [ ] **Step 2：加 CI job**

  `release.yml` 或 `ci.yml` 的 tag job：`./config/ci/release.sh`、`dependency-check.sh`、`-Psbom package`、上传 SBOM/覆盖率报告。

- [ ] **Step 3：写发布文档**

  `docs/releasing.md`：版本策略、签名、SBOM、兼容性、回滚、制品检查清单。
  `docs/api-compatibility.md`：japicmp 基线、允许的变更、弃用窗口、公共 API 清单更新流程。

- [ ] **Step 4：验证**

  Run: `./mvnw -o -B -Panalysis verify`
  Expected: PASS
  Run: `./mvnw -o -B -Psbom -DskipTests package`
  Expected: `target/seedj-sbom.json` 存在
  Run: `./mvnw -o -B -Pcompat -DskipTests verify`（有 1.0.0 基线时）
  Expected: PASS

- [ ] **Step 5：Commit**

  ```bash
  git add pom.xml */pom.xml config docs .github
  git commit -m "build: enforce final coverage, analysis, sbom and compatibility gates"
  ```

### Task 4.4：A- 验收与记录

**Files:**
- Create: `docs/conformance/runs/2026-11-06-a-minus.md`
- Modify: `docs/assessment/2026-11-06-architecture-remediation-spec.md`（状态改为 verified）

**Interfaces:**
- 记录命令、结果、覆盖率、门禁、剩余风险。

- [ ] **Step 1：跑全量**

  ```bash
  ./mvnw -o -B clean verify
  ./mvnw -o -B -Panalysis verify
  ./mvnw -o -B -Pconformance -DskipTests verify
  ./mvnw -o -B -Psbom -DskipTests package
  bash config/ci/check-doc-links.sh
  bash config/ci/check-coverage.sh
  ./mvnw -o -B spotless:check
  ```
  Expected: 全绿（conformance 需规范仓库）。

- [ ] **Step 2：核对 A- 矩阵**

  逐条勾选 spec 第 2 节 A1–A7、E1–E10、C1–C7、S1–S7；未达标项不得写 PASS，列入“剩余风险”。

- [ ] **Step 3：写验收记录**

  表格：命令、结果、耗时、覆盖率、失败/跳过原因、负责人。附 `git rev-parse HEAD`。

- [ ] **Step 4：Commit**

  ```bash
  git add docs/conformance/runs docs/assessment
  git commit -m "docs: record a-minus acceptance evidence"
  ```

---

## 执行顺序与依赖

1. **M0 先做**：没有真实门禁，后续改动无法证明。
2. **T1.1 → T1.2** 是命令契约的基础；**T1.3 → T1.4 → T1.5/T1.7** 是黄金路径；T1.6/T1.8 可并行。
3. **T2.1 → T2.2 → T2.3** 是读侧链路；T2.4/T2.5 可并行。
4. **T3.2 包重构放在 M1/M2 合并后**，避免与业务改动冲突；按模块提交，便于回滚。
5. **T3.3/T3.4/T3.5** 在包重构后做，避免重复改 import。
6. **T4 最后做**，文档与门禁以最终代码为准。

## 风险与应对

| 风险 | 应对 |
|---|---|
| 包重构冲突大 | 冻结其他 PR；按模块分 4 个 PR；先合并 infra，再 jdbc，再 starter，再 iam；每个 PR 跑全量测试 |
| 覆盖率一次性到 80% 不现实 | 先修 include + ratchet；每阶段 +5%；以 diff-cover 90% 保新增；未达标不得宣称 A- |
| PostgreSQL 锁实现复杂 | 先用 lease 表 + `ON CONFLICT`；contract test 覆盖并发/过期；不实现公平性/可重入 |
| 锁表行增长 | 锁定 key 无界；运维定时 `DELETE expires_at < now() - interval '1 hour'`；后续加自动清理任务 |
| Schema 迁移破坏存量数据 | 1.0 未发布，无存量；真实部署用 expand/contract；V2 只加列，不删列 |
| OpenAPI 手写漂移 | `CommandApiConformanceTest` + `ApiAlignmentTest`；后续可换生成器 |
| 本地 dev auth 泄漏到生产 | `@Profile("local")` + `@ConditionalOnProperty` + 生产启动测试断言不存在 |
| 事件溯源路径被误用 | 文档标 advanced；`ArchitectureTest` 断言 starter 不自动暴露其 HTTP 入口；示例分离 |
| 时长超预期 | 以 M0/M1 为最小可交付；M2–M4 可按版本分批；A- 以验收矩阵为准，不按日期放水 |

## 交付物清单

- 代码：T1.1–T3.5 的 main/test 变更。
- 文档：`docs/quickstart.md`、`docs/decision-guide.md`、`docs/command-contract.md`、`docs/read-model.md`、`docs/security.md`、`docs/testing.md`、`docs/errors.md`、`docs/api-compatibility.md`、`docs/releasing.md`。
- 门禁：`config/ci/*`、`.github/workflows/ci.yml`、`config/coverage-baseline.json`、`config/public-api.txt`、`config/archunit/*`。
- 验收：`docs/conformance/runs/2026-11-06-a-minus.md`。
- ADR：`ADR-0009-command-descriptor.md`、`ADR-0010-object-schema.md`、`ADR-0011-package-namespace.md`、`ADR-0012-coverage-and-ci.md`（可在对应任务中创建）。

## Self-Review

- **Spec coverage:** 覆盖 A1–A7、E1–E10、C1–C7、S1–S7；T3.6 为 stretch，不阻塞 A-。
- **Step scan:** 每个任务有失败测试、实现、验证、commit；机械重构任务用移动/编译/门禁验证。
- **Type consistency:** `CommandDescriptor`、`ObjectSchema`、`TemporalSearch`、`CallerIdCodec`、`DerivedFieldRegistry`、`PostgresLockProvider` 的签名在任务间一致；包名按 T3.2 映射表统一。
- **Review Focus:** 非 UUID、缺依赖、Schema 漂移、未授权、并发创建、事件丢失、分页边界、本地启动均有对应测试。
- **Proportion:** 计划描述决策与验证，不代替实现；SQL/接口签名是必须固定的契约，未展开业务方法体。
