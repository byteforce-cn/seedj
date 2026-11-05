# PKI SPI 收敛设计：`PkiProvider` 作为唯一对外契约

- 状态：待批准
- 日期：2026-11-03
- 关联 ADR：`docs/adr/ADR-0001-pki-spi-single-surface.md`

## 1. 意图

`PkiProvider` 是 PKI 基础设施对外的**唯一** SPI。实现方（内置 BC、Vault、step-ca、HSM 网关）只需实现它，调用方只认识它。

现状违反了这条边界：上一个改动把实现内部的持久化抽象 `PkiStateStore` 提升成了 `core` 的公开接口，并且：

| 位置 | 泄漏方式 |
| --- | --- |
| `core/src/main/java/cn/byteforce/seedj/pki/PkiStateStore.java` | 公开顶层接口，方法 `recordIssued/recordRevoked/status/clear/getOrCreateKeyPair` |
| `domain-infra-memory/.../PkiAutoConfiguration.java:17` | 单独注册为 bean，任何组件可直接注入 |
| `starter/.../IamServerAutoConfiguration.java:307` | `jwkSource(PkiProvider, PkiStateStore)` 直接调用 `getOrCreateKeyPair` |
| `starter/src/test/.../ClusterExtension.java:105,137` | 测试直接构造 `FilePkiStateStore` |
| `starter/src/test/.../ClusterPkiStore.java` | 泄漏衍生出的注解，**全仓库无任何使用** |

**根因不是滥用，是 SPI 缺了一个意图**：`PkiProvider` 只表达"签发证书"，没有表达"给我 alias 的稳定签名身份"。IAM 需要后者，只能绕过 SPI 自取密钥。

事实补充：生产代码中 `revoke/status` 没有任何调用方，只有测试在用；`PkiStateStore` 五个方法里生产真正依赖的只有 `getOrCreateKeyPair`。也就是说，泄漏出去的能力 4/5 是无人使用的账本，却把存储机制（Properties 文件、FileLock、base64 私钥、keySize）一并顶到了公开 API。

## 2. 目标 / 非目标

### 目标

1. `core` 的 PKI 包对外只暴露 `PkiProvider`（+ `PkiException`）。
2. "稳定签名身份"作为一等意图进入 `PkiProvider`，不是把 `getOrCreateKeyPair` 原样搬上去。
3. 彻底删除 `PkiStateStore`：不保留同名接口，持久化收敛为 `BcPkiProvider` 使用的包私有具体类 `PkiStateFile`，不再是 bean，不可被业务注入。
4. 用测试锁住"PKI 包只有这两个公开类型"，防止再次泄漏。
5. 调用方（IAM）认知负担降到一次调用。

### 非目标

1. 不改 `issue/revoke/status/caCertificate/sslContext` 的既有语义。
2. 不引入 Spring 依赖到 `core`。
3. 不做密钥算法可配置化（HSM/KMS 等能力等真实需求再走 ADR）。
4. 不改 `PkiProperties` 的既有配置键。
5. 不修 `ClusterExtension.java:143` 的可疑写法（见第 9 节，只报告）。

### 反目标

1. 把"密钥对可导出、算法是 RSA、位数由调用方决定"写进 SPI 事实 —— 那样生产级 provider 反而更难接。
2. 让调用方连续调用两个方法（先取密钥、再签发）才能拿到身份。
3. 为了"未来可能的多 CA"再抽一层。

## 3. 约束

- `core` 仅依赖 JDK；`PkiProvider` 不得出现 Spring 类型。
- 依赖方向不变：`core` ← `domain-infra-memory` ← `starter`。
- 契约变更走 ADR。
- `1.0.0` 尚未发布（`pom.xml:55` = `1.0.0-SNAPSHOT`，本地仓库无 `1.0.0` 基线），因此删除 `PkiStateStore` 不构成已发布 API 的 breaking；japicmp 门禁当前无基线可跑。
- 公开签名变更必须有可执行验证；`core` 已有 JUnit 5 + AssertJ 可用。

## 4. 方案取舍

### A. 意图级方法（采用）

```java
IssuedCertificate signingIdentity(String alias);
IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason);
```

调用方只说"给我 alias 的稳定签名身份"。密钥生成、持久化、复用、有效期、位数全部是实现策略；持久化内聚为包私有具体类。

### B. 把 `getOrCreateKeyPair(alias, keySize)` 原样搬到 `PkiProvider`（否决）

迁移最小，但把"RSA、可导出私钥、调用方决定位数"写成 SPI 事实。Vault / step-ca / HSM 私钥不可导出、签发在服务端完成，无法实现该方法；调用方仍需两步调用。

### C. 给 `CertificateRequest` 加 `alias` 字段（否决）

`issue()` 将出现两种模式（带 alias 复用密钥 / 不带随机生成），语义靠可空字段分叉，最容易被误用；"稳定身份"这一需求被藏进签发语义。

## 5. 契约语义（`core`/`PkiProvider`）

### `IssuedCertificate signingIdentity(String alias)`

> 获取 alias 对应的长期签名身份（密钥对 + CA 签发证书）。

1. **幂等**：同一 alias、同一 provider 部署（同一 CA + 同一状态存储）下，多次调用返回的 `serialNumber`、证书公钥、私钥一致。
2. **首次调用**：生成密钥对并签发，持久化后返回。
3. **已存在且有效**：直接返回已签发的身份，不重新签发（不产生证书漂移）。
4. **已过期**：用同一密钥对重签（`serialNumber` 不变），以新证书覆盖旧证书。
5. **已被 `revoke`**：抛 `PkiException`，消息指明需要调用 `rotateSigningIdentity`。**不静默恢复已吊销身份**。
6. `alias` 为 null/空白 → `IllegalArgumentException`。
7. 线程安全与多实例一致性由实现保证；跨实例一致性依赖实现配置（内置实现为共享状态文件）。
8. 返回的 `IssuedCertificate` 中 `chain` 为 `[leaf, ca]`，`privateKey` 与证书公钥匹配，`notBefore/notAfter` 取自证书本身。

### `IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason)`

> 轮换 alias 的签名身份：吊销当前身份、丢弃其密钥材料、签发新身份。

1. alias 无当前身份 → 等价于 `signingIdentity(alias)`，不产生吊销记录。
2. 有当前身份 → 以 `reason` 吊销旧 serial，丢弃旧密钥，生成新密钥并签发，返回新身份。
3. 旧 serial 在账本中保持 `REVOKED`，永不重用。
4. 该方法由运维/安全事件显式触发，不被任何读路径自动调用。

### 为什么是两个方法而不是一个

- 续期是"保持可用"，属于读路径的正常行为，自动做。
- 吊销后的轮换是安全事件，静默自动恢复等于抹掉事件；但只抛异常又会让 SPI 变成单向门。所以给显式出口，且仍在 `PkiProvider` 上。

### 既有方法保持不变

`issue` / `revoke` / `status` / `caCertificate` / `sslContext` / `trustContext` 语义与签名不动。`sslContext` 属于 JSSE 机制，理论上更该下沉到实现，但本次不改，避免范围外破坏。

## 6. 实现侧变更

### `domain-infra-memory`

**`PkiStateFile`（包私有 `final` 类，无接口）**

```java
final class PkiStateFile {
    PkiStateFile(Path file);
    void recordIssued(IssuedCertificate cert);
    void recordRevoked(String serialNumber, RevocationReason reason);
    CertificateStatus status(String serialNumber);
    void clear();
    KeyPair getOrCreateKeyPair(String alias, int keySize);
    KeyPair rotateKeyPair(String alias, int keySize);
    Optional<X509Certificate> identityCertificate(String alias);
    void putIdentityCertificate(String alias, X509Certificate cert);
}
```

- `recordIssued` 由 `putIfAbsent` 改为**只增不减的 `max(notAfter)`**：同一 serial 重签（过期续期）时顺延 `notAfter`，普通签发仍是新 serial，行为不变。
- 新增文件键：`identity.<alias>.cert=<base64 DER>`。存 DER 而不是每次重算，避免每次调用都产生新的 `notBefore` 导致证书漂移。
- 类、构造与全部方法均为包私有：`core` 不引用，`starter` 不可见。

**`BcPkiProvider`**

- 公开构造：`BcPkiProvider(String caResource, char[] password, String caAlias, Path stateFile)`（内部自建 `PkiStateFile`）。
- 包私有构造：`BcPkiProvider(..., PkiStateFile stateFile)`（仅同包测试用）。
- 签名身份策略为实现的私有常量：`SIGNING_KEY_SIZE=2048`、`SIGNING_VALIDITY=3650d`、`SIGNING_SUBJECT="CN=<alias>,O=Byteforce"`、`SIGNING_USAGE=BOTH`。**不新增配置键**；出现真实需求再走 ADR。
- `signingIdentity` / `rotateSigningIdentity` 伪代码：

```
signingIdentity(alias):
  kp   = store.getOrCreateKeyPair(alias, KEY_SIZE)
  req  = Request(subject(alias), [], [], VALIDITY, kp, BOTH)
  cert = store.identityCertificate(alias)
  if cert == null:            issued = issue(req); store.putIdentityCertificate(alias, issued.certificate()); return issued
  switch store.status(serialOf(cert)):
    VALID   -> return materialize(cert, kp)          // 原样返回，不重签
    EXPIRED -> issued = issue(req); store.putIdentityCertificate(alias, issued.certificate()); return issued
    REVOKED -> throw PkiException("签名身份已被吊销，需 rotateSigningIdentity(alias, reason)")
    UNKNOWN -> issue + put
```

`serialOf(cert)` 由证书本身解析，与 `deterministicSerial` 无关，避免重复实现推导逻辑。

**`PkiAutoConfiguration`**

```java
@Bean @ConditionalOnMissingBean
public PkiProvider pkiProvider(PkiProperties props) {
    return new BcPkiProvider(props.getCaResource(), props.getCaPassword().toCharArray(),
                             props.getCaAlias(), props.getRevocationStore());
}
```

不再有 `PkiStateStore` bean。

### `starter`

`IamServerAutoConfiguration.java:307`：

```java
@Bean @ConditionalOnMissingBean
public JWKSource<SecurityContext> jwkSource(PkiProvider pki) {
    IssuedCertificate issued = pki.signingIdentity(JWT_SIGNER_ALIAS);
    RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) issued.certificate().getPublicKey())
            .privateKey((RSAPrivateKey) issued.privateKey())
            .keyID(issued.serialNumber())
            .build();
    ...
}
```

`kid` 语义不变（仍是 `serialNumber`），续期不换 serial，因此轮换前 `kid` 稳定。

### 测试

- 删除 `starter/src/test/.../ClusterPkiStore.java` 及 `ClusterExtension` 中相关分支与字段。
- `ClusterExtension` 改为 `new BcPkiProvider(caUri, PASSWORD, CA_ALIAS, pkiStateFile)`。
- `BcPkiProviderTest` 增加：
  1. `signingIdentity` 两次调用 / 两个 provider 实例共享状态文件时 serial、公钥、私钥一致；
  2. 账本中该 serial 写入过期 `notAfter` 后，`signingIdentity` 重签且 serial 不变、`notAfter` 顺延；
  3. `revoke` 后 `signingIdentity` 抛 `PkiException`；
  4. `rotateSigningIdentity` 返回新 serial，旧 serial 仍为 `REVOKED`，新身份可用；
  5. alias 为空抛 `IllegalArgumentException`。
- `core` 新增 `PkiSpiSurfaceTest`：枚举 `cn/byteforce/seedj/pki` 包下的顶层类型，断言只允许 `PkiProvider`、`PkiException` 及其嵌套类型。这是防再次泄漏的机械门禁。

## 7. 兼容性与迁移

| 调用方 | 迁移 |
| --- | --- |
| 注入 `PkiStateStore` 的业务代码 | 无生产调用方；改为 `PkiProvider.signingIdentity` |
| `jwkSource(PkiProvider, PkiStateStore)` | 改为 `jwkSource(PkiProvider)` |
| 直接 `new BcPkiProvider(..., PkiStateStore)` | 改为传 `Path stateFile` |
| 测试中 `stateStore.clear()` | 保留：`PkiStateFile` 包私有，同包测试可用 |
| 配置 `seedj.pki.*` | 不变 |

- `PkiStateStore` 全仓库仅 1 个实现、0 个业务调用方，删除面可控；`core` 中不再存在该类型。
- 版本：`1.0.0` 未发布，不升 MAJOR；ADR 记录决策即可。

## 8. 验证

```bash
mvn -q -pl core,domain-infra-memory test
mvn -q -pl starter -DskipITs test        # 单元测试
mvn -q -pl starter verify                # 含 OidcIT，需要 Docker
```

- `PkiSpiSurfaceTest` 失败即表示有人再次把实现细节暴露到 `core`。
- japicmp 门禁（`-Pcompat`）当前无 `1.0.0` 基线，不适用；在首次发布后生效。

## 9. 留给实现者的空间（与范围外发现）

- `PkiStateFile` 中 `Properties.store` 会写入当前时间戳注释，属实现细节，不动。
- 签名身份与当前 CA 绑定：更换 CA 后必须对相关 alias 调用 `rotateSigningIdentity`，否则 `signingIdentity` 会继续返回旧 CA 签发的证书。
- 默认状态文件为 `java.io.tmpdir/seedj-pki-state.properties`，JWT 私钥 base64 明文落盘且无权限控制。**本次不修**，建议单独立项（安全）。
- `ClusterExtension.java:143` 用 `pkiProvider.sslContext(untrustedClientCert)` 构建"不受信客户端"上下文，疑似应为 `untrustedPki.sslContext(...)`。**本次不修**，先确认该负向用例是否真的在验证预期行为。
- `IamServerAutoConfiguration.java:317` 的 `// ★ 新增 usage 参数` 是残留标记，随 `jwkSource` 重写一并消失。
