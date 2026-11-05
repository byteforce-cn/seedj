package cn.byteforce.seedj.transit;

final class Base64Validator {
    private Base64Validator() {}
    public static void validate(String base64) {
        if (base64 == null || base64.isEmpty()) {
            throw new IllegalArgumentException("Base64 字符串不能为空");
        }
        if (base64.length() % 4 != 0) {
            throw new IllegalArgumentException("Base64 长度必须是 4 的倍数（带填充）");
        }
        if (!base64.matches("^[A-Za-z0-9+/]+={0,2}$")) {
            throw new IllegalArgumentException("包含非标准 Base64 字符或空白字符");
        }
    }
}
