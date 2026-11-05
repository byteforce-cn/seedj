package cn.byteforce.seedj.iam.scim;

import java.util.Objects;

/** One RFC 7644 PATCH operation. */
public record ScimPatchOperation(String op, String path, Object value) {

    public ScimPatchOperation {
        op = Objects.requireNonNull(op, "op").toLowerCase(java.util.Locale.ROOT);
        if (!op.equals("add") && !op.equals("remove") && !op.equals("replace")) {
            throw new IllegalArgumentException("unsupported PATCH op: " + op);
        }
        if (path != null && path.isBlank()) {
            path = null;
        }
        if (op.equals("remove") && path == null) {
            throw new IllegalArgumentException("remove requires a path");
        }
        if (!op.equals("remove") && value == null) {
            throw new IllegalArgumentException(op + " requires a value");
        }
    }
}
