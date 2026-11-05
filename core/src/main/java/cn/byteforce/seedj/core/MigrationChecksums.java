package cn.byteforce.seedj.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 迁移内容的规范化与 checksum 工具。
 *
 * <p>checksum 是跨适配层的协议：同一份内容在任何适配层、任何时间
 * 必须算出相同结果，因此算法与规范化规则统一在 core 中定义，
 * 各适配层不得自行实现。
 */
public final class MigrationChecksums {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private MigrationChecksums() {
    }

    /**
     * 对内容先做保守规范化，再计算 SHA-256，返回 64 位小写十六进制字符串。
     */
    public static String checksum(String content) {
        return sha256(normalize(content));
    }

    /**
     * 保守规范化：统一换行符、去行尾空白、strip 首尾。
     *
     * <p>只处理对文本普遍安全的变换，<b>不做</b>内部空白折叠——那会破坏
     * SQL 引号内容、JSON 字符串字面量、{@code $tag$...$tag$} 等语法。
     */
    public static String normalize(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : content.split("\\R")) {
            sb.append(line.stripTrailing()).append('\n');
        }
        return sb.toString().strip();
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xff;
                out[i * 2] = HEX[b >>> 4];
                out[i * 2 + 1] = HEX[b & 0x0f];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
