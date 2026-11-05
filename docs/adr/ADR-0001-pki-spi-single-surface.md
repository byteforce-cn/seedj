# ADR-0001：PKI 对外 SPI 收敛为 `PkiProvider` 单一契约

- 状态：accepted
- 日期：2026-11-03
- 决策者：seedj 基础设施维护者
- 关联设计：`docs/superpowers/specs/2026-11-03-pki-spi-convergence-design.md`

## 1. 背景与动机

PKI 的对外契约是 `PkiProvider`（`core/src/main/java/cn/byteforce/seedj/pki/PkiProvider.java`），用于隔离内置 Bouncy Castle 实现与未来的 Vault / step-ca / HSM 实现。

当前实现内部用于持久化签发/吊销账本与密钥材料的 `PkiStateStore` 被提升为 `core` 的公开接口，并进一步：

- 被 `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/PkiAutoConfiguration.java:17` 注册为独立 bean；
- 被 `starter/src/main/java/cn/byteforce/seedj/iam/config/IamServerAutoConfiguration.java:307` 直接注入并调用 `getOrCreateKeyPair`；
- 扩散到测试层 `ClusterExtension.java:105,137` 与无人使用的 `@ClusterPkiStore` 注解。

后果：

1. **SPI 隔离失效**：任何 provider 实现都被迫接受第二套接口的语义（跨进程共享状态、可导出私钥、按 serial 记账），否则调用方编译不过。
2. **机制泄漏到契约**：`keySize`、文件锁、`clear()` 等实现细节出现在公开 API，调用方可篡改 CA 账本、伪造状态。
3. **演进双倍成本**：每次能力扩展都要同时改两个 SPI。

根因是 `PkiProvider` 缺少"稳定签名身份"这一意图，IAM 只能绕过 SPI 自取密钥。

## 2. 决策

1. `core` 的 `cn.byteforce.seedj.pki` 包只对外暴露 `PkiProvider` 与 `PkiException`。
2. 在 `PkiProvider` 上新增两个意图级方法：
   - `IssuedCertificate signingIdentity(String alias)`：获取 alias 的稳定签名身份；有效则原样返回，过期用同一密钥重签（serial 不变），已被吊销则抛 `PkiException`。
   - `IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason)`：显式吊销旧身份并换钥重签，返回新身份。
3. 删除 `core/src/main/java/cn/byteforce/seedj/pki/PkiStateStore.java`，且**不在任何模块保留同名接口**。
4. 持久化逻辑收敛为 `domain-infra-memory` 的包私有 `final` 类 `PkiStateFile`（无接口、无 bean），只被 `BcPkiProvider` 与同包测试引用。
5. `PkiAutoConfiguration` 只注册 `PkiProvider`；`jwkSource` 只依赖 `PkiProvider`。
6. 密钥位数、有效期、subject 等作为实现策略（`BcPkiProvider` 私有常量），不进契约、不新增配置键。

## 3. 备选方案与被否决理由

### 方案 B：把 `getOrCreateKeyPair(alias, keySize)` 原样搬到 `PkiProvider`

- 优点：迁移最小。
- 否决理由：把"RSA、私钥可导出、调用方决定位数"写成 SPI 事实。生产级 PKI（Vault / step-ca / HSM）私钥不可导出、签发在服务端完成，无法实现该方法；调用方仍需两步调用，认知负担未降低。

### 方案 C：给 `CertificateRequest` 增加 `alias` 字段

- 优点：不新增方法。
- 否决理由：`issue()` 出现两种模式（带 alias 复用密钥 / 不带随机生成），语义靠可空字段分叉，最易误用；稳定身份需求被藏进签发语义。

### 方案 D：为"长期密钥材料"另立一个独立 SPI

- 优点：职责看似更单一。
- 否决理由：与本次要消除的"两个 SPI 联动演进"问题同构；本次唯一的调用方需要的是"密钥 + 证书"一个整体，拆开只会增加组合错误。

## 4. 兼容性影响

| 仓库/模块 | 影响 |
| --- | --- |
| eis | 无 |
| seedj（本仓库） | `core` 删除 `PkiStateStore`；`domain-infra-memory` 删除 `FilePkiStateStore`（并入 `PkiStateFile`）；`starter` 的 `jwkSource` 签名变化 |
| coord | 无 |
| workbook | 无 |
| 业务团队 | 若曾注入 `PkiStateStore`，需改用 `PkiProvider.signingIdentity`；当前仓库内无生产调用方 |

- 版本：`pom.xml:55` 为 `1.0.0-SNAPSHOT`，`1.0.0` 尚未发布（本地仓库无 `1.0.0` 基线），本次不构成已发布 API 的 breaking，不升 MAJOR。
- 依赖方向：不变（`core` ← `domain-infra-memory` ← `starter`），`core` 仍零 Spring 依赖。

## 5. 迁移方案

1. 业务代码：`stateStore.getOrCreateKeyPair(alias, 2048)` → `pki.signingIdentity(alias)`。
2. 需要轮换：`stateStore.clear()` / 手工删文件 → `pki.rotateSigningIdentity(alias, reason)`。
3. 自定义 `BcPkiProvider` 构造：第 4 参数由 `PkiStateStore` 改为 `Path stateFile`。
4. 自定义 Spring 装配：删除 `PkiStateStore` bean，只保留 `PkiProvider`。

## 6. 验证方式

| 变更 | 验证 |
| --- | --- |
| 契约新增 | `domain-infra-memory` 中 `BcPkiProviderTest` 的 `signingIdentity` / `rotateSigningIdentity` 用例 |
| SPI 表面收敛 | `core` 新增 `PkiSpiSurfaceTest`：`cn.byteforce.seedj.pki` 包只允许 `PkiProvider`、`PkiException` |
| IAM 集成 | `starter` 的 `OidcIT`（需要 Docker） |
| 兼容性 | japicmp `-Pcompat` 在 `1.0.0` 发布后生效；当前无基线，不适用 |

命令：

```bash
mvn -q -pl core,domain-infra-memory test
mvn -q -pl starter -DskipITs test
mvn -q -pl starter verify
```
