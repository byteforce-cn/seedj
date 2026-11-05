# 演进契约实施计划（P5）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 落地 L4 演进能力：兼容性门禁、迁移/回滚演练、弃用窗口政策与 ADR 模板。

**Architecture:** 兼容工件与门禁脚本放 `docs/conformance/`、`config/conformance/`；根 POM 增加 `-Pconformance` profile 调用规范 `compatibility-checker`；`core` 的 `Migration` 增加可选回滚脚本，`JdbcMigrationRunner` 增加事务化回滚与历史清理；政策文档放 `docs/conformance/`，ADR 模板引用兼容规则。

**Tech Stack:** Java 21、Maven 多模块、JUnit 5、AssertJ、Testcontainers、Python 3（仅 `-Pconformance`）。

**Spec:** `docs/superpowers/specs/2026-11-05-contract-conformance-design.md` §7.10 / §9 P5；规范 `06-evolution-contract/`。

## Global Constraints

- 产品代码不得出现规范名（不区分大小写），唯一例外：`EIS-{3位}-{3位}`、Schema URI `eis://…`、DDL 域类型。
- 不新增 Maven module；新文件只进 `docs/conformance/`、`config/conformance/`、既有模块。
- 兼容门禁默认不参与日常构建；`-Pconformance` 显式启用，规范工具缺席时失败，不静默跳过。
- 迁移回滚必须与迁移同事务语义；回滚脚本缺失时显式失败，不静默跳过。
- 弃用窗口 ≥2 MINOR 且 ≥90 天；ADR 必须引用兼容规则条目。

## Review Focus

- **门禁不会假绿**：字段删除/类型变更必须触发 `--fail-on MAJOR` 退出码 1。
- **回滚不会半途**：回滚脚本失败时历史行与表结构都保持回滚前状态（事务）。
- **checksum 覆盖回滚**：回滚脚本变更必须被 checksum 检出（新迁移）。
- **历史一致**：回滚后 `seedj_schema_history` 删除对应版本，重新 migrate 可再次应用。
- **工具缺席**：`-Pconformance` 在 `spec.root` 不存在时显式失败。

---

### Task 1: 兼容工件、门禁脚本与 `-Pconformance`

**Files:**
- Create: `config/conformance/compatibility.rules.yaml`（复制规范）
- Create: `config/conformance/check-compatibility.sh`
- Create: `config/conformance/gate-selftest.sh`
- Create: `config/conformance/README.md`
- Create: `docs/conformance/schema/*.schema.json`（复制数据/授权 Schema）
- Create: `docs/conformance/baseline/*.schema.json`（首版基线 = 当前 Schema）
- Create: `docs/conformance/api/event.asyncapi.yaml`、`resolution.api.yaml`、`attributes.api.yaml`
- Modify: `pom.xml`（新增 `conformance` profile）

**Interfaces:**
- Produces: `check-compatibility.sh <spec-root>`；对 `docs/conformance/baseline/*.schema.json` 与
  `docs/conformance/schema/*.schema.json` 逐对运行 checker，`--fail-on MAJOR`。
- `gate-selftest.sh <spec-root>`：临时生成字段删除（MAJOR，期望 1）与可选新增（MINOR，期望 0）两个 schema，证明门禁不是假绿。

- [x] **Step 1: 复制工件**

```bash
mkdir -p config/conformance docs/conformance/{schema,baseline,api}
cp /home/wxy/idp/bf/eis/06-evolution-contract/compatibility.rules.yaml config/conformance/
cp /home/wxy/idp/bf/eis/01-data-contract/*.schema.json docs/conformance/schema/
cp /home/wxy/idp/bf/eis/04-authz-contract/*.schema.json docs/conformance/schema/
cp docs/conformance/schema/*.schema.json docs/conformance/baseline/
cp /home/wxy/idp/bf/eis/02-operation-contract/event.asyncapi.yaml docs/conformance/api/
cp /home/wxy/idp/bf/eis/04-authz-contract/resolution.api.yaml docs/conformance/api/
cp /home/wxy/idp/bf/eis/04-authz-contract/attributes.api.yaml docs/conformance/api/
```

- [x] **Step 2: 写脚本并手工验证**

`check-compatibility.sh`：`set -euo pipefail`；`spec_root` 缺省 `../bf/eis`；缺 checker 时
`echo ... >&2; exit 1`；先跑 `gate-selftest.sh`；再逐对跑 `--fail-on MAJOR`。

`gate-selftest.sh`：用 `mktemp -d` 生成 `old.json` / `new-major.json` / `new-minor.json`；
`--old old --new new-major --fail-on MAJOR` 期望退出码 1；`new-minor` 期望 0；最后清理。

Run: `bash config/conformance/check-compatibility.sh /home/wxy/idp/bf/eis`
Expected: 所有 baseline 对判定 PATCH，退出码 0。

- [x] **Step 3: 加 Maven profile**

根 `pom.xml`：

```xml
<profile>
  <id>conformance</id>
  <properties>
    <spec.root>${project.basedir}/../bf/eis</spec.root>
  </properties>
  <build><plugins><plugin>
    <artifactId>maven-antrun-plugin</artifactId>
    <version>${maven-antrun-plugin.version}</version>
    <executions><execution>
      <id>compatibility-gate</id><phase>verify</phase><goals><goal>run</goal></goals>
      <configuration><target>
        <exec executable="bash" failonerror="true">
          <arg value="${project.basedir}/config/conformance/check-compatibility.sh"/>
          <arg value="${spec.root}"/>
        </exec>
      </target></configuration>
    </execution></executions>
  </plugin></plugins></build>
</profile>
```

- [x] **Step 4: 验证**

Run: `mvn -o -B -Pconformance -DskipTests verify`
Expected: BUILD SUCCESS，输出兼容判定。

- [x] **Step 5: 提交**

```bash
git add pom.xml config/conformance docs/conformance
git commit -m "feat: add conformance compatibility gate"
```

---

### Task 2: 迁移回滚与演练

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/core/Migration.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/core/MigrationChecksums.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/JdbcMigrationRunner.java`
- Test: `core/src/test/java/cn/byteforce/seedj/core/MigrationChecksumsTest.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/jdbc/JdbcMigrationRollbackTest.java`

**Interfaces:**
- `record Migration(int version, String description, String content, String rollback)`；保留 3 参构造器（rollback=null）。
- `MigrationChecksums.checksum(Migration)`：rollback 为 null 时与 `checksum(content)` 相同；非空时覆盖 rollback。
- `JdbcMigrationRunner.rollback(String scope, List<Migration> migrations, int targetVersion)`：逆序回滚 `version > targetVersion`，删除历史行；任一脚本缺失/失败则整体回滚。

- [x] **Step 1: 写失败测试**

`MigrationChecksumsTest`：同一 content、不同 rollback → checksum 不同；rollback=null 与
`checksum(content)` 相同。

`JdbcMigrationRollbackTest`（Testcontainers）：

```java
@Test void rollbackDropsObjectsAndRemovesHistory() { ... }
@Test void rollbackIsTransactionalOnScriptFailure() { ... }
@Test void rollbackWithoutScriptFailsExplicitly() { ... }
@Test void migrateAfterRollbackReapplies() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=JdbcMigrationRollbackTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错误（rollback 不存在）。

- [x] **Step 3: 实现**

- `Migration` 增加可选 `rollback`；校验非空白（非 null 时）。
- runner 的 `migrate` / `validate` / `isUpToDate` 改用 `MigrationChecksums.checksum(migration)`。
- `rollback`：同一 advisory lock + 事务；`ORDER BY version DESC`；每步执行 `migration.rollback()` 后
  `DELETE FROM seedj_schema_history WHERE scope=? AND version=?`；缺 rollback 抛
  `InfrastructureException(MIGRATION_FAILED, ...)`。
- 回滚脚本执行复用 `ScriptUtils`。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=JdbcMigrationRollbackTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/core seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/jdbc core/src/test/java/cn/byteforce/seedj/core/MigrationChecksumsTest.java
git commit -m "feat: add migration rollback rehearsal"
```

---

### Task 3: 弃用窗口、ADR 模板与 P5 记录

**Files:**
- Create: `docs/conformance/deprecation-policy.md`
- Create: `docs/conformance/change-list-template.md`
- Create: `docs/adr/ADR-template.md`
- Create: `docs/conformance/templates/migration.template.sql`
- Create: `docs/conformance/templates/rollback.template.sql`
- Create: `docs/conformance/runs/2026-11-05-p5.md`
- Modify: `docs/superpowers/plans/2026-11-05-evolution-contract.md`

- [x] **Step 1: 写政策与模板**

- 弃用窗口：≥2 MINOR 且 ≥90 天；删除必须带 migration guide 与 rollback；
- 变更清单模板：变更类型、兼容级别、requires、窗口起始/结束、回滚方案；
- ADR 模板：必须引用 `config/conformance/compatibility.rules.yaml` 的规则条目与级别。

- [x] **Step 2: 全量验证**

Run: `mvn -o -B verify`
Expected: BUILD SUCCESS。

Run: `mvn -o -B -Pconformance -DskipTests verify`
Expected: BUILD SUCCESS。

- [x] **Step 3: 写验收记录并提交**

```bash
git add docs config/conformance
git commit -m "docs: record p5 conformance evidence"
```

---

## 自查记录

- **Spec coverage：** 兼容门禁（Task 1）、迁移/回滚（Task 2）、弃用窗口/ADR（Task 3）。
- **Type consistency：** `Migration.rollback` 在 runner/checksum/test 三处同名；`checksum(Migration)` 与 `checksum(String)` 兼容。
- **Review Focus：** 假绿（Task 1 负例）、回滚事务（Task 2）、checksum 覆盖（Task 2）、历史（Task 2）、工具缺席（Task 1）。
