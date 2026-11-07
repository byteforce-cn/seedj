# 测试与覆盖率

本页是 seedj 的测试策略与门禁说明。门禁必须可执行：任何“约定”都应该能在 CI 或本地脚本里
找到对应命令，否则不算门禁。

## 测试分层

| 层 | 位置 | 依赖 | 说明 |
|---|---|---|---|
| 单元测试 | `<module>/src/test/java` | 无外部服务 | 纯逻辑、值对象、解析器、错误映射 |
| 业务规则 | `<module>/src/test/java` + `CommandHarness` | 无 Spring | 命令链路、幂等、writeOnly、状态机；见 `HrEmployeeRulesTest` |
| 契约测试 | `core` 的 `test-jar` | 无外部服务 | SPI 实现共用一套契约，例如 `TemporalStore`、`LockProvider`、`WorkflowProvider` |
| Web 切片 | starter / server | Spring 上下文 | `MockMvc` 覆盖 HTTP 状态、错误信封、权限 |
| 架构/文档门禁 | `seedj-it` | 编译产物 | ArchUnit、SplitPackage、PackageCycle、PublicApi、DocLink、FileSize、PackageInfo |
| 集成测试 | `seedj-jdbc-postgresql`、`seedj-it` | Docker（Testcontainers） | PostgreSQL、迁移、分布式锁、端到端命令链路 |

约定：

- 先写失败测试，再实现；提交前跑受影响模块的 `verify`。
- 业务规则用 `CommandHarness`（`seedj-infra-local` 的 test-jar）直接构造内存运行时；
  `@SpringBootTest` 只留给 HTTP、安全、装配与数据库契约。
- 断言行为，不断言 mock 调用次数；契约测试只依赖 SPI，不依赖具体实现类。
- Testcontainers 测试在没有 Docker 时不得静默通过：要么失败并说明需要 Docker，要么用
  `@Testcontainers(disabledWithoutDocker = true)` 明确标记为跳过。

## 业务规则测试：CommandHarness

`CommandHarness` 提供内存命令/对象存储、固定时钟、caller、写范围与 schema 校验；
业务测试只需保留工号、派生字段、组织目录等业务依赖。

```java
CommandHarness harness = CommandHarness.of(moduleRegistry(), caller)
        .clockAt("2026-10-08T09:00:00Z");
CommandReceipt created = harness.execute("hr.employee.create",
        Map.of("name", "张三", "org_unit_id", "D01"), "rule-1");

assertThat(harness.object("employee", created.locator()).fields())
        .containsEntry("status", "pending_onboarding");
```

示例：`seedj-server/hr/src/test/java/.../HrEmployeeRulesTest.java`。它替代了原来的
`@SpringBootTest` 状态机测试；HTTP 状态码仍由 `HrEmployeeHttpTest` 覆盖。

## 常用命令

```bash
./mvnw -o -B -pl core test                          # 契约单测
./mvnw -o -B -pl seedj-infra-local -am test         # 内存适配器 + core
./mvnw -o -B -pl seedj-jdbc-postgresql -am test     # PostgreSQL 适配器（Docker）
./mvnw -o -B -pl seedj-biz-starter -am test         # 装配 + Web 切片
./mvnw -o -B -pl seedj-iam-starter -am test         # IAM
./mvnw -o -B -pl seedj-it -am test                  # 架构/文档门禁与端到端
./mvnw -o -B -Panalysis verify                      # 全量 + SpotBugs（Medium 阻断）
bash config/ci/verify.sh                            # 主门禁：测试 + 覆盖率 + 架构 + 链接 + 格式
```

## 覆盖率 ratchet

- 每个有主源码的模块在 `verify` 阶段执行 JaCoCo `check`；阈值在模块 POM 的
  `seedj.coverage.line` / `seedj.coverage.branch`。
- 基线记录在 `config/coverage-baseline.json`；`config/ci/check-coverage.sh` 解析
  `target/site/jacoco/jacoco.xml`，任何模块低于基线 0.5 个百分点即失败。
- 只允许上调；下调必须改基线文件并在 PR 说明原因。
- 变更行目标 ≥ 90%；CI 安装 `diff-cover` 后按 `--fail-under=90` 执行。
- 热点报告：`bash config/ci/coverage-report.sh`（写入 `target/coverage-hotspots.md`）。
- 当前各模块的行/分支覆盖率由基线文件与门禁脚本校验，只允许上调。

## 架构与文档门禁

| 测试 | 断言 |
|---|---|
| `ArchitectureTest` | core 无 Spring/JDBC；biz 不反向依赖 IAM；内存实现不依赖 JDBC |
| `SplitPackageTest` | 同一个包只属于一个模块；允许清单为空 |
| `PackageCycleTest` | 包级依赖无环；只剩 parent↔spi 白名单 |
| `PublicApiSurfaceTest` | `config/public-api.txt` 与编译产物完全一致 |
| `FileSizeGuardTest` | 单文件 ≤ 600 行；允许清单为空 |
| `PackageInfoTest` | 每个模块至少一个 package-info，说明职责与依赖方向 |
| `DocLinkTest` | 所有 Markdown 相对链接可解析；README 不含退役模块名 |
| `CommandApiConformanceTest` | `config/conformance/commands.yaml` 与 `CommandCatalog` 一致 |

新增 public 类型后运行：

```bash
./mvnw -o -B -pl seedj-it -am test -Dtest=PublicApiSurfaceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dseedj.api.update=true
```

并提交 `config/public-api.txt` 的 diff。

## SpotBugs 与依赖扫描

- `-Panalysis` 在 `verify` 阶段执行 SpotBugs，Medium 及以上默认**阻断**；
  排除项必须带原因，集中在 `config/spotbugs-exclude.xml`。
- `bash config/ci/dependency-check.sh` 是 CI/发布专用门禁（需要在线仓库与 NVD 数据），
  `failBuildOnCVSS=7`；离线调用显式失败，不静默跳过。

## 相关文档

- [API 兼容性](api-compatibility.md)
