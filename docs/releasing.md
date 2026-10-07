# seedj 发布手册（Release Runbook）

> 适用对象：发布执行人 / 运维 / CI。**Agent 不得自动执行 `deploy`**（AGENTS.md §5）。
> 关联文档：[`api-compatibility.md`](./api-compatibility.md)（兼容门禁）、[`SUPPORT.md`](./SUPPORT.md)
> （支持政策）、[`../CHANGELOG.md`](../CHANGELOG.md)（变更定稿）、[`ROADMAP.md`](./ROADMAP.md)（里程碑 M6）。

## 0. 发布模型（已定决策）

| 项 | 决策 |
| --- | --- |
| 版本号 | SemVer；单点维护：根 POM `<revision>`（flatten 分发至全部模块）+ CHANGELOG |
| 分支 / 标签 | 保护分支 `main`；发布打标签 `vX.Y.Z`（指向定稿提交） |
| 制品仓库 | **POM 不写 `distributionManagement`**：投放目标由发布侧显式参数 + `settings.xml` 凭据决定（私服地址与账号由运维掌握，见 §3） |
| 签名 | 可选：`-Psign`（GPG）；是否强制以组织安全口径为准（未启用时无 `.asc`，首次启用需在私服验证通过后补签历史基线说明） |
| 发布物 | 15 个可消费模块 ×（主 jar + `-sources.jar` + `-javadoc.jar`）+ 各 pom（root / bom / 聚合）；`starter-coord-it` 集成套件模块双侧禁投（`maven.install.skip` / `maven.deploy.skip`） |
| SBOM | `-Psbom` 产出 `target/seedj-sbom.json`（CycloneDX），随发布记录归档 |
| 兼容门禁 | 发版前对上一基线跑 `-Pcompat`（japicmp，二进制破坏阻断），见 `api-compatibility.md` |
| 审批 | CI：受保护环境（人工审批）后由流水线执行 deploy；本地：发布执行人手工执行 |

## 1. 发布前检查清单（Checklist）

- [ ] 目标版本的变更全部合入 `main`；CI 主门禁（`.github/workflows/ci.yml`）与夜间集成档
      （`it.yml`）最近一次全绿
- [ ] `CHANGELOG.md` 定稿：`[Unreleased]` → `## [X.Y.Z] - yyyy-MM-dd`；核对 SemVer（删除/改名
      public API、默认行为变化 = MAJOR；兼容新增 = MINOR；修复 = PATCH）
- [ ] 根 POM `<revision>` 更新为 `X.Y.Z` 并提交（下一开发线切回 `X.(Y+1).0-SNAPSHOT`）
- [ ] 兼容门禁：`./mvnw -B -Pcompat verify -DskipTests -Dseedj.compat.baseline=<上一发布版本>`
      （首个正式版本 1.0.0 无上一基线，跳过）
- [ ] 全量本地验证：`./mvnw -B -T1C verify`
- [ ] 集成套件：`./mvnw -B -pl starter/coord-it -am verify -Pit`（需本机 Docker + coord 镜像）
- [ ] 发布演练（§4，file 仓库 dry-run）通过

## 2. 制品构建

```bash
# 1) 全量验证（测试 + 覆盖率门禁 + 零测试守卫）
./mvnw -B -T1C verify

# 2) Release 制品（source + javadoc 粘附）
./mvnw -B -Prelease -DskipTests package

# 3) SBOM（随发布记录归档）
./mvnw -B -Psbom -DskipTests package      # → target/seedj-sbom.json

# 4) 可选：GPG 签名（组织启用签名时）
./mvnw -B -Prelease,sign -DskipTests package
```

## 3. 投放（deploy）

本仓库**不在 POM 中配置 `distributionManagement`**——投放目标由命令显式给出：

```bash
# release 版本
./mvnw -B -Prelease -DskipTests deploy \
  -DaltReleaseDeploymentRepository='<repoId>::default::<repoUrl>'

# snapshot 版本（开发线，如需要）
./mvnw -B -Prelease -DskipTests deploy \
  -DaltSnapshotDeploymentRepository='<repoId>::default::<snapshotRepoUrl>'
```

- `<repoId>`：与 `~/.m2/settings.xml` 中 `<server><id>` 一致（凭据来源，运维配置）；
- `<repoUrl>`：例 `https://nexus.example.com/repository/maven-releases/`；
- 未指定 alt 参数的裸 `mvn deploy` 在本仓库**被禁止**（AGENTS.md §2：可能推向私服默认目标）。

### 私服侧前置条件（运维）

1. 建立 `maven-releases` / `maven-snapshots` 仓库；release 仓库建议**禁止覆盖**、启用校验和策略；
2. `~/.m2/settings.xml` 配置对应 `<server>` 凭据；CI 场景以加密 Secret 注入（见 §6）；
3. 首次投放后核对：15 个模块均含 主 jar / sources / javadoc + 各 pom，`bom` 可被独立导入解析。

## 4. 发布演练与验证（file 仓库 dry-run）

无需私服即可全链路验证制品结构（与真实投放同一插件路径）：

```bash
./mvnw -B -Prelease -DskipTests deploy \
  -DaltDeploymentRepository='seedj-dryrun::default::file:///tmp/seedj-release-dryrun/repo'

# 校验：jar 数（15 模块 × 主/sources/javadoc = 45）、pom、校验和
find /tmp/seedj-release-dryrun/repo -name '*.jar' | wc -l
find /tmp/seedj-release-dryrun/repo -name '*.sha1' | wc -l
```

> 注：`altDeploymentRepository` 对 release / snapshot 版本均生效（演练通用）；
> §3 真实投放按版本使用 `altReleaseDeploymentRepository` / `altSnapshotDeploymentRepository` 专用参数。

真实投放后的消费侧验证：在干净目录建一个仅 `import bom` + 引 `starter-web` 的最小工程，
`mvn dependency:resolve` 确认版本解析与下拉成功。

发布完成后打标签：

```bash
git tag -a vX.Y.Z -m 'seedj X.Y.Z'
git push origin main --follow-tags
```

## 5. 回滚与补救

| 场景 | 处置 |
| --- | --- |
| 制品缺陷（可等待） | 修复后发 **PATCH** 版本；release 仓库禁覆盖时不得重推同版本 |
| 严重缺陷（紧急） | 私服下架/标记问题版本 + 内部公告；修复后发 PATCH 并推动升级 |
| 投放中断（部分模块缺失） | 重跑 deploy 命令补齐缺失坐标（按仓库覆盖策略需运维配合） |
| 兼容性事故 | 按 [`api-compatibility.md`](./api-compatibility.md) 走 MAJOR 或豁免评审；补 `docs/upgrade-x.y.md` 迁移说明 |

## 6. CI 承接（GitHub Actions）

- `.github/workflows/release.yml`：`workflow_dispatch` 手选 `dry-run / deploy`；
  `deploy` 走受保护环境 `release`（配置审批人 + `MAVEN_SETTINGS_XML` / `SEEDJ_RELEASE_REPO_ID` /
  `SEEDJ_RELEASE_REPO_URL` secrets 后生效）。
- 组织若使用其它平台（GitLab / Jenkins），按 §2–§4 命令原样移植；凭据注入方式以平台能力为准。

## 7. 发布记录（每版追加）

| 版本 | 日期 | 执行人 | 基线 | 签名 | SBOM | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| （待 1.0.0 首发） | | | — | | | |
