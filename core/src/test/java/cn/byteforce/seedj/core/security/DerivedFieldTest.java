package cn.byteforce.seedj.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 派生字段：域分离、轮换窗口与确定性。
 */
class DerivedFieldTest {

    private static final String SCOPE = "seedj:hr:employee:id_number_hash:v1";

    @Test
    @DisplayName("稳定输出 64 位小写 hex")
    void derivesStableLowercaseHex() {
        DerivedField field = new DerivedField(SCOPE,
                new DerivedFieldKeyRing("key-1", "key-0"));

        String derived = field.derive("110101199001011234");

        assertThat(derived).matches("^[0-9a-f]{64}$");
        assertThat(field.derive("110101199001011234")).isEqualTo(derived);
    }

    @Test
    @DisplayName("同一原始值在不同 scope 下散列不同")
    void scopeSeparatesSameRawValue() {
        DerivedField first = new DerivedField(SCOPE, new DerivedFieldKeyRing("key-1"));
        DerivedField second = new DerivedField("seedj:iam:user:secret_hash:v1",
                new DerivedFieldKeyRing("key-1"));

        assertThat(first.derive("same")).isNotEqualTo(second.derive("same"));
    }

    @Test
    @DisplayName("轮换后旧密钥仍可匹配")
    void rotationMatchesAllAcceptedKeys() {
        DerivedField oldKey = new DerivedField(SCOPE, new DerivedFieldKeyRing("old"));
        String stored = oldKey.derive("110101199001011234");
        DerivedField rotated = new DerivedField(SCOPE,
                new DerivedFieldKeyRing("new", "old"));

        assertThat(rotated.variants("110101199001011234")).contains(stored);
        assertThat(rotated.matches("110101199001011234", stored)).isTrue();
        assertThat(rotated.derive("110101199001011234")).isNotEqualTo(stored);
    }

    @Test
    @DisplayName("空白原始值拒绝")
    void blankRawValueIsRejected() {
        DerivedField field = new DerivedField(SCOPE, new DerivedFieldKeyRing("key"));

        assertThatThrownBy(() -> field.derive(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法 scope 与空密钥拒绝")
    void invalidScopeAndEmptyKeysRejected() {
        assertThatThrownBy(() -> new DerivedField("hr:employee:v1",
                new DerivedFieldKeyRing("key")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DerivedFieldKeyRing())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
