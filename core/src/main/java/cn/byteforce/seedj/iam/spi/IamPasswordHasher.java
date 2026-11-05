package cn.byteforce.seedj.iam.spi;

/** Password/recovery-code hashing port; the Spring adapter uses BCrypt. */
public interface IamPasswordHasher {

    String encode(CharSequence rawPassword);

    boolean matches(CharSequence rawPassword, String encodedPassword);

    default boolean isEncoded(String value) {
        return value != null && value.startsWith("{");
    }
}
