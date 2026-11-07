package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SCIM constants and small map helpers. */
public final class ScimSupport {

    public static final String CONTENT_TYPE = "application/scim+json";
    public static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private ScimSupport() {}

    public static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, val) -> copy.put(String.valueOf(key), val));
                result.add(copy);
            }
        }
        return result;
    }

    public static String etag(long version) {
        return "W/\"" + version + "\"";
    }

    public static void checkVersion(Long expected, long actual) {
        if (expected != null && expected != actual) {
            throw new IamException(
                    IamErrorCode.SCIM_PRECONDITION_FAILED,
                    "resource version mismatch: expected " + expected + ", actual " + actual);
        }
    }

    public static Long parseVersion(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IamException(
                    IamErrorCode.SCIM_BAD_REQUEST, "invalid If-Match/ETag value: " + header);
        }
    }

    public static Map<String, Object> meta(
            String resourceType, String location, long version, Instant created, Instant modified) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", resourceType);
        meta.put("location", location);
        meta.put("version", etag(version));
        if (created != null) {
            meta.put("created", created.toString());
        }
        if (modified != null) {
            meta.put("lastModified", modified.toString());
        }
        return meta;
    }

    public static Map<String, Object> error(String detail, int status, String scimType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ERROR_SCHEMA));
        body.put("detail", detail);
        body.put("status", String.valueOf(status));
        if (scimType != null && !scimType.isBlank()) {
            body.put("scimType", scimType);
        }
        return body;
    }
}
