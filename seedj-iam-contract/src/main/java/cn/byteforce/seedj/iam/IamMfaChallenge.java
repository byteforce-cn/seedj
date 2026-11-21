package cn.byteforce.seedj.iam;

import java.time.Instant;

/**
 * Short-lived native-auth MFA challenge. The opaque token is never stored; only its SHA-256 hash
 * is.
 */
public record IamMfaChallenge(
        String id,
        String userId,
        String clientId,
        String deviceId,
        String tokenHash,
        Instant expiresAt,
        boolean consumed,
        int attempts,
        int maxAttempts) {

    public IamMfaChallenge {
        id = requireText(id, "id");
        userId = requireText(userId, "userId");
        clientId = requireText(clientId, "clientId");
        deviceId = deviceId == null ? "" : deviceId;
        tokenHash = requireText(tokenHash, "tokenHash");
        expiresAt = expiresAt == null ? Instant.now().plusSeconds(300) : expiresAt;
        attempts = Math.max(0, attempts);
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean locked() {
        return attempts >= maxAttempts;
    }

    public IamMfaChallenge withAttempt() {
        return new IamMfaChallenge(
                id,
                userId,
                clientId,
                deviceId,
                tokenHash,
                expiresAt,
                consumed,
                attempts + 1,
                maxAttempts);
    }

    public IamMfaChallenge markConsumed() {
        return new IamMfaChallenge(
                id, userId, clientId, deviceId, tokenHash, expiresAt, true, attempts, maxAttempts);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
