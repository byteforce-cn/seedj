package cn.byteforce.seedj.iam;

import java.time.Instant;

/** One-time MFA recovery code. Only a password hash is stored. */
public record IamRecoveryCode(String userId, String codeHash, boolean used, Instant createdAt) {

    public IamRecoveryCode {
        userId = requireText(userId, "userId");
        codeHash = requireText(codeHash, "codeHash");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public IamRecoveryCode markUsed() {
        return new IamRecoveryCode(userId, codeHash, true, createdAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
