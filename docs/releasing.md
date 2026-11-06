# 发布流程

本页是 `1.0.0` 及以后版本的发布清单。**判定权威是根 [pom.xml](../pom.xml) 中的
`release` / `sign` / `sbom` / `compat` profile 与 [兼容性规则](api-compatibility.md)**；
本页只描述执行顺序与验收证据，不重新定义规则。

## 1. 版本与变更记录

- 版本号由 `revision` 属性（`flatten-maven-plugin` 的 `resolveCiFriendliesOnly`）统一管理，
  发布时 `-Drevision=1.0.0`；不要在子模块里单独写版本。
- 每个不兼容变更必须有 ADR，并在 [变更清单](conformance/change-list-template.md) 的副本里登记。
- `1.0.0` 尚未发布，因此本轮允许破坏性变更；发布后按 [弃用策略](conformance/deprecation-policy.md) 执行窗口期。

## 2. 构建与验收

```bash
./mvnw -B clean verify                 # 全量测试（含 Testcontainers PostgreSQL）
./mvnw -B -Pcompat -DskipTests verify  # 基线存在时的二进制兼容门禁
./mvnw -B -Pconformance -DskipTests verify  # 规范兼容性对照（需规范仓库）
```

`verify` 同时执行 Spotless 格式检查、JaCoCo 覆盖率门禁与各模块测试；
任何一项失败都视为不可发布。

## 3. 制品与签名

```bash
./mvnw -B -Prelease,sign -DskipTests deploy     # 源码包 + Javadoc + GPG 签名
./mvnw -B -Psbom package                        # root target/seedj-sbom.json（CycloneDX）
```

- GPG 密钥默认取私钥，CI 用 `-Dgpg.keyname=<key>` 或 secrets 注入；未启用 `sign` 时构建不依赖 GPG。
- 每个制品的 `META-INF/` 必须包含 `LICENSE` 与 `NOTICE`（根 POM 的 `copy-license-notice` 负责），
  `LicensePresenceTest` 对样本制品做断言。
- SBOM 随制品归档；依赖变更导致的许可变化在这一步暴露。

## 4. 发布后

- 把 `docs/conformance/schema/` 快照为新的 `docs/conformance/baseline/`，冻结该版本；
  下一次变更只改 `schema/`。
- 记录一次 [conformance run](conformance/runs/) 作为该版本的验收证据。
- 回滚：按版本号重新部署上一个制品即可；数据库侧遵循各能力自带迁移的 rollback 模板
  （[templates/](conformance/templates/)），不手工改 schema。

## 5. 制品检查清单

- [ ] `verify` 全绿（含 Spotless、覆盖率、DocLink、许可）
- [ ] 不兼容变更均有 ADR 与变更清单
- [ ] `config/public-api.txt`（T0.3 引入）与实际公共 API 一致
- [ ] 制品 `META-INF/` 含 `LICENSE`、`NOTICE`
- [ ] SBOM 已生成并随制品归档
- [ ] 签名可校验（`gpg --verify`）

## 相关入口

- 兼容性判定与公共 API 维护：[api-compatibility.md](api-compatibility.md)
- 兼容性门禁工具：[config/conformance/README.md](../config/conformance/README.md)
- 文档索引：[docs/README.md](README.md)
