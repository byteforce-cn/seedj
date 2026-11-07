# 贡献指南

感谢参与 Seedj。本文档面向人类贡献者；架构决策与设计文档在维护者内部仓库，公开侧只体现
在代码、`docs/`、issue 与 PR 记录中。

## 环境

| 项 | 要求 |
|---|---|
| JDK | 21（编译目标见根 `pom.xml` 的 `<java.version>`） |
| 构建 | 仓库自带 Maven Wrapper（`./mvnw`），无需预装 Maven |
| Docker | 仅 PostgreSQL 集成测试需要（Testcontainers） |
| Node | 20+，仅文档站使用 |

## 常用命令

```bash
# 全量门禁：测试 + 覆盖率 + 架构约束 + 格式（提交前必须通过）
MAVEN_FLAGS=-o bash config/ci/verify.sh

# 单模块
./mvnw -B -pl seedj-server/hr -am test

# 文档站
npm ci && npm run docs:lint && npm run docs:build && npm run docs:nav
```

仓库脚本默认离线（`MAVEN_FLAGS=-o`）；首次构建去掉该变量，或先执行
`./mvnw -B dependency:go-offline`。CI 使用 `MAVEN_FLAGS=""`。

**本地请用 JDK 21。** CI 的格式化与编译行为以 JDK 21 为准，其他 JDK 版本可能给出不同结果。
`spotless` 会把结果缓存在 `target/spotless-index`；怀疑本地与 CI 不一致时，删掉该缓存再跑
`./mvnw -B spotless:check`。

## 代码约定

- 格式由 `spotless`（google-java-format）统一，`verify.sh` 会检查；本地可跑
  `./mvnw -B spotless:apply`。
- 公开类型与成员需要 Javadoc；新增行为要带测试。
- 新增第三方依赖：说明用途，版本进 `bom` 统一管理；`core` 保持零 Spring。
- 不提交构建产物（`target/`、`.flattened-pom.xml`、`node_modules/`、`docs/.vitepress/dist`）。

## 分支与 PR

1. 从最新 `main` 开分支：`feat/...`、`fix/...`、`docs/...`、`chore/...`；不要直接推 `main`。
2. 提交信息：`<type>(<scope>): <subject>`，type ∈ `feat|fix|refactor|docs|test|chore|build`。
3. PR 前本地跑 `verify.sh`；行为变更同步更新 `CHANGELOG.md`。
4. 破坏性变更：先开 `decision` issue 说明动机与迁移路径，再提 PR。
5. 不提交生成物、IDE 配置与临时文件；不要 force-push 共享分支。

CI 会执行 `verify`（测试 + 覆盖率 + 架构约束 + 格式）、`conformance`（契约对齐）与
`docs`（文档构建与死链），三者全绿才能合并。

## 文档

- 产品文档在 `docs/`，用 VitePress 构建；新增页面必须挂到 `docs/.vitepress/config.mts`
  的侧栏，否则 `docs:nav` 会失败。
- 页面之间用相对链接，不写跨仓路径。
- 发版说明写在 `CHANGELOG.md`。

## 报告问题

- 缺陷与需求：GitHub Issues（模板会引导最小复现）。
- 安全漏洞：不要开公开 issue，见 [SECURITY.md](SECURITY.md)。

## 许可

提交即表示同意以 [Apache License 2.0](LICENSE) 授权。
