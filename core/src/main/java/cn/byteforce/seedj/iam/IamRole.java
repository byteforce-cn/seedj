package cn.byteforce.seedj.iam;

import java.util.Objects;

/** RBAC role. {@code parentId} models inheritance; cycles must be rejected by stores. */
public record IamRole(
        String id,
        String tenantId,
        String code,
        String name,
        String description,
        String parentId,
        boolean builtIn,
        long version) {

    public IamRole {
        id = requireText(id, "id");
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        code = requireText(code, "code");
        name = name == null || name.isBlank() ? code : name;
        description = description == null ? "" : description;
        version = Math.max(0, version);
    }

    public static IamRole create(String id, String tenantId, String code, String name, String description,
                                 String parentId, boolean builtIn) {
        return new IamRole(id, tenantId, code, name, description,
                parentId, builtIn, 0);
    }

    public IamRole withParent(String parentId) {
        return new IamRole(id, tenantId, code, name, description, parentId, builtIn, version);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
