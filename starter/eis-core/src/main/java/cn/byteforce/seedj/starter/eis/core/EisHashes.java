package cn.byteforce.seedj.starter.eis.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要工具：SHA-256 小写 hex（幂等指纹与审计链共用）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisHashes {

    private EisHashes() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 小写 hex。
     *
     * @param text 输入文本
     * @return 64 位小写 hex 摘要
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算字节序列的 SHA-256 小写 hex。
     *
     * @param bytes 输入字节
     * @return 64 位小写 hex 摘要
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
