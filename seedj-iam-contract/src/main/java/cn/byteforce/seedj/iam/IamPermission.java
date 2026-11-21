package cn.byteforce.seedj.iam;

import java.util.Objects;

/** Fine-grained permission code, split into resource/action for ABAC reuse. */
public record IamPermission(
        String tenantId, String code, String resource, String action, String description) {

    public IamPermission {
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        resource = requireText(resource, "resource");
        action = requireText(action, "action");
        code = code == null || code.isBlank() ? resource + ":" + action : code;
        description = description == null ? "" : description;
    }

    public static IamPermission of(
            String tenantId, String resource, String action, String description) {
        return new IamPermission(tenantId, resource + ":" + action, resource, action, description);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
