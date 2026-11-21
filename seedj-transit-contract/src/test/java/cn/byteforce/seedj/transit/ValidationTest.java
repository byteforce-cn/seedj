package cn.byteforce.seedj.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 请求模型的校验规则（package-info 里承诺的语义，必须可执行）。 */
class ValidationTest {

    @Test
    @DisplayName("keyName：拒绝 null / 空白 / 首尾空白")
    void keyName() {
        assertThatThrownBy(() -> Validation.requireKeyName(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.requireKeyName(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.requireKeyName("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.requireKeyName(" key "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Validation.requireKeyName("key")).isEqualTo("key");
    }

    @Test
    @DisplayName("context：null/空串归一化为 null，含空白拒绝")
    void context() {
        assertThat(Validation.normalizeContext(null)).isNull();
        assertThat(Validation.normalizeContext("")).isNull();
        assertThatThrownBy(() -> Validation.normalizeContext("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.normalizeContext(" abc "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Validation.normalizeContext("aGVsbG8=")).isEqualTo("aGVsbG8=");
    }

    @Test
    @DisplayName("Base64：严格拒绝 URL-safe、无填充、空白")
    void base64() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> Base64Validator.validate("aGVsbG8="));
        assertThatThrownBy(() -> Base64Validator.validate("aGVsbG8"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base64Validator.validate("aGVsbG8_"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base64Validator.validate("aGVs bG8="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("批量：null 列表与 null 元素拒绝，空列表合法")
    void batch() {
        assertThatThrownBy(() -> Validation.requireBatchList(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Validation.requireBatchList(java.util.Arrays.asList("a", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Validation.requireBatchList(java.util.List.of())).isEmpty();
    }

    @Test
    @DisplayName("CreateKeyRequest.of 只要求名称与类型")
    void createKeyFactory() {
        CreateKeyRequest request = CreateKeyRequest.of("app-key", KeyType.AES256_GCM96);
        assertThat(request.getKeyName()).isEqualTo("app-key");
        assertThat(request.getDerived()).isNull();
    }
}
