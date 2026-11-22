package cn.byteforce.seedj.iam.engine;

import cn.byteforce.seedj.iam.spi.IamPasswordHasher;

/**
 * Deterministic hasher for the engine's plain tests: no Spring, no BCrypt, and the {@code {test}}
 * prefix keeps {@link IamPasswordHasher#isEncoded} semantics.
 */
public final class TestPasswordHasher implements IamPasswordHasher {

    @Override
    public String encode(CharSequence rawPassword) {
        return "{test}" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return encode(rawPassword).equals(encodedPassword);
    }
}
