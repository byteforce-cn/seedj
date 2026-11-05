# PKI SPI 收敛 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `PkiProvider` 成为 PKI 唯一对外契约，彻底删除 `PkiStateStore`（持久化收敛为包私有 `PkiStateFile`）。

**Architecture:** 先补齐缺失的意图方法（`signingIdentity` / `rotateSigningIdentity`），让 IAM 迁移到新契约；再把存储接口从 `core` 挪进实现模块并去掉 bean，最后用表面门禁测试锁住"PKI 包只有两个公开类型"。

**Tech Stack:** Java 21、Maven 3.9、JUnit 5 + AssertJ、Bouncy Castle、Spring Boot。

**Spec:** `docs/superpowers/specs/2026-11-03-pki-spi-convergence-design.md`；ADR：`docs/adr/ADR-0001-pki-spi-single-surface.md`

## Global Constraints

- `core` 仅依赖 JDK：`PkiProvider` 中不得出现 Spring / BouncyCastle / Nimbus 类型。
- 依赖方向不变：`core` ← `domain-infra-memory` ← `starter`。
- 契约方法签名逐字照抄本计划，不得改名或加参数。
- 签名身份策略是 `BcPkiProvider` 私有常量：`SIGNING_KEY_SIZE=2048`、`SIGNING_VALIDITY=Duration.ofDays(3650)`、subject 为 `"CN=" + alias + ",O=Byteforce"`、usage 为 `CertificateUsage.BOTH`。不新增配置键。
- alias 合法字符：`^[A-Za-z0-9][A-Za-z0-9._-]*$`；null / 空白 / 含其他字符一律 `IllegalArgumentException`（防止 `,` `=` 破坏 X500 DN）。
- 本目录当前不是 git 仓库（`git rev-parse` 报 `not a git repository`）：跳过所有提交步骤；若执行时已建仓，每个任务结束后提交一次。
- 涉及移动/删除类的任务，验证命令必须带 `clean`，否则 `target/classes` 里的陈旧 `.class` 会让门禁测试误报。

## Review Focus

| # | 输入 / 条件 | 期望行为 | 落在哪个任务 |
|---|---|---|---|
| 1 | 两个实例并发首次调用 `signingIdentity` | 收敛到同一 serial，不产生两个身份 | Task 1 |
| 2 | alias 含 `,` `=` 空格或为空 | `IllegalArgumentException`，不生成损坏的 DN | Task 1 |
| 3 | 状态文件里身份证书键丢失（密钥还在） | 用同一密钥重建，serial 不变 | Task 2 |
| 4 | 账本 `issued.<serial>` 丢失但证书还在 | 重建账本，不抛异常 | Task 2 |
| 5 | `rotateSigningIdentity` 之后 | 旧 serial 永久 `REVOKED`，`signingIdentity` 不会复活它 | Task 3 |

已知限制（不写测试）：身份证书与当前 CA 绑定；更换 CA 时必须对相关 alias 调用 `rotateSigningIdentity`，否则会返回旧 CA 签发的证书。写入 `signingIdentity` 的 javadoc 与设计文档 §9。

---

### Task 1: `signingIdentity` 首次签发、幂等、并发收敛

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/pki/PkiProvider.java`（新增 1 个方法）
- Modify: `core/src/main/java/cn/byteforce/seedj/pki/PkiStateStore.java`（新增 2 个方法）
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/FilePkiStateStore.java`
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/BcPkiProvider.java`
- Test: `domain-infra-memory/src/test/java/cn/byteforce/seedj/pki/BcPkiProviderTest.java`

**Interfaces:**
- Consumes: 现有 `issue(CertificateRequest)`、`stateStore.getOrCreateKeyPair(alias, keySize)`、`stateStore.status(serial)`。
- Produces:
  - `IssuedCertificate PkiProvider.signingIdentity(String alias)`
  - `Optional<X509Certificate> PkiStateStore.identityCertificate(String alias)`
  - `void PkiStateStore.putIdentityCertificate(String alias, X509Certificate cert)`
  - `public BcPkiProvider(String caResource, char[] password, String caAlias, Path stateFile)`

- [ ] **Step 1: 写失败测试**（追加到 `BcPkiProviderTest`，并补 import：`java.io.IOException`、`java.io.InputStream`、`java.io.OutputStream`、`java.util.ArrayList`、`java.util.Properties`、`java.util.concurrent.ExecutorService`、`java.util.concurrent.Executors`、`java.util.concurrent.Future`、`java.util.concurrent.TimeUnit`）

```java
    @Test
    @DisplayName("signingIdentity 首次调用应签发身份，再次调用应返回同一 serial、公钥与私钥")
    void signingIdentity_shouldBeIdempotent() {
        IssuedCertificate first = pki.signingIdentity("test-signer");
        IssuedCertificate second = pki.signingIdentity("test-signer");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey()).isEqualTo(first.certificate().getPublicKey());
        assertThat(second.privateKey()).isEqualTo(first.privateKey());
        assertThat(second.notAfter()).isEqualTo(first.notAfter());
    }

    @Test
    @DisplayName("两个实例共享状态文件时 signingIdentity 应返回同一身份")
    void signingIdentity_shouldBeSharedAcrossInstances() {
        IssuedCertificate first = pki.signingIdentity("shared-signer");

        BcPkiProvider other = new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);
        IssuedCertificate second = other.signingIdentity("shared-signer");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey()).isEqualTo(first.certificate().getPublicKey());
    }

    @Test
    @DisplayName("两个实例并发调用 signingIdentity 应收敛到同一 serial")
    void signingIdentity_shouldConvergeUnderConcurrentFirstUse() throws Exception {
        BcPkiProvider other = new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                BcPkiProvider target = (i % 2 == 0) ? pki : other;
                futures.add(pool.submit(() -> target.signingIdentity("concurrent-signer").serialNumber()));
            }
            List<String> serials = new ArrayList<>();
            for (Future<String> future : futures) {
                serials.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(serials).containsOnly(serials.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("alias 非法时应拒绝，不生成损坏的 DN")
    void signingIdentity_withInvalidAlias_shouldThrow() {
        assertThatThrownBy(() -> pki.signingIdentity(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pki.signingIdentity(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pki.signingIdentity("bad,alias"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pki.signingIdentity("bad=alias"))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: 跑测试确认红灯**

Run: `mvn -q -pl domain-infra-memory -am test`
Expected: 编译失败，`找不到符号: 方法 signingIdentity(String)`。

- [ ] **Step 3: 加契约**

`core/.../PkiProvider.java`：

```java
    /**
     * 获取 alias 对应的长期签名身份（密钥对 + CA 签发证书）。
     *
     * <p>同一 alias 在同一 provider 部署（同一 CA + 同一状态存储）下幂等：
     * 证书有效时原样返回；证书过期时用同一密钥对重签，{@code serialNumber} 不变；
     * 身份已被 {@link #revoke} 时抛 {@link PkiException}，不静默恢复。
     *
     * <p>身份与当前 CA 绑定：更换 CA 后必须调用
     * {@link #rotateSigningIdentity(String, RevocationReason)} 迁移。
     *
     * @param alias 业务别名，匹配 {@code [A-Za-z0-9][A-Za-z0-9._-]*}
     */
    IssuedCertificate signingIdentity(String alias);
```

- [ ] **Step 4: 加内部存储能力**

`core/.../PkiStateStore.java`：

```java
    Optional<X509Certificate> identityCertificate(String alias);
    void putIdentityCertificate(String alias, X509Certificate cert);
```

（`rotateSigningIdentity` 与 `rotateKeyPair` 在 Task 3 再加，保持本任务可编译。）

- [ ] **Step 5: 实现 `FilePkiStateStore`**

新增常量 `IDENTITY_PREFIX = "identity."`、`IDENTITY_CERT_SUFFIX = ".cert"`。
`identityCertificate` 从 Properties 取 base64 DER，用 `CertificateFactory.getInstance("X.509")` 还原；缺失返回 `Optional.empty()`；解码失败抛 `PkiException`。
`putIdentityCertificate` 调 `cert.getEncoded()`（`CertificateEncodingException` 包成 `PkiException`），base64 后 `update(props -> props.setProperty(key, value))`。

- [ ] **Step 6: 实现 `BcPkiProvider`**

私有常量与构造：

```java
    private static final int SIGNING_KEY_SIZE = 2048;
    private static final Duration SIGNING_VALIDITY = Duration.ofDays(3650);
    private static final Pattern SIGNING_ALIAS_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public BcPkiProvider(String caResource, char[] password, String caAlias, Path stateFile) {
        this(caResource, password, caAlias, new FilePkiStateStore(stateFile));
    }
```

方法签名：

```java
    @Override
    public IssuedCertificate signingIdentity(String alias)
    private IssuedCertificate issueSigningIdentity(String alias, KeyPair keyPair)
    private IssuedCertificate materialize(X509Certificate certificate, KeyPair keyPair)
    private static String requireAlias(String alias)
```

`signingIdentity` 流程：`requireAlias` → `getOrCreateKeyPair(alias, SIGNING_KEY_SIZE)` → `identityCertificate(alias)`；有证书且 `status(serial)==REVOKED` 抛 `PkiException`（消息含 `rotateSigningIdentity`）；有证书且 `status==VALID` 返回 `materialize`；其余（无证书 / EXPIRED / UNKNOWN）走 `issueSigningIdentity`，后者用 `CertificateRequest(subject, List.of(), List.of(), SIGNING_VALIDITY, keyPair, CertificateUsage.BOTH)` 调 `issue()`，再 `putIdentityCertificate(alias, issued.certificate())`。

- [ ] **Step 7: 跑测试确认绿灯**

Run: `mvn -q -pl domain-infra-memory -am test`
Expected: `Tests run: ..., Failures: 0, Errors: 0`，包含 4 个新用例。

- [ ] **Step 8: 提交（无 VCS 则跳过）**

```bash
git add core/src/main/java/cn/byteforce/seedj/pki domain-infra-memory/src
git commit -m "feat(pki): add PkiProvider.signingIdentity for stable signing identities"
```

---

### Task 2: 过期续期、账本自愈、证书自愈

**Files:**
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/FilePkiStateStore.java`（`recordIssued` 改为只增不减）
- Test: `domain-infra-memory/src/test/java/cn/byteforce/seedj/pki/BcPkiProviderTest.java`

**Interfaces:**
- Consumes: Task 1 的 `signingIdentity`、`identityCertificate`、`putIdentityCertificate`。
- Produces: 无新签名；`recordIssued` 语义变为 `notAfter = max(existing, new)`。

- [ ] **Step 1: 写失败测试**

```java
    @Test
    @DisplayName("账本标记过期后 signingIdentity 应同密钥重签并顺延 notAfter")
    void signingIdentity_shouldRenewExpiredCertificate() throws IOException {
        IssuedCertificate first = pki.signingIdentity("renew-signer");
        markLedgerExpired(first.serialNumber());

        IssuedCertificate renewed = pki.signingIdentity("renew-signer");

        assertThat(renewed.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(renewed.certificate().getPublicKey()).isEqualTo(first.certificate().getPublicKey());
        assertThat(renewed.notAfter()).isAfterOrEqualTo(first.notAfter());
        assertThat(pki.status(first.serialNumber())).isEqualTo(CertificateStatus.VALID);
    }

    @Test
    @DisplayName("身份证书键丢失时应复用已有密钥重建，serial 不变")
    void signingIdentity_shouldRebuildWhenCertificateMissing() throws IOException {
        IssuedCertificate first = pki.signingIdentity("repair-signer");
        removeStateKeys("identity.repair-signer.cert");

        IssuedCertificate rebuilt = pki.signingIdentity("repair-signer");

        assertThat(rebuilt.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(rebuilt.privateKey()).isEqualTo(first.privateKey());
    }

    @Test
    @DisplayName("账本记录丢失时 signingIdentity 应重建账本而不是抛异常")
    void signingIdentity_shouldRebuildWhenLedgerMissing() throws IOException {
        IssuedCertificate first = pki.signingIdentity("ledger-signer");
        removeStateKeys("issued." + first.serialNumber());

        IssuedCertificate rebuilt = pki.signingIdentity("ledger-signer");

        assertThat(rebuilt.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(pki.status(first.serialNumber())).isEqualTo(CertificateStatus.VALID);
    }

    private void markLedgerExpired(String serial) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
        }
        props.setProperty("issued." + serial, "0");
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            props.store(out, "test");
        }
    }

    private void removeStateKeys(String... keys) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
        }
        for (String key : keys) {
            props.remove(key);
        }
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            props.store(out, "test");
        }
    }
```

- [ ] **Step 2: 跑测试确认红灯**

Run: `mvn -q -pl domain-infra-memory -am test`
Expected: `signingIdentity_shouldRenewExpiredCertificate` 失败于 `status` 仍为 `EXPIRED`（`recordIssued` 的 `putIfAbsent` 保留了旧值）。

- [ ] **Step 3: 改 `recordIssued`**

```java
    @Override
    public void recordIssued(IssuedCertificate cert) {
        String key = ISSUED_PREFIX + cert.serialNumber();
        long notAfter = cert.notAfter().toEpochMilli();
        update(props -> {
            long previous = props.containsKey(key) ? Long.parseLong(props.getProperty(key)) : Long.MIN_VALUE;
            props.setProperty(key, String.valueOf(Math.max(previous, notAfter)));
        });
    }
```

- [ ] **Step 4: 跑测试确认绿灯**

Run: `mvn -q -pl domain-infra-memory -am test`
Expected: `Failures: 0, Errors: 0`，Task 1 的用例仍全绿。

- [ ] **Step 5: 提交（无 VCS 则跳过）**

```bash
git commit -am "fix(pki): renew expired signing identities without resetting serial"
```

---

### Task 3: 吊销后拒绝 + `rotateSigningIdentity`

**Files:**
- Modify: `core/src/main/java/cn/byteforce/seedj/pki/PkiProvider.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/pki/PkiStateStore.java`
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/FilePkiStateStore.java`
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/BcPkiProvider.java`
- Test: `domain-infra-memory/src/test/java/cn/byteforce/seedj/pki/BcPkiProviderTest.java`

**Interfaces:**
- Produces:
  - `IssuedCertificate PkiProvider.rotateSigningIdentity(String alias, RevocationReason reason)`
  - `KeyPair PkiStateStore.rotateKeyPair(String alias, int keySize)`

- [ ] **Step 1: 写失败测试**

```java
    @Test
    @DisplayName("身份被吊销后 signingIdentity 应抛 PkiException，不静默恢复")
    void signingIdentity_shouldFailWhenRevoked() {
        IssuedCertificate issued = pki.signingIdentity("revoked-signer");
        pki.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);

        assertThatThrownBy(() -> pki.signingIdentity("revoked-signer"))
                .isInstanceOf(PkiException.class)
                .hasMessageContaining("rotateSigningIdentity");
    }

    @Test
    @DisplayName("rotateSigningIdentity 应吊销旧身份、换钥并返回新身份")
    void rotateSigningIdentity_shouldReplaceKeyAndRevokeOld() {
        IssuedCertificate old = pki.signingIdentity("rotate-signer");

        IssuedCertificate rotated = pki.rotateSigningIdentity("rotate-signer", RevocationReason.SUPERSEDED);

        assertThat(rotated.serialNumber()).isNotEqualTo(old.serialNumber());
        assertThat(rotated.certificate().getPublicKey()).isNotEqualTo(old.certificate().getPublicKey());
        assertThat(pki.status(old.serialNumber())).isEqualTo(CertificateStatus.REVOKED);
        assertThat(pki.status(rotated.serialNumber())).isEqualTo(CertificateStatus.VALID);
        assertThat(pki.signingIdentity("rotate-signer").serialNumber()).isEqualTo(rotated.serialNumber());
    }

    @Test
    @DisplayName("rotateSigningIdentity 对未使用的 alias 等价于首次签发")
    void rotateSigningIdentity_withoutExisting_shouldCreate() {
        IssuedCertificate rotated = pki.rotateSigningIdentity("fresh-signer", RevocationReason.UNSPECIFIED);

        assertThat(rotated.serialNumber()).isNotBlank();
        assertThat(pki.status(rotated.serialNumber())).isEqualTo(CertificateStatus.VALID);
        assertThat(pki.signingIdentity("fresh-signer").serialNumber()).isEqualTo(rotated.serialNumber());
    }
```

- [ ] **Step 2: 跑测试确认红灯**

Run: `mvn -q -pl domain-infra-memory -am test`
Expected: 编译失败，`找不到符号: 方法 rotateSigningIdentity(...)`。

- [ ] **Step 3: 加契约与实现**

`core/.../PkiProvider.java`：

```java
    /**
     * 轮换 alias 的签名身份：以 {@code reason} 吊销当前身份、丢弃其密钥材料、签发新身份。
     *
     * <p>alias 没有当前身份时等价于 {@link #signingIdentity(String)}，不产生吊销记录。
     * 旧 serial 永久保持 REVOKED，不会被后续调用复活。
     */
    IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason);
```

`core/.../PkiStateStore.java`：

```java
    KeyPair rotateKeyPair(String alias, int keySize);
```

`FilePkiStateStore.rotateKeyPair`：在 `update` 内生成新密钥对并覆盖 `keypair.<alias>.private` / `.public`，返回新 KeyPair。
`BcPkiProvider.rotateSigningIdentity`：`requireAlias` → `Objects.requireNonNull(reason)` → 读 `identityCertificate`；无则 `return signingIdentity(alias)`；有则取 `oldSerial`，若 `status` 为 `VALID` 或 `EXPIRED` 调 `recordRevoked(oldSerial, reason)`（`UNKNOWN` 跳过，避免 `recordRevoked` 抛"未签发"）→ `rotateKeyPair(alias, SIGNING_KEY_SIZE)` → `issueSigningIdentity(alias, newKeyPair)`。

- [ ] **Step 4: 跑测试确认绿灯**

Run: `mvn -q -pl domain-infra-memory -am test`
Expected: `Failures: 0, Errors: 0`，共 23 个用例全绿（13 个既有 + Task 1 的 4 个 + Task 2 的 3 个 + 本任务 3 个）。

- [ ] **Step 5: 提交（无 VCS 则跳过）**

```bash
git commit -am "feat(pki): add explicit signing identity rotation"
```

---

### Task 4: IAM 只依赖 `PkiProvider`

**Files:**
- Modify: `starter/src/main/java/cn/byteforce/seedj/iam/config/IamServerAutoConfiguration.java:305-326`
- Test: `starter/src/test/java/cn/byteforce/seedj/test/OidcIT.java`（JWKS 段落）

**Interfaces:**
- Consumes: `PkiProvider.signingIdentity(String)`（Task 1）。
- Produces: `JWKSource<SecurityContext> jwkSource(PkiProvider pki)`，无 `PkiStateStore` 参数。

本任务是重构，红→绿不适用。回归测试先行的替代验证：先加 `kid` 断言（在当前代码上应通过，锁定行为），再改装配，再跑同一测试确认仍通过。

- [ ] **Step 1: 先加回归测试**

在 `OidcIT` 的 JWKS 段落（`jwksMustExposePublicRsaKey` 之后）追加：

```java
    @Test
    @DisplayName("JWKS 的 kid 必须等于 IAM 签名身份的 serialNumber")
    void jwksKidMustMatchSigningIdentitySerial(@ClusterPki PkiProvider pki) {
        IamTestSession session = session();
        HttpResult result = session.get("/oauth2/jwks", MediaType.APPLICATION_JSON);
        assertThat(result.status()).isEqualTo(200);

        List<Map<String, Object>> keys = JsonPath.read(result.body(), "$.keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).get("kid"))
                .isEqualTo(pki.signingIdentity("iam-jwt-signer").serialNumber());
    }
```

- [ ] **Step 2: 在旧代码上跑，确认基线通过**

Run: `mvn -q -pl starter -am verify -Dit.test='OidcIT#jwksKidMustMatchSigningIdentitySerial'`
Expected: `Tests run: 1, Failures: 0, Errors: 0`（Docker 必需）。

- [ ] **Step 3: 改 `jwkSource`**

```java
    private static final String JWT_SIGNER_ALIAS = "iam-jwt-signer";

    @Bean
    @ConditionalOnMissingBean
    public JWKSource<SecurityContext> jwkSource(PkiProvider pki) {
        IssuedCertificate issued = pki.signingIdentity(JWT_SIGNER_ALIAS);

        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) issued.certificate().getPublicKey())
                .privateKey((RSAPrivateKey) issued.privateKey())
                .keyID(issued.serialNumber())
                .build();

        JWKSet jwkSet = new JWKSet(rsaKey);
        return (jwkSelector, securityContext) -> jwkSelector.select(jwkSet);
    }
```

同时删除 `import cn.byteforce.seedj.pki.PkiStateStore;` 与 `IamServerAutoConfiguration.java:317` 的残留注释 `// ★ 新增 usage 参数`。

- [ ] **Step 4: 跑同一 IT 确认仍绿**

Run: `mvn -q -pl starter -am verify -Dit.test='OidcIT#jwksKidMustMatchSigningIdentitySerial'`
Expected: `Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交（无 VCS 则跳过）**

```bash
git commit -am "refactor(iam): obtain JWT signing identity via PkiProvider"
```

---

### Task 5: 删除 `PkiStateStore` 并加表面门禁

**Files:**
- Delete: `core/src/main/java/cn/byteforce/seedj/pki/PkiStateStore.java`
- Rename: `domain-infra-memory/.../FilePkiStateStore.java` → `domain-infra-memory/.../PkiStateFile.java`
- Modify: `domain-infra-memory/.../PkiStateFile.java`（`final class`，删 `implements`/`@Override`/`public`）
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/BcPkiProvider.java`（第 4 参数为 `PkiStateStore` 的构造改为包私有）
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/pki/PkiAutoConfiguration.java`（删除 store bean）
- Modify: `starter/src/test/java/cn/byteforce/seedj/test/extension/ClusterExtension.java`
- Delete: `starter/src/test/java/cn/byteforce/seedj/test/extension/ClusterPkiStore.java`
- Create: `core/src/test/java/cn/byteforce/seedj/pki/PkiSpiSurfaceTest.java`
- Test: `domain-infra-memory/src/test/java/cn/byteforce/seedj/pki/BcPkiProviderTest.java`（构造调用）

**Interfaces:**
- Consumes: Task 1–4 的全部改动。
- Produces: `cn.byteforce.seedj.pki` 在 `core` 中只剩 `PkiProvider` 与 `PkiException`。

- [ ] **Step 1: 写表面门禁测试（先红）**

`core/src/test/java/cn/byteforce/seedj/pki/PkiSpiSurfaceTest.java`：

```java
package cn.byteforce.seedj.pki;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PkiSpiSurfaceTest {

    @Test
    void pkiPackageShouldExposeOnlyPkiProviderAndPkiException() throws Exception {
        Path dir = Path.of(PkiProvider.class.getResource(".").toURI());
        try (Stream<Path> files = Files.list(dir)) {
            List<String> topLevelTypes = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.contains("$"))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .sorted()
                    .toList();
            assertThat(topLevelTypes).containsExactly("PkiException", "PkiProvider");
        }
    }
}
```

- [ ] **Step 2: 跑测试确认红灯**

Run: `mvn -q -pl core clean test`
Expected: 断言失败，实际为 `[PkiException, PkiProvider, PkiStateStore]`。

- [ ] **Step 3: 移动并收紧访问级别**

1. 删除 `core/.../PkiStateStore.java`。
2. `FilePkiStateStore.java` 重命名为 `PkiStateFile.java`；类声明改为 `final class PkiStateFile`，删除 `implements PkiStateStore` 与所有 `@Override`，构造与方法去掉 `public`，删除无用的 `file()`。**不保留任何同名接口。**
3. 本任务替换了 Task 1–3 中对 `FilePkiStateStore` 的引用（构造、字段类型）。
4. `BcPkiProvider(..., PkiStateStore)` 构造去 `public`；保留 Task 1 的 `Path` 公有构造。
5. `PkiAutoConfiguration` 删除 `pkiStateStore` bean，改为：

```java
    @Bean
    @ConditionalOnMissingBean
    public PkiProvider pkiProvider(PkiProperties props) {
        return new BcPkiProvider(
                props.getCaResource(),
                props.getCaPassword().toCharArray(),
                props.getCaAlias(),
                props.getRevocationStore());
    }
```

6. `ClusterExtension`：删除 `FilePkiStateStore` / `PkiStateStore` 的 import、`pkiStateStore` 字段、`@ClusterPkiStore` 的两个分支、`teardown` 里的 `pkiStateStore = null;`、两个 `new FilePkiStateStore(...)` 局部变量；两处构造改为 `new BcPkiProvider(caP12.toUri().toString(), PASSWORD, CA_ALIAS, pkiStateFile)` 与 `new BcPkiProvider(untrustedCaP12.toUri().toString(), PASSWORD, CA_ALIAS, tempDir.resolve("untrusted-pki-state.properties"))`。
7. 删除 `ClusterPkiStore.java`。

- [ ] **Step 4: 全量跑测试确认绿灯**

Run: `mvn -q -pl core,domain-infra-memory,starter -am clean test`
Expected: `Failures: 0, Errors: 0`；`PkiSpiSurfaceTest` 通过。

- [ ] **Step 5: 提交（无 VCS 则跳过）**

```bash
git add -A
git commit -m "refactor(pki): delete PkiStateStore and keep persistence private"
```

---

### Task 6: 文档定稿与整体验证

**Files:**
- Modify: `docs/adr/ADR-0001-pki-spi-single-surface.md`（状态 `proposed` → `accepted`）
- Modify: `docs/superpowers/specs/2026-11-03-pki-spi-convergence-design.md`（§9 补"身份与 CA 绑定"限制）

**Interfaces:**
- Consumes: Task 1–5。
- Produces: 可交付的决策记录与验证结论。

- [ ] **Step 1: 更新 ADR 状态**

把 `- 状态：proposed` 改为 `- 状态：accepted`。

- [ ] **Step 2: 在设计文档 §9 补限制**

追加一条：「签名身份与当前 CA 绑定；更换 CA 后必须对相关 alias 调用 `rotateSigningIdentity`，否则 `signingIdentity` 会继续返回旧 CA 签发的证书。」

- [ ] **Step 3: 全量验证**

Run:
```bash
mvn -q -pl core,domain-infra-memory -am clean test
mvn -q -pl starter -am clean verify
```
Expected:
- 第一条：`Failures: 0, Errors: 0`；
- 第二条：单元测试 + `OidcIT` 全绿（Docker 必需）。

- [ ] **Step 4: 记录未验证项**

若 Docker 不可用，在最终答复里写明 `OidcIT` 未验证及命令，不得声称通过。

- [ ] **Step 5: 提交（无 VCS 则跳过）**

```bash
git add docs
git commit -m "docs(adr): accept PKI SPI convergence decision"
```
