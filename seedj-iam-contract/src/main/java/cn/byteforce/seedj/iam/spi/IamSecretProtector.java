package cn.byteforce.seedj.iam.spi;

/**
 * Envelope encryption for TOTP secrets and JWK private material. {@code context} is authenticated
 * data (for example the user id).
 */
public interface IamSecretProtector {

    String protect(String plaintext, String context);

    String reveal(String ciphertext, String context);

    String keyName();
}
