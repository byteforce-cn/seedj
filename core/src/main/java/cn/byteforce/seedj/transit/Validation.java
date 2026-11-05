package cn.byteforce.seedj.transit;

import java.util.List;

final class Validation {
    private Validation() {}

    public static String requireKeyName(String keyName) {
        if (keyName == null || keyName.isBlank()) throw new IllegalArgumentException("keyName 不能为 null 或纯空白字符串");
        if (!keyName.equals(keyName.trim())) throw new IllegalArgumentException("keyName 不允许包含首尾空白字符");
        return keyName;
    }

    public static String normalizeContext(String context) {
        if (context == null || context.isEmpty()) return null;
        if (context.isBlank() || !context.equals(context.trim())) throw new IllegalArgumentException("context 不允许包含任何空白字符");
        Base64Validator.validate(context);
        return context;
    }

    public static String requireReference(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference 不能为 null 或纯空白字符串");
        return reference;
    }

    public static String requireBase64NonEmpty(String value, String fieldName) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(fieldName + " 不能为 null 或空字符串");
        Base64Validator.validate(value);
        return value;
    }

    public static Integer requirePositiveVersion(Integer version) {
        if (version != null && version <= 0) throw new IllegalArgumentException("keyVersion 必须 > 0");
        return version;
    }

    public static <T> List<T> requireBatchList(List<T> items) {
        if (items == null) throw new IllegalArgumentException("items 不能为 null");
        for (T item : items) {
            if (item == null) throw new IllegalArgumentException("items 不允许包含 null 元素");
        }
        return List.copyOf(items);
    }
}
