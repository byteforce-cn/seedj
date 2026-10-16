package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.coord.sdk.transit.TransitHmacSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 信封加密（{@code TransitClient}）集成验证。
 *
 * <p>覆盖：AES-256-GCM 加解密往返（含 UTF-8）、context 绑定的解密约束、
 * HMAC-SHA256 用途化签名/验签（purpose 必填、互不可验，含篡改拒绝）。
 * KEK 由容器环境变量注入。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordTransitIntegrationTest extends AbstractCoordIntegrationTest {

    /** HMAC 用途标签（G-TR-3 / ADR-0012：purpose 必填且互不可验）。 */
    private static final String HMAC_PURPOSE = "seedj-it:content";

    @Autowired
    private TransitClient transitClient;

    @Test
    void encryptDecryptRoundTrip() {
        byte[] plaintext = "敏感数据 sensitive-payload".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = transitClient.encrypt(plaintext);
        assertThat(ciphertext).isNotEmpty();
        assertThat(ciphertext).isNotEqualTo(plaintext);

        assertThat(transitClient.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void contextBoundCiphertextRejectsWrongContext() {
        byte[] plaintext = "order-123".getBytes(StandardCharsets.UTF_8);
        byte[] context = "tenant-A".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = transitClient.encrypt(plaintext, context);
        assertThat(transitClient.decrypt(ciphertext, context)).isEqualTo(plaintext);

        assertThatThrownBy(() -> transitClient.decrypt(ciphertext, "tenant-B".getBytes(StandardCharsets.UTF_8)))
                .as("context 不匹配必须解密失败（GCM 完整性校验）")
                .isInstanceOf(CoordException.class);
    }

    @Test
    void hmacSignAndVerifyWithPurpose() {
        byte[] data = "signed-content".getBytes(StandardCharsets.UTF_8);

        TransitHmacSignature signed = transitClient.hmacSignWithPurpose(data, HMAC_PURPOSE);

        assertThat(signed.signature()).isNotEmpty();
        assertThat(signed.keyId()).as("验签必须回传签名时的材料标识").isNotEmpty();
        assertThat(transitClient.hmacVerifyWithPurpose(data, signed.signature(), HMAC_PURPOSE, signed.keyId()))
                .isTrue();
        assertThat(transitClient.hmacVerifyWithPurpose("tampered".getBytes(StandardCharsets.UTF_8),
                signed.signature(), HMAC_PURPOSE, signed.keyId())).isFalse();
    }

    @Test
    void hmacPurposesAreMutuallyUnverifiable() {
        byte[] data = "purpose-bound-content".getBytes(StandardCharsets.UTF_8);

        TransitHmacSignature signed = transitClient.hmacSignWithPurpose(data, HMAC_PURPOSE);

        assertThat(transitClient.hmacVerifyWithPurpose(data, signed.signature(),
                HMAC_PURPOSE + ":other", signed.keyId()))
                .as("不同 purpose 派生不同密钥，互不可验（ADR-0012）")
                .isFalse();
    }

    @Test
    void emptyPayloadRoundTrips() {
        byte[] ciphertext = transitClient.encrypt(new byte[0]);
        assertThat(transitClient.decrypt(ciphertext)).isEmpty();
    }
}
