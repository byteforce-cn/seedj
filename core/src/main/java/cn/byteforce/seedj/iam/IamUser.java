package cn.byteforce.seedj.iam;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * IAM user aggregate. {@code userName} is unique per tenant and is the SCIM {@code userName};
 * {@code id} is the stable subject identifier.
 */
public record IamUser(
        String id,
        String tenantId,
        String userName,
        String externalId,
        String displayName,
        String email,
        boolean active,
        Map<String, Object> attributes,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String DEFAULT_TENANT = "default";

    public IamUser {
        id = requireText(id, "id");
        tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
        userName = requireText(userName, "userName");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        version = Math.max(0, version);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static IamUser create(
            String id,
            String tenantId,
            String userName,
            String displayName,
            String email,
            boolean active) {
        Instant now = Instant.now();
        return new IamUser(
                id, tenantId, userName, null, displayName, email, active, Map.of(), 0, now, now);
    }

    public String normalizedUserName() {
        return userName.toLowerCase(Locale.ROOT);
    }

    public IamUser withProfile(String displayName, String email, Map<String, Object> attributes) {
        return new IamUser(
                id,
                tenantId,
                userName,
                externalId,
                displayName,
                email,
                active,
                attributes,
                version,
                createdAt,
                updatedAt);
    }

    public IamUser withActive(boolean active) {
        return new IamUser(
                id,
                tenantId,
                userName,
                externalId,
                displayName,
                email,
                active,
                attributes,
                version,
                createdAt,
                updatedAt);
    }

    public IamUser withExternalId(String externalId) {
        return new IamUser(
                id,
                tenantId,
                userName,
                externalId,
                displayName,
                email,
                active,
                attributes,
                version,
                createdAt,
                updatedAt);
    }

    public IamUser withVersion(long version, Instant updatedAt) {
        return new IamUser(
                id,
                tenantId,
                userName,
                externalId,
                displayName,
                email,
                active,
                attributes,
                version,
                createdAt,
                updatedAt);
    }

    public Map<String, Object> mutableAttributes() {
        return new LinkedHashMap<>(attributes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
