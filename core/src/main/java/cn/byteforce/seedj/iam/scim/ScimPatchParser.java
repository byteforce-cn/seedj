package cn.byteforce.seedj.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts the JSON-ish PATCH body into validated operations. */
public final class ScimPatchParser {

    private ScimPatchParser() {}

    public static List<ScimPatchOperation> parse(Object body) {
        Object operations = body;
        if (body instanceof Map<?, ?> map && map.containsKey("Operations")) {
            operations = map.get("Operations");
        }
        if (!(operations instanceof List<?> list)) {
            throw invalid("PATCH body must contain an Operations array");
        }
        List<ScimPatchOperation> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw invalid("each operation must be an object");
            }
            Object op = map.get("op");
            Object path = map.get("path");
            Object value = map.get("value");
            if (op == null) {
                throw invalid("operation is missing op");
            }
            try {
                result.add(
                        new ScimPatchOperation(
                                String.valueOf(op),
                                path == null ? null : String.valueOf(path),
                                value));
            } catch (IllegalArgumentException e) {
                throw invalid(e.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private static IamException invalid(String message) {
        return new IamException(IamErrorCode.SCIM_PATCH_INVALID, "invalid SCIM PATCH: " + message);
    }
}
