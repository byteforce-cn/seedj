package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.transit.TransitClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 信封加密（{@code TransitClient}）集成验证。
 *
 * <p>覆盖：AES-256-GCM 加解密往返（含 UTF-8）、context 绑定的解密约束、
 * HMAC-SHA256 签名/验签（含篡改拒绝）。KEK 由容器环境变量注入。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordTransitIntegrationTest extends AbstractCoordIntegrationTest {

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
    void hmacSignAndVerify() {
        byte[] data = "signed-content".getBytes(StandardCharsets.UTF_8);

        byte[] signature = transitClient.hmacSign(data);
        assertThat(signature).isNotEmpty();
        assertThat(transitClient.hmacVerify(data, signature)).isTrue();
        assertThat(transitClient.hmacVerify("tampered".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
    }

    @Test
    void emptyPayloadRoundTrips() {
        byte[] ciphertext = transitClient.encrypt(new byte[0]);
        assertThat(transitClient.decrypt(ciphertext)).isEmpty();
    }
}
