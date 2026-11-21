package cn.byteforce.seedj.iam;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** SCIM canonical group. Roles are assigned to groups separately. */
public record IamGroup(
        String id,
        String tenantId,
        String displayName,
        String externalId,
        Set<String> memberIds,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public IamGroup {
        id = requireText(id, "id");
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        displayName = requireText(displayName, "displayName");
        memberIds = memberIds == null ? Set.of() : Set.copyOf(memberIds);
        version = Math.max(0, version);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static IamGroup create(
            String id,
            String tenantId,
            String displayName,
            String externalId,
            Set<String> memberIds) {
        Instant now = Instant.now();
        return new IamGroup(id, tenantId, displayName, externalId, memberIds, 0, now, now);
    }

    public IamGroup withMembers(Set<String> members) {
        return new IamGroup(
                id, tenantId, displayName, externalId, members, version, createdAt, updatedAt);
    }

    public IamGroup withVersion(long version, Instant updatedAt) {
        return new IamGroup(
                id, tenantId, displayName, externalId, memberIds, version, createdAt, updatedAt);
    }

    public Set<String> mutableMembers() {
        return new LinkedHashSet<>(memberIds);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
