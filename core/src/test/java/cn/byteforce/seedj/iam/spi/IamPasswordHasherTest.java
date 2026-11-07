package cn.byteforce.seedj.iam.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 密码哈希端口：{value} 前缀即视为已编码，避免重复哈希。 */
class IamPasswordHasherTest {

    @Test
    @DisplayName("isEncoded：{ 前缀视为已编码，null/明文视为未编码")
    void isEncodedDetectsPrefixedHashes() {
        IamPasswordHasher hasher = new DelegatingHasher();

        assertThat(hasher.isEncoded("{bcrypt}$2a$10$hash")).isTrue();
        assertThat(hasher.isEncoded("{sha256}abc")).isTrue();
        assertThat(hasher.isEncoded("plain-text")).isFalse();
        assertThat(hasher.isEncoded("")).isFalse();
        assertThat(hasher.isEncoded(null)).isFalse();
    }

    @Test
    @DisplayName("encode/matches：由实现提供，前缀不参与比较")
    void encodeAndMatch() {
        IamPasswordHasher hasher = new DelegatingHasher();

        String encoded = hasher.encode("secret");

        assertThat(encoded).isEqualTo("{plain}secret");
        assertThat(hasher.matches("secret", encoded)).isTrue();
        assertThat(hasher.matches("other", encoded)).isFalse();
    }

    /** 测试用最小编码器：只用于验证端口默认方法。 */
    private static final class DelegatingHasher implements IamPasswordHasher {

        @Override
        public String encode(CharSequence rawPassword) {
            return "{plain}" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).equals(encodedPassword);
        }
    }
}
