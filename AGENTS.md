
# AGENTS.md — 本仓库的代理/自动化约束

> 本文件面向在此仓库中工作的自动化代理（AI Agent）、脚本与协作者，用于约束行为边界、
> 统一工程约定。**修改任何代码或配置前请先通读本文件。**

## 0. 项目定位

本仓库是一个 **Java 基础类库（base library）项目**，为组织内各业务系统
提供通用能力沉淀，例如：

- 通用工具类、常量、枚举、异常体系
- 统一返回体 / 分页模型 / DTO 基类
- 鉴权、加密、签名等基础安全组件
- 配置、序列化、日志、追踪等基础设施封装
- 公共注解、AOP 切面、SPI 扩展点

**特性**：本仓库被上游业务以 Maven 依赖方式引入，因此：

- **对外 API 一经发布应保持向后兼容**，破坏性变更必须走大版本；
- **不写业务逻辑**，只提供可复用能力。

**项目口径**：本项目统一称 **seedj**（groupId `cn.byteforce.seedj`）；新增的 POM
`name`/`description`、README、文档与源码注释一律使用 seedj，不要回退为“seed”。

**技术基线**：JDK 21 + Maven。  
**明确禁止使用 `record`**（包括 `record` 声明、`record` 模式解构），即使 JDK 21 原生支持。
DTO / VO / 配置类 / 不可变值对象一律使用 `final class` + 手写不可变实现（final 字段 + 显式 getter/构造器；
仓库基线**不引入 Lombok**，如需引入需先评审并同步构建配置）。

---

## 1. 临时文件（强制）

- **所有临时文件、草稿脚本、日志、下载产物一律放系统 `/tmp` 下**（建议 `/tmp/seedj-*` 前缀，
  如 `/tmp/seedj-pr/`、`/tmp/seedj-tmp/`）。
- **禁止**在仓库工作区内创建 `tmp/`、`scratch/` 等临时目录或在其中写文件——会污染源码树，
  干扰工作区视图与管理（即使已被 `.gitignore` 忽略也不允许）。
- 仓库 `tmp/` 下**既有**的历史脚本/产物保持原位，**不要迁移、删除或改名**（避免与既有流程/会话冲突）；
  记忆/文档中的旧路径 `/root/seedj/tmp/...` 仍然有效，以原处为准。
- 构建产物按各自约定入 `target/`（已 ignore）；其余大文件、备份、探测结果一律放 `/tmp`。

---

## 2. 构建与工具链

- **构建工具**：Maven（`pom.xml`）；命令行统一走仓库自带 `./mvnw`（wrapper 固定 Maven 3.9.x，已纳入基线）。
  **不要**擅自切换为 Gradle，也不要再引入其它构建包裹。
- **JDK**：**21**，以 `pom.xml` 中 `<maven.compiler.release>` / `<java.version>` 为准。
  - 不要擅自升级或降级语言级别；
  - **不允许使用 `record`**，即使 JDK 21 支持；
  - 如发现构建配置未对齐 JDK 21，先询问，不要自行修改。

- **禁止**：直接在仓库根跑 `mvn deploy`（会推到私服）；如确需发布见 §6。
- 构建/测试日志、`mvn -X` 输出写到 `/tmp/seedj-build/*.log`，不要留在仓库内。

---

## 3. 代码规范

- **格式**：`4 空格缩进`，UTF-8，LF 换行。若仓库已有 `.editorconfig` / `checkstyle.xml`，
  以其为准，不要额外新增格式化配置。
- **注释**：
  - 所有 **public / protected** 类型、方法、字段必须有 Javadoc；
  - Javadoc 使用 `@author`、`@since`（新类必填）、`@param`、`@return`、`@throws`；
  - 内部实现注释用中文，对外 Javadoc 建议使用中文（与既有风格保持一致）。
- **命名**：遵循 JDK 约定；工具类用 `XxxUtils`，常量类 `XxxConstants`，异常 `XxxException`。
- **不可变优先**：
  - 工具类应 `final` + 私有构造；
  - DTO / VO / 值对象优先 `final` 字段 + 手写 getter（仓库基线不引入 Lombok）；
  - **禁止使用 `record`**，包括但不限于：
    - `public record Xxx(...) {}`
    - `record` 模式解构，如 `if (o instanceof Point(int x, int y))`
    - 用 `record` 作为 DTO、VO、配置类、异常载体、SPI 参数对象。
- **空值处理**：对外方法入参默认 **不返回 null**（返回空集合 / `Optional`）；判空统一使用
  `Objects.isNull` / `StringUtils` 等已有工具，不要重复造轮子。
- **日志**：统一使用 SLF4J（`private static final Logger log = LoggerFactory.getLogger(X.class);`），
  **禁止**在库代码中使用 `System.out` / `printStackTrace()`。
- **异常**：库对外抛出统一的自定义异常（如 `BaseException` 及其子类）；不要 `throw new RuntimeException(msg)`；
  捕获异常时不得吞掉（`catch (Exception ignored)` 需附理由注释）。
- **依赖**：新增依赖前先确认 `dependencyManagement` / `BOM` 中是否已有同能力组件；
  新增第三方依赖必须：
  1. 写清用途与替代方案；
  2. 使用 `<scope>provided</scope>` 或 `optional=true`（避免传递污染下游）；
  3. 更新 `pom.xml` 的 `<dependencyManagement>` 统一版本。
- **语言特性**：语言级别为 JDK 21，可使用 `var`、文本块、switch 表达式、`instanceof` 模式匹配等；
  但除 `record` 明确禁止外，新语法应以可读性和既有风格为准，不要为了用而用。

---

## 4. 测试

- 单元测试：JUnit 5（或仓库既有版本，勿混用 JUnit 4/5）。
- 断言：AssertJ 或 JUnit 内置断言，禁止用 `System.out` 断言。
- 覆盖率：新增/修改的 public 方法应至少有一条测试；纯 getter/常量类可豁免。
- 覆盖率门禁（`verify` 强制）：common 域（core/web/security/data）行覆盖 ≥ 80%，
  starter 域 ≥ 70%（JaCoCo `includes` 为 **class 文件路径模式**，勿改回点分包名）；
  同时校验 `jacoco.exec` 存在——「有主源码但未执行任何测试」的模块会被守卫拦截
  （确无测试需求时在模块 POM 声明 `seedj.jacoco.guard.skip=true` 并注明原因）。
- 集成测试：统一命名 `*IntegrationTest.java`，位于 `starter/coord-it`，经 surefire `-Pit` 档运行
  （默认 `verify` 排除）；基于 Testcontainers 真实容器（镜像/容器不可用时硬失败，**不静默跳过**）。
  **不得依赖组织外部中间件实例**。
- 测试数据、临时数据库、随机种子文件写入 `/tmp/seedj-test/`，**不得**入库。
- 测试代码中同样**禁止使用 `record`**；测试 DTO / Fixture 使用普通 `final class` 或 Builder。

---

## 5. 版本与发布

- 版本号语义化：`MAJOR.MINOR.PATCH`。
  - 新增向后兼容能力 → `MINOR`；
  - 修复 → `PATCH`；
  - 删除/改名 public API、改变默认行为 → `MAJOR`，并在 `docs/upgrade-x.y.md` 写明迁移步骤。
- 发布由 CI/人工执行 `mvn deploy`；**Agent 不得自动执行 `deploy`**。
  发布流程与检查清单见 `docs/releasing.md`；兼容性门禁与豁免见 `docs/api-compatibility.md`。
- 变更需更新 `CHANGELOG.md`（若存在），格式 `## [x.y.z] - yyyy-MM-dd`。

---

## 6. Git 与提交

- 分支：`feature/*`、`fix/*`、`chore/*`；不要直接推 `main`/`master`。
- 提交信息：`<type>(<scope>): <subject>`，type ∈ `feat|fix|refactor|docs|test|chore|build`。
- **禁止**：`git push --force` 到共享分支；提交 `target/`、`*.class`、IDE 配置（`.idea/`、`*.iml`）。
- 提交前本地应通过：`./mvnw -q -T1C verify`（至少跑改动模块 + 其依赖）。

---

## 7. 代理行为边界（Agent 必读）

- **改动最小化**：只动与任务相关的文件；不做无关格式化、重命名、依赖升级。
- **不确定就停**：涉及 public API 签名、依赖版本、构建插件、发布流程时，先询问，不要擅自决定。
- **不生成新工具类**，除非确认仓库中不存在等价能力（先 `grep` / 搜索）。
- **不得引入 `record`**：
  - 生成 DTO / VO / 配置 / 不可变类时，使用 `final class` + 手写 getter；
  - 如模板、示例、生成代码中出现 `record`，必须改写；
  - 不得使用 `record` 解构模式。
- **不修改**：`LICENSE`、CI 配置（`.github/`、`.gitlab-ci.yml`、`Jenkinsfile`）、
  `maven-settings.xml`、私服地址、签名密钥等。
- **临时产物归 `/tmp`**（见 §1），任务结束后清理自己产生的 `/tmp/seedj-*` 目录。
- **报错优先读日志**：构建失败先看 `target/` 下的 surefire/failsafe 报告，再决定是否改代码。
- **产出报告**：涉及代码修改的任务，结束时给出简明变更清单（文件 → 改动原因 → 是否影响 API）。

---

> 如本文件与仓库既有 `CONTRIBUTING.md`、`README.md`、CI 配置冲突，**以 CI 配置与实际构建结果为准**，
> 并同步更新本文件。
