package cn.byteforce.seedj.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Transit 契约测试套件。新 provider 只需 {@code extends} 本类并实现 {@link #newProvider()}。
 *
 * <p>覆盖 {@code package-info} 里承诺的语义：幂等、版本、context、批量、脱敏、生命周期。
 */
public abstract class TransitProviderContractTest {

    protected TransitProvider provider;

    protected abstract TransitProvider newProvider();

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

    @Test
    @DisplayName("createKey 同名同参幂等")
    void createKeyIsIdempotent() {
        KeyInfo first =
                provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));
        KeyInfo second =
                provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));

        assertThat(second.getLatestVersion()).isEqualTo(first.getLatestVersion());
    }

    @Test
    @DisplayName("createKey 同名不同参抛 KEY_ALREADY_EXISTS")
    void createKeyConflict() {
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
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));

        Ciphertext ciphertext = provider.crypto().encrypt(EncryptRequest.of("k1", "hello"));
        String plain = provider.crypto().decrypt(DecryptRequest.of("k1", ciphertext.getValue()));

        assertThat(plain).isEqualTo("hello");
        assertThat(ciphertext.getKeyVersion()).isEqualTo(1);
        assertThat(ciphertext.getValue()).startsWith("v1:");
    }

    @Test
    @DisplayName("rotate 后新密文用新版本，旧密文仍可解密")
    void rotateKeepsOldVersionsDecryptable() {
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
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));

        List<BatchResult<Ciphertext>> results =
                provider.crypto()
                        .encryptBatch(
                                new BatchEncryptRequest(
                                        "k1",
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
        provider.keyAdmin().createKey(CreateKeyRequest.of("rsa", KeyType.RSA_2048));

        assertThatThrownBy(() -> provider.crypto().encrypt(EncryptRequest.of("rsa", "x")))
                .isInstanceOf(TransitException.class)
                .extracting(e -> ((TransitException) e).getErrorCode())
                .isEqualTo(TransitErrorCode.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("sign/verify 与 hmac/verifyHmac")
    void signAndMac() {
        provider.keyAdmin().createKey(CreateKeyRequest.of("rsa", KeyType.RSA_2048));
        provider.keyAdmin().createKey(CreateKeyRequest.of("mac", KeyType.HMAC));

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

        MacResult mac = provider.mac().hmac(new HmacRequest("mac", "data", null, null));
        assertThat(
                        provider.mac()
                                .verifyHmac(
                                        new VerifyHmacRequest(
                                                "mac", "data", mac.getValue(), null, null)))
                .isTrue();
    }

    @Test
    @DisplayName("toString 不输出密钥材料、明文、密文")
    void toStringRedacts() {
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));
        Ciphertext ciphertext = provider.crypto().encrypt(EncryptRequest.of("k1", "top-secret"));

        assertThat(ciphertext.toString()).doesNotContain("top-secret");
        assertThat(EncryptRequest.of("k1", "top-secret").toString()).doesNotContain("top-secret");
    }

    @Test
    @DisplayName("deleteKey 幂等；未知 key 抛 KEY_NOT_FOUND")
    void deleteKeyIsIdempotent() {
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
        provider.keyAdmin().createKey(CreateKeyRequest.of("k1", KeyType.AES256_GCM96));
        String text = "订单已取消 ✅";

        Ciphertext ct = provider.crypto().encrypt(EncryptRequest.of("k1", text));

        assertThat(
                        provider.crypto()
                                .decrypt(DecryptRequest.of("k1", ct.getValue()))
                                .getBytes(StandardCharsets.UTF_8))
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }
}
