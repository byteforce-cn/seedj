# ADR-0008：模块拓扑重构（core / 实现模块 / starter / server / it）

- 状态：accepted
- 日期：2026-11-05
- 决策者：seedj 维护者
- 关联：ADR-0002（基础设施 SPI 统一）、ADR-0003（IAM 生产化）、ADR-0004（Outbox 独立 SPI）、ADR-0007（biz starter 独立与安全契约下沉）
- 取代：ADR-0003 / ADR-0007 中的**模块命名与目录位置**；两份 ADR 的其余决策继续有效

## 1. 背景

重构前的模块拓扑有三处名实不符：

1. `starter` 既是唯一的聚合依赖，又是集成测试宿主；`src/main` 为空，`IamServer` / `HrServer` / `ImServer` 与 `ClusterExtension` 全在 `src/test`，没有可独立运行的服务入口。
2. `domain-infra-memory` / `domain-jdbc-postgresql` 的 `domain-` 前缀与实际职责（基础设施实现）不符；`workflow-infra-memory` 与 `seedj-outbox-starter` 游离在两套命名之外。
3. 三个“服务”只存在于测试源码里，无法作为独立进程部署，也无法验证多实例下的 outbox / eventbus / lock / ID / audit 行为。

同时确认了实现模块的两条边界：

- `seedj-infra-local`：用内存/文件承载**不依赖关系型本地事务**的协调能力（PKI、Transit、EventBus、ID、Lock、Audit memory、IAM memory、Workflow）。
- `seedj-jdbc-postgresql`：用 PostgreSQL 承载**需要关系型本地事务**的协调能力（Outbox、Audit、IAM store、迁移）。
- 两者是并列的实现模块，只依赖 `core`，彼此不依赖。当前不引入 MySQL/Oracle/Redis 变体；将来出现第二个实现时再按同一模式扩展。

## 2. 决策

### 2.1 目标模块树

```
bom
core                          # 契约，零 Spring、零 seedj 依赖
seedj-infra-local             # 原 domain-infra-memory + workflow-infra-memory
seedj-jdbc-postgresql         # 原 domain-jdbc-postgresql + seedj-outbox-starter
seedj-iam-starter             # IAM 实现 + 自动装配
seedj-biz-starter             # DDD 业务横切 + 资源服务器安全
seedj-server/
  iam  → seedj-server-iam     # 统一认证/授权服务（OIDC Provider）
  hr   → seedj-server-hr      # HR 参考服务，验证 biz-starter + core/infra
  im   → seedj-server-im      # IM 参考服务，验证非传统业务对 core 的要求
seedj-it                      # 原 starter；集成测试与夹具，src/main 无代码
```

### 2.2 依赖方向

```
core
 ↑
seedj-infra-local   seedj-jdbc-postgresql
 ↑                        ↑
 ├──── seedj-iam-starter ─┤
 └──── seedj-biz-starter ─┘
          ↑
 seedj-server-iam  seedj-server-hr  seedj-server-im
          ↑
       seedj-it
```

硬规则：

1. `core` 零 Spring、零 seedj 依赖。
2. `seedj-infra-local` / `seedj-jdbc-postgresql` 只依赖 `core`，互不依赖；Spring 保持 `provided`，第三方库保持 `optional`/`provided`。
3. `seedj-iam-starter` 与 `seedj-biz-starter` 互不依赖；都只依赖 `core` + 两个实现模块。
4. `seedj-server-iam` 只带 `seedj-iam-starter`；`seedj-server-hr` / `seedj-server-im` 只带 `seedj-biz-starter`，classpath 中不得出现 `seedj-iam-starter`。
5. server 之间零依赖；`seedj-it` 是唯一同时依赖三个 server 的模块，依赖范围为 `test`。
6. server 包名使用 `cn.byteforce.seedj.server.<name>`，避免与 starter 的 `cn.byteforce.seedj.iam` / `cn.byteforce.seedj.biz` 形成 split package。

### 2.3 合并与迁移

- `workflow-infra-memory` 整体并入 `seedj-infra-local`，包名 `cn.byteforce.seedj.workflow.memory` 不变。该模块原本没有 `AutoConfiguration.imports` 登记，本次不改变其自动装配行为。
- `seedj-outbox-starter` 整体并入 `seedj-jdbc-postgresql`：
  - 包名 `cn.byteforce.seedj.outbox.wiring` 不变；
  - `OutboxAutoConfiguration` 追加到 `seedj-jdbc-postgresql` 的 `AutoConfiguration.imports`；
  - `OutboxRelay` / `OutboxWriter` / `OutboxProperties` / `OutboxMetrics` 与 PostgreSQL store 同模块交付，宿主只加一个依赖。
- `starter` 更名为 `seedj-it`：
  - `IamServer` / `HrServer` / `ImServer` 迁入对应 server 模块的 `src/main`；
  - 测试用户、测试控制器、事件夹具、`ClusterExtension` 留在 `seedj-it/src/test`；
  - server main 类只保留 `@SpringBootApplication`、`@EnableIamServer` / `@EnableBizServer` 与 `main`；
  - `seedj-it` 通过 `SpringApplicationBuilder.sources(...)` 注入夹具，不再依赖“测试类与 server 同包被扫描”。

### 2.4 打包与发布

- server 模块使用 `spring-boot-maven-plugin`，executable jar 以 `exec` classifier 附加；主制品保持 thin jar，`seedj-it` 以普通依赖启动。
- server 与 `seedj-it` 设置 `seedj.compat.skip=true`；server 设置 `seedj.jacoco.guard.skip=true`（入口类无独立单测，覆盖率由 `seedj-it` 承担）。
- BOM 只管理库模块（`core`、两个实现模块、两个 starter）；server 与 `seedj-it` 不进入对外 BOM。

## 3. 后果

正面：

- 依赖方向可用 POM 直接读出；`hr/im` 不可能意外携带 OIDC 授权服务器。
- IAM 服务、业务参考服务、集成测试各有明确宿主，三服务可在同一 JVM 内启动，也可独立部署。
- outbox 的存储与投递装配同模块交付，业务应用只加 `seedj-jdbc-postgresql` 即可获得 outbox 能力。

代价：

- server 模块引入 `spring-boot-maven-plugin` 与 `exec` classifier，发布脚本需要区分 thin/exec 制品。
- `seedj-it` 的测试夹具通过 `sources(...)` 显式注入，新增服务时需要同时更新 `ServiceDefinition.sources()`。
- `seedj-infra-local` 合并 workflow 后模块体积变大；等出现第二个 workflow 实现时再拆分。

## 4. 验证

- `mvn -DskipTests install`：12 个 reactor 模块全部编译、打包、安装成功。
- `mvn test`：库模块 + `seedj-it` 非 Docker 测试全部通过（含合并后的 workflow / outbox 测试）。
- `mvn -pl seedj-it -am verify`：110 个集成测试全部通过，三服务健康检查、OIDC、eventbus、transit、ID 全链路正常。
