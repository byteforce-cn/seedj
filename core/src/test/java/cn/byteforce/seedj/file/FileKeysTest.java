package cn.byteforce.seedj.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.ErrorCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link FileKeys} 的 key 规则：所有 provider 共用，规则本身是契约的一部分。 */
class FileKeysTest {

    @Test
    @DisplayName("接受嵌套 key 与 512 字符边界")
    void acceptsValidKeys() {
        FileKeys.requireValid("attachments/2026/report.pdf");
        FileKeys.requireValid("a".repeat(512));
        FileKeys.requireValid("头像/张三.png");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "/a",
                "a/",
                "a//b",
                "a/./b",
                "a/../b",
                "a\u0000b",
                "a\tb",
                "a\nb"
            })
    @DisplayName("拒绝空、绝对路径、空段、点段与控制字符")
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> FileKeys.requireValid(key))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_KEY);
                            assertThat(e.isRetryable()).isFalse();
                        });
    }

    @Test
    @DisplayName("拒绝超过 512 字符的 key")
    void rejectsKeysLongerThan512Characters() {
        assertThatThrownBy(() -> FileKeys.requireValid("a".repeat(513)))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_KEY));
    }

    @Test
    @DisplayName("拒绝 UTF-8 超过 1024 字节的 key")
    void rejectsKeysLongerThan1024Utf8Bytes() {
        assertThatThrownBy(() -> FileKeys.requireValid("中".repeat(342)))
                .isInstanceOfSatisfying(
                        FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_KEY));
    }

    @Test
    @DisplayName("PROVIDER_UNAVAILABLE 可重试且归为后端错误")
    void providerUnavailableIsRetryable() {
        FileException failure = new FileException(FileErrorCode.PROVIDER_UNAVAILABLE, "down");

        assertThat(failure.isRetryable()).isTrue();
        assertThat(failure.category()).isEqualTo(ErrorCategory.BACKEND_ERROR);
        assertThat(failure.getErrorCode()).isEqualTo(FileErrorCode.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("客户端错误不可重试")
    void clientErrorsAreNotRetryable() {
        FileException failure = new FileException(FileErrorCode.NOT_FOUND, "missing");

        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.category()).isEqualTo(ErrorCategory.CLIENT_ERROR);
    }
}
