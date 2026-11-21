package cn.byteforce.seedj.iam;

import java.time.Instant;

/**
 * Encrypted TOTP enrollment. {@code secretCiphertext} is always protected by an {@code
 * IamSecretProtector}; plaintext secrets never reach this type.
 */
public record IamTotpState(
        String userId,
        String tenantId,
        String keyName,
        String secretCiphertext,
        boolean confirmed,
        long lastUsedStep,
        Instant createdAt,
        Instant confirmedAt) {

    public IamTotpState {
        userId = requireText(userId, "userId");
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        keyName = requireText(keyName, "keyName");
        secretCiphertext = requireText(secretCiphertext, "secretCiphertext");
        lastUsedStep = Math.max(-1, lastUsedStep);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public IamTotpState confirmed(Instant at) {
        return new IamTotpState(
                userId, tenantId, keyName, secretCiphertext, true, lastUsedStep, createdAt, at);
    }

    public IamTotpState withLastUsedStep(long step) {
        return new IamTotpState(
                userId,
                tenantId,
                keyName,
                secretCiphertext,
                confirmed,
                step,
                createdAt,
                confirmedAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
