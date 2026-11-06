package cn.byteforce.seedj.core.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 摘要工具：SHA-256 小写 hex（幂等指纹与审计链共用）。
 *
 * <p><b>配合契约</b>：审计链的摘要输入应为
 * {@link CanonicalJson#canonicalizePayload(String)} 的输出，
 * 而非原始 JSON 文本——否则同一语义的不同空白/键序会产出不同摘要。</p>
 *
 * <p><b>确定性</b>：本类每次调用新建 {@link MessageDigest}（非线程安全，
 * 不共享），保证并发下摘要结果与单线程一致；不使用静态实例缓存。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HashesHelper {

    /** 摘要算法（Java SE 规范强制支持，实际不会缺失）。 */
    private static final String ALGORITHM = "SHA-256";

    /** 无状态 hex 格式化器，小写、无分隔符，线程安全。 */
    private static final HexFormat HEX = HexFormat.of();

    private HashesHelper() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 小写 hex。
     *
     * @param text 输入文本，不可为 {@code null}
     * @return 64 字符小写 hex 摘要（对应 32 字节 / 256 位）
     * @throws NullPointerException {@code text} 为 {@code null}
     */
    public static String sha256Hex(String text) {
        Objects.requireNonNull(text, "text");
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算字节序列的 SHA-256 小写 hex。
     *
     * @param bytes 输入字节，不可为 {@code null}
     * @return 64 字符小写 hex 摘要（对应 32 字节 / 256 位）
     * @throws NullPointerException {@code bytes} 为 {@code null}
     * @throws IllegalStateException JVM 不提供 SHA-256（规范上不可达）
     */
    public static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HEX.formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 " + ALGORITHM, ex);
        }
    }
}
