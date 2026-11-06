# 测试与覆盖率

本页是 seedj 的测试策略与门禁说明。门禁必须可执行：任何“约定”都应该能在 CI 或本地脚本里
找到对应命令，否则不算门禁。

## 测试分层

| 层 | 位置 | 依赖 | 说明 |
|---|---|---|---|
| 单元测试 | `<module>/src/test/java` | 无外部服务 | 纯逻辑、值对象、解析器、错误映射 |
| 契约测试 | `core` 的 `test-jar` | 无外部服务 | SPI 实现共用一套契约，例如 `TemporalStore`、`LockProvider` |
| Web 切片 | starter / server | Spring 上下文 | `MockMvc` 覆盖 HTTP 状态、错误信封、权限 |
| 集成测试 | `seedj-jdbc-postgresql`、`seedj-it` | Docker（Testcontainers） | PostgreSQL、迁移、分布式锁、端到端命令链路 |

约定：

- 先写失败测试，再实现；提交前跑受影响模块的 `verify`。
- 断言行为，不断言 mock 调用次数；契约测试只依赖 SPI，不依赖具体实现类。
- 测试输出必须干净；WARN/异常栈不是“正常噪音”。
- Testcontainers 测试在没有 Docker 时不得静默通过：要么失败并说明需要 Docker，要么用
  `@Testcontainers(disabledWithoutDocker = true)` 明确标记为跳过。

## 常用命令

```bash
# 单模块（含依赖模块）
./mvnw -o -B -pl core -am verify

# 全量
./mvnw -o -B clean verify

# 覆盖率报告（生成 target/site/jacoco/index.html）
./mvnw -o -B -pl core verify

# 覆盖率 ratchet 门禁
bash config/ci/check-coverage.sh

# 文档链接门禁
bash config/ci/check-doc-links.sh
```

`-o` 表示离线；首次构建或新增依赖时去掉 `-o`。CI 不使用 `-o`，除非仓库缓存已预热。

## 覆盖率 ratchet

`config/coverage-baseline.json` 是每个模块的**下限**：

```json
{
  "core": {"line": 0.52, "branch": 0.42}
}
```

规则：

1. 每个模块在 `verify` 阶段执行 `jacoco:check`，阈值来自模块 POM 的
   `seedj.coverage.line` / `seedj.coverage.branch`，与 JSON 基线保持一致。
2. `config/ci/check-coverage.sh` 解析 `target/site/jacoco/jacoco.xml`，任何模块低于基线
   超过 0.5 个百分点即失败；报告缺失打印 WARN 并跳过（本地只构建部分模块时正常）。
3. 基线只允许上浮。下调必须修改本文件并在 PR 说明原因；不得为了让红灯变绿而下调。
4. 变更行覆盖由 `diff-cover` 执行，阈值 90%；本地未安装时打印 SKIP，CI 必须安装并执行。
   基线分支默认 `origin/main`，可用 `DIFF_COVER_BASE` 覆盖。
5. 提升节奏：每完成一组测试，把模块阈值提升到“当前实测 − 1%”，避免长期红灯；最终目标见
   `docs/assessment/2026-11-06-architecture-remediation-spec.md` 第 6 节。

## 覆盖率缺口怎么补

```bash
# 生成按包排序的热点清单（T4.3 引入）
bash config/ci/coverage-report.sh
```

优先级：`core/cqrs`（CommandRuntime、CommandCatalog、幂等）→ `core/domain`
（Schema、时间规则、分页）→ `core/audit`（哈希与链）→ `core/lock`、`core/outbox` →
`core/iam`（policy、SCIM 解析）→ `core/workflow`（SPI 校验）。

每个类至少覆盖：正常路径、边界值、非法输入、异常映射。record 类测构造期约束，不测 getter。
时间/哈希/分页用 `@ParameterizedTest`；`Sealing`、`ObjectVersion`、`CanonicalHasher` 之类加
性质测试（同一输入稳定、半开区间、微秒单调）。

## 门禁清单

| 门禁 | 命令 | 失败含义 |
|---|---|---|
| 单元/集成测试 | `./mvnw -o -B verify` | 行为回归 |
| 覆盖率 | `jacoco:check` + `check-coverage.sh` | 低于模块基线或变更行不足 90% |
| 格式 | `./mvnw -o -B spotless:check` | 未按 Google Java Format (AOSP) 格式化 |
| 文档链接 | `bash config/ci/check-doc-links.sh` | Markdown 相对链接指向不存在的文件 |
| 架构 | `ArchitectureTest`、`SplitPackageTest`、`PackageCycleTest` | 依赖方向、split package、包环违规 |
| 文件大小 | `FileSizeGuardTest` | 主源码文件超过 600 行且不在 allowlist |
| 公共 API | `PublicApiSurfaceTest` | public 类型未登记到 `config/public-api.txt` |
| 许可 | `LicensePresenceTest` | 仓库或许可制品缺少 LICENSE/NOTICE |
