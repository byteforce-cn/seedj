# seedj API 兼容性政策（兼容门禁）

> 状态：v1（配合 1.0.0 首发落地）；工具：japicmp（`-Pcompat`）；
> 关联：[`AGENTS.md`](../AGENTS.md) §5（SemVer 与向后兼容承诺）、[`releasing.md`](./releasing.md) §1（发布前检查）。

## 1. 兼容性承诺

- **二进制向后兼容（硬门禁）**：已发布的 public / protected API（类、方法、字段、方法签名、
  可见性）在 MINOR / PATCH 内不得破坏；
- **源码兼容（告警级）**：当前仅报告不阻断（升级为硬门禁列入 ROADMAP 队列）；
- **破坏性变更**：一律进入下一个 MAJOR，并提供 `docs/upgrade-x.y.md` 迁移说明（AGENTS.md §5）。

## 2. 门禁执行

```bash
# 默认基线 1.0.0；指定其它基线：
mvn -B -Pcompat verify -DskipTests -Dseedj.compat.baseline=<基线版本>
```

- 前提：基线版本制品可从本地仓库 / 私服解析（`mvn install` 或私服拉取）；
- 判定口径：`onlyModified=true`；**二进制不兼容 → 构建失败**；源码不兼容 → 报告输出；
- 豁免：`-Dseedj.compat.skip=true`；测试套件模块 `starter/coord-it` 已在自身 POM 永久豁免
  （不产出可消费构件）。

## 3. 豁免流程（白名单）

1. PR 描述必须列明：破坏项清单、根因、影响面（哪些业务方）、迁移方式；
2. 常规路径：进入下一个 **MAJOR**；
3. 组织特批路径（尽量不用）：评审通过后临时以 `-Dseedj.compat.skip=true` 执行发布，
   并在 CHANGELOG 与该 PR 中留痕；**禁止**「先发布、后补说明」。

## 4. 基线维护

- 1.0.0 发布即固定为基线（私服坐标 + SBOM 一并归档）；
- 每次 **MAJOR** 发布后滚动更新：基线坐标、`docs/releasing.md` §7 发布记录、
  根 POM `seedj.compat.baseline` 默认值。

## 5. CI 接入（现状与计划）

- 现状：`-Pcompat` 由发布执行人在发版检查清单中手动执行（见 `releasing.md` §1）；
- 计划：纳入 `release.yml` 构建 job（首个基线发布后启用），或拉取基线到 runner 本地仓库后执行。
