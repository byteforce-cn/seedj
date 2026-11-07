package cn.byteforce.seedj.starter.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class IamPasswordHasherAdapterTest {

    private final IamPasswordHasher delegating =
            new IamPasswordHasherAdapter(
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());

    @Test
    void encodesAndMatchesThroughTheDelegatingEncoder() {
        String hash = delegating.encode("secret");

        assertThat(hash).startsWith("{bcrypt}");
        assertThat(delegating.matches("secret", hash)).isTrue();
        assertThat(delegating.matches("wrong", hash)).isFalse();
    }

    @Test
    void rejectsBlankOrNullEncodedValues() {
        assertThat(delegating.matches("secret", null)).isFalse();
        assertThat(delegating.matches("secret", "   ")).isFalse();
    }

    @Test
    void detectsEncodedValuesByPrefix() {
        assertThat(delegating.isEncoded("{noop}secret")).isTrue();
        assertThat(delegating.isEncoded("plain-text")).isFalse();
        assertThat(delegating.isEncoded(null)).isFalse();
    }

    @Test
    void delegatesEveryCallToTheWrappedEncoder() {
        PasswordEncoder noOp = NoOpPasswordEncoder.getInstance();

        IamPasswordHasher adapter = new IamPasswordHasherAdapter(noOp);

        assertThat(adapter.encode("secret")).isEqualTo("secret");
        assertThat(adapter.matches("secret", "secret")).isTrue();
    }
}
