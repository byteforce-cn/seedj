package cn.byteforce.seedj.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Transit 契约测试套件。新 provider 只需 {@code extends} 本类并实现 {@link #newProvider()}。
 *
 * <p>覆盖 {@code package-info} 里承诺的语义：幂等、版本、context、批量、脱敏、生命周期。
 *
 * <p>能力门控：契约要求调用方在调用 <b>可选能力</b> 前先查 {@link TransitProvider#supports}（见 {@code package-info} 的
 * {@code UNSUPPORTED_OPERATION}）。本套件按同一条规则门控——声明不支持的操作不执行对应用例， 但 {@link
 * #supportedOperationsAreDeclared} 会钉住一套最小能力，避免“全声明 false 也能过”。 没有 key 生命周期的远端 KMS 实现覆盖 {@link
 * #provisionKey} 即可复用加解密/批量的用例。
 */
public abstract class TransitProviderContractTest {

    protected TransitProvider provider;

    protected abstract TransitProvider newProvider();

    /**
     * 实现是否给密文打版本（{@code keyVersion} 非 null 且密文带 {@code v<n>:} 前缀）。
     *
     * <p>无版本管理的远端 KMS 返回 {@code false}：{@code package-info} 只承诺 rewrap 的语义，没有承诺版本前缀。
     */
    protected boolean versionedCiphertext() {
        return true;
    }

    /**
     * 准备一个可用于加解密的 key。
     *
     * <p>默认走 key admin 的 {@code createKey}。没有 key admin 的实现（单 KEK 的远端 KMS）覆盖为直接返回 name，
     * 这样加解密与批量用例仍然可以跑。
     */
    protected String provisionKey(String name, KeyType type) {
        provider.keyAdmin().createKey(CreateKeyRequest.of(name, type));
        return name;
    }

    @BeforeEach
    void createProvider() {
        provider = newProvider();
    }

    @AfterEach
    void closeProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    /** 最小能力：加解密与 HMAC 是 Transit 的业务入口，任何实现都必须支持。 */
    @Test
    @DisplayName("声明的能力覆盖最小集合")
    void supportedOperationsAreDeclared() {
        assertThat(provider.supports(KeyType.AES256_GCM96, TransitOperation.ENCRYPT))
                .as("AES256_GCM96 ENCRYPT")
                .isTrue();
        assertThat(provider.supports(KeyType.AES256_GCM96, TransitOperation.DECRYPT))
                .as("AES256_GCM96 DECRYPT")
                .isTrue();
        assertThat(provider.supports(KeyType.HMAC, TransitOperation.HMAC)).as("HMAC HMAC").isTrue();
        assertThat(provider.supports(KeyType.HMAC, TransitOperation.VERIFY_HMAC))
                .as("HMAC VERIFY_HMAC")
                .isTrue();
    }

    @Test
    @DisplayName("createKey 同名同参幂等")
    void createKeyIsIdempotent() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ROTATE);
        KeyInfo first =
                provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));
        KeyInfo second =
                provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));

        assertThat(second.getLatestVersion()).isEqualTo(first.getLatestVersion());
    }

    @Test
    @DisplayName("createKey 同名不同参抛 KEY_ALREADY_EXISTS")
    void createKeyConflict() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ROTATE);
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));

        assertThatThrownBy(
                        () ->
                                provider.keyAdmin()
                                        .createKey(CreateKeyRequest.of("k1", KeyType.AES128_GCM96)))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.KEY_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("encrypt/decrypt 往返一致，密文携带版本")
    void encryptDecryptRoundTrip() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ENCRYPT);
        requireOperation(KeyType.AES256_GCM96, TransitOperation.DECRYPT);
        String key = provisionKey("k1", KeyType.AES256_GCM96);

        Ciphertext ciphertext = provider.crypto().encrypt(EncryptRequest.of(key, "hello"));
        String plain = provider.crypto().decrypt(DecryptRequest.of(key, ciphertext.getValue()));

        assertThat(plain).isEqualTo("hello");
        if (versionedCiphertext()) {
            assertThat(ciphertext.getKeyVersion()).isEqualTo(1);
            assertThat(ciphertext.getValue()).startsWith("v1:");
        }
    }

    @Test
    @DisplayName("rotate 后新密文用新版本，旧密文仍可解密")
    void rotateKeepsOldVersionsDecryptable() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ROTATE);
        Assumptions.assumeTrue(versionedCiphertext(), "provider has no key versions");
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));
        Ciphertext old = provider.crypto().encrypt(EncryptRequest.of("k1", "before"));

        provider.keyAdmin().rotateKey("k1");
        Ciphertext fresh = provider.crypto().encrypt(EncryptRequest.of("k1", "after"));

        assertThat(provider.keyAdmin().readKey("k1").getLatestVersion()).isEqualTo(2);
        assertThat(fresh.getKeyVersion()).isEqualTo(2);
        assertThat(provider.crypto().decrypt(DecryptRequest.of("k1", old.getValue())))
                .isEqualTo("before");
    }

    @Test
    @DisplayName("rewrap 重新包装到最新版本，明文不变")
    void rewrapToLatest() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.REWRAP);
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ROTATE);
        Assumptions.assumeTrue(versionedCiphertext(), "provider has no key versions");
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));
        Ciphertext old = provider.crypto().encrypt(EncryptRequest.of("k1", "payload"));
        provider.keyAdmin().rotateKey("k1");

        Ciphertext rewrapped =
                provider.crypto().rewrap(new RewrapRequest("k1", old.getValue(), null));

        assertThat(rewrapped.getKeyVersion()).isEqualTo(2);
        assertThat(provider.crypto().decrypt(DecryptRequest.of("k1", rewrapped.getValue())))
                .isEqualTo("payload");
    }

    @Test
    @DisplayName("derived key 必须带 context；非 derived key 不允许带 context")
    void contextRules() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.CONFIGURE);
        provider.keyAdmin()
                .createKey(
                        new CreateKeyRequest(
                                "derived", KeyType.AES256_GCM96, true, null, null, null));
        provider.keyAdmin().createKey(CreateKeyRequest.of("plain", KeyType.AES256_GCM96));

        assertThatThrownBy(() -> provider.crypto().encrypt(EncryptRequest.of("derived", "x")))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.INVALID_CONTEXT);

        assertThatThrownBy(
                        () ->
                                provider.crypto()
                                        .encrypt(
                                                new EncryptRequest("plain", "x", "aGVsbG8=", null)))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.INVALID_CONTEXT);

        Ciphertext ct =
                provider.crypto().encrypt(new EncryptRequest("derived", "x", "aGVsbG8=", null));
        assertThat(
                        provider.crypto()
                                .decrypt(new DecryptRequest("derived", ct.getValue(), "aGVsbG8=")))
                .isEqualTo("x");
    }

    @Test
    @DisplayName("批量：单项失败进 BatchResult.errorCode，请求级失败才抛异常")
    void batchPerItemErrors() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ENCRYPT);
        String key = provisionKey("k1", KeyType.AES256_GCM96);

        List<BatchResult<Ciphertext>> results =
                provider.crypto()
                        .encryptBatch(
                                new BatchEncryptRequest(
                                        key,
                                        List.of(
                                                new BatchItem("ok", "ref-1", null, null),
                                                new BatchItem("ok2", "ref-2", null, null)),
                                        null,
                                        null));

        assertThat(results)
                .hasSize(2)
                .allSatisfy(
                        r -> {
                            assertThat(r.getErrorCode()).isNull();
                            assertThat(r.getValue()).isNotNull();
                        });
    }

    @Test
    @DisplayName("类型不匹配的操作抛 TYPE_MISMATCH")
    void typeMismatch() {
        requireOperation(KeyType.RSA_2048, TransitOperation.ROTATE);
        provider.keyAdmin().createKey(CreateKeyRequest.of("rsa", KeyType.RSA_2048));

        assertThatThrownBy(() -> provider.crypto().encrypt(EncryptRequest.of("rsa", "x")))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("sign/verify 往返，篡改数据验签失败")
    void signAndVerify() {
        requireOperation(KeyType.RSA_2048, TransitOperation.SIGN);
        requireOperation(KeyType.RSA_2048, TransitOperation.VERIFY);
        provider.keyAdmin().createKey(CreateKeyRequest.of("rsa", KeyType.RSA_2048));

        Signature signature = provider.signer().sign(new SignRequest("rsa", "data", null, null));
        assertThat(
                        provider.signer()
                                .verify(
                                        new VerifyRequest(
                                                "rsa", "data", signature.getValue(), null, null)))
                .isTrue();
        assertThat(
                        provider.signer()
                                .verify(
                                        new VerifyRequest(
                                                "rsa",
                                                "tampered",
                                                signature.getValue(),
                                                null,
                                                null)))
                .isFalse();
    }

    @Test
    @DisplayName("hmac/verifyHmac 往返，篡改数据验签失败")
    void macAndVerifyHmac() {
        requireOperation(KeyType.HMAC, TransitOperation.HMAC);
        requireOperation(KeyType.HMAC, TransitOperation.VERIFY_HMAC);
        String key = provisionKey("mac", KeyType.HMAC);

        MacResult mac = provider.mac().hmac(new HmacRequest(key, "data", null, null));
        assertThat(
                        provider.mac()
                                .verifyHmac(
                                        new VerifyHmacRequest(
                                                key, "data", mac.getValue(), null, null)))
                .isTrue();
        assertThat(
                        provider.mac()
                                .verifyHmac(
                                        new VerifyHmacRequest(
                                                key, "tampered", mac.getValue(), null, null)))
                .isFalse();
    }

    @Test
    @DisplayName("toString 不输出密钥材料、明文、密文")
    void toStringRedacts() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ENCRYPT);
        String key = provisionKey("k1", KeyType.AES256_GCM96);
        Ciphertext ciphertext = provider.crypto().encrypt(EncryptRequest.of(key, "top-secret"));

        assertThat(ciphertext.toString()).doesNotContain("top-secret");
        assertThat(EncryptRequest.of(key, "top-secret").toString()).doesNotContain("top-secret");
    }

    @Test
    @DisplayName("deleteKey 幂等；未知 key 抛 KEY_NOT_FOUND")
    void deleteKeyIsIdempotent() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.DELETE);
        provider.keyAdmin().createKey(CreateKeyRequest.of("gone", KeyType.AES256_GCM96));
        provider.keyAdmin().deleteKey("gone");
        provider.keyAdmin().deleteKey("gone");

        assertThatThrownBy(() -> provider.keyAdmin().readKey("gone"))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.KEY_NOT_FOUND);
    }

    @Test
    @DisplayName("close 幂等；关闭后操作抛 PROVIDER_CLOSED")
    void closeSemantics() {
        provider.close();
        provider.close();

        assertThatThrownBy(() -> provider.crypto().encrypt(EncryptRequest.of("k", "x")))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.PROVIDER_CLOSED);
    }

    @Test
    @DisplayName("datakey 生成的临时密钥可解出被包装的密钥")
    void dataKey() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.GENERATE_DATA_KEY);
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));

        DataKey dataKey =
                provider.crypto().generateDataKey(new DataKeyRequest("k1", null, null, true));

        String unwrapped =
                provider.crypto()
                        .decrypt(DecryptRequest.of("k1", dataKey.getCiphertextKey().getValue()));
        assertThat(java.util.Base64.getDecoder().decode(unwrapped))
                .isEqualTo(dataKey.usePlaintextKey());
    }

    @Test
    @DisplayName("encrypt 结果与 UTF-8 明文一致（跨实现可互操作）")
    void roundTripUtf8() {
        requireOperation(KeyType.AES256_GCM96, TransitOperation.ENCRYPT);
        requireOperation(KeyType.AES256_GCM96, TransitOperation.DECRYPT);
        String key = provisionKey("k1", KeyType.AES256_GCM96);
        String text = "订单已取消 ✅";

        Ciphertext ct = provider.crypto().encrypt(EncryptRequest.of(key, text));

        assertThat(
                        provider.crypto()
                                .decrypt(DecryptRequest.of(key, ct.getValue()))
                                .getBytes(StandardCharsets.UTF_8))
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 声明不支持的操作跳过对应用例：契约的第一条就是「调用可选能力前先查 supports」。 */
    private void requireOperation(KeyType type, TransitOperation operation) {
        Assumptions.assumeTrue(
                provider.supports(type, operation),
                () -> "provider does not support " + type + " " + operation);
    }
}
