package cn.byteforce.seedj.iam.scim;

/**
 * Parsed PATCH path. Supports {@code attr}, {@code attr.sub}, and the SCIM
 * multi-valued form {@code members[value eq "id"]}.
 */
public record ScimPatchPath(String attribute, ScimFilter valueFilter) {

    public ScimPatchPath {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("attribute must not be blank");
        }
    }

    public static ScimPatchPath parse(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        int bracket = trimmed.indexOf('[');
        if (bracket < 0) {
            return new ScimPatchPath(trimmed, null);
        }
        if (!trimmed.endsWith("]")) {
            throw new IllegalArgumentException("unterminated PATCH path filter: " + path);
        }
        String attribute = trimmed.substring(0, bracket).trim();
        String expression = trimmed.substring(bracket + 1, trimmed.length() - 1).trim();
        return new ScimPatchPath(attribute, ScimFilterParser.parse(expression));
    }
}
