package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM protector for local/dev and single-process deployments. Production multi-instance
 * deployments should use the Transit/KMS protector instead.
 */
public class AesGcmIamSecretProtector implements IamSecretProtector {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String PREFIX = "v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String keyName;

    public AesGcmIamSecretProtector(byte[] key, String keyName) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException("AES key must be 16, 24, or 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
        this.keyName = keyName == null || keyName.isBlank() ? "local-aes" : keyName;
    }

    public static AesGcmIamSecretProtector fromBase64(String base64Key, String keyName) {
        return new AesGcmIamSecretProtector(Base64.getDecoder().decode(base64Key), keyName);
    }

    @Override
    public String protect(String plaintext, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (context != null && !context.isBlank()) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return PREFIX
                    + ":"
                    + keyName
                    + ":"
                    + encoder.encodeToString(iv)
                    + ":"
                    + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IamException(IamErrorCode.STORE_UNAVAILABLE, "secret encryption failed", e);
        }
    }

    @Override
    public String reveal(String protectedValue, String context) {
        try {
            String[] parts = protectedValue.split(":", 4);
            if (parts.length != 4 || !PREFIX.equals(parts[0]) || !keyName.equals(parts[1])) {
                throw new IllegalArgumentException("unsupported protected value");
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[2]);
            byte[] ciphertext = decoder.decode(parts[3]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (context != null && !context.isBlank()) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IamException(IamErrorCode.STORE_UNAVAILABLE, "secret decryption failed", e);
        }
    }

    @Override
    public String keyName() {
        return keyName;
    }
}
