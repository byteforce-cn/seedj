package cn.byteforce.seedj.iam.identity;

import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Adapts Spring's {@link PasswordEncoder} to the Spring-free IAM port. */
public class IamPasswordHasherAdapter implements IamPasswordHasher {

    private final PasswordEncoder encoder;

    public IamPasswordHasherAdapter(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean isEncoded(String value) {
        return value != null && value.startsWith("{");
    }
}
