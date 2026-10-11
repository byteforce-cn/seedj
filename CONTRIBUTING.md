# 贡献指南（Contributing）

感谢参与 seedj！本文档面向人类贡献者；仓库内自动化代理/脚本的行为约束另见 [`AGENTS.md`](AGENTS.md)。

## 开发环境

| 项 | 要求 |
| --- | --- |
| JDK | 21+（编译目标以根 [`pom.xml`](pom.xml) `<java.version>` 为准） |
| 构建 | Maven Wrapper（`./mvnw`，固定 Maven 3.9.x），无需预装 Maven |
| Docker | 仅集成套件（`starter/coord-it`，`-Pit`）需要 |

## 常用命令

```bash
# 全量构建 + 单测 + 覆盖率门禁（提交前必须通过）
./mvnw -B -T1C verify

# 单模块（含依赖）验证
./mvnw -B -pl starter/web -am verify

# 静态分析（SpotBugs，CI 主门禁已接入）
./mvnw -B -Panalysis -DskipTests verify

# coord 集成套件（需本机 Docker + coord 镜像，镜像不可用即硬失败）
./mvnw -B -pl starter/coord-it -am verify -Pit
```

- 构建/测试日志建议写入 `/tmp/seedj-build/`，不要留在仓库内；
- 质量门禁：common 域行覆盖率 ≥ 80%、starter 域 ≥ 70%；「有主源码但未执行任何测试」的模块会被守卫拦截。

## 代码与文档规范（摘要）

完整口径见 [`AGENTS.md`](AGENTS.md)，核心约定：

- JDK 21；**禁止使用 `record`**（DTO/VO/配置类一律 `final class` + 手写不可变实现）；
- 所有 public/protected 类型、方法、字段必须有 Javadoc（`@author`/`@since`/`@param`/`@return`）；
- 4 空格缩进、UTF-8、LF；
- 对外方法不返回 `null`（返回空集合 / `Optional`）；日志统一 SLF4J；
- 新增第三方依赖：说明用途与替代方案，设 `<optional>`/`<provided>`，版本进 `bom` 统一管理；
- 对外 API 兼容性承诺与豁免流程见 [`docs/api-compatibility.md`](docs/api-compatibility.md)。

## 提交与 PR

1. 分支命名：`feature/*`、`fix/*`、`chore/*`；不要直接推送 `main`；
2. 提交信息：`<type>(<scope>): <subject>`，type ∈ `feat|fix|refactor|docs|test|chore|build`；
3. PR 前本地通过 `./mvnw -B -T1C verify`；行为变更同步更新 [`CHANGELOG.md`](CHANGELOG.md)；
4. 破坏性变更：先在 `docs/adr/` 提 ADR 并写明迁移方式，进入下一个 MAJOR；
5. 禁止 `git push --force` 覆盖共享分支历史；不要提交 `target/`、`*.class`、IDE 配置与临时文件；
6. CI 会执行：全量 verify（单测 + 覆盖率 + 零测试守卫 + SpotBugs）；coord 集成套件按夜间档运行。

## 报告问题

- 功能缺陷 / 需求建议：使用 GitHub Issues（模板会引导你提供最小复现）；
- **安全漏洞**：请勿公开 issue，按 [`SECURITY.md`](SECURITY.md) 私密报告。

## 许可

本项目采用 Apache License 2.0。向本项目提交贡献，即表示你同意以该许可对本贡献进行授权（见 [`LICENSE`](LICENSE)）。
