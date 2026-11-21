package cn.byteforce.seedj.iam;

import java.time.Instant;

/**
 * A signing key in JWK JSON form. {@code privateJwk} is empty for retired keys; when present it is
 * already protected by the implementation (JDBC encrypts it).
 */
public record IamSigningKey(
        String kid,
        String algorithm,
        String publicJwk,
        String privateJwk,
        boolean active,
        Instant createdAt,
        Instant retiredAt) {

    public IamSigningKey {
        kid = requireText(kid, "kid");
        algorithm = algorithm == null || algorithm.isBlank() ? "RS256" : algorithm;
        publicJwk = requireText(publicJwk, "publicJwk");
        privateJwk = privateJwk == null ? "" : privateJwk;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public boolean canSign() {
        return active && !privateJwk.isBlank();
    }

    public IamSigningKey retire(Instant at) {
        return new IamSigningKey(kid, algorithm, publicJwk, "", false, createdAt, at);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
