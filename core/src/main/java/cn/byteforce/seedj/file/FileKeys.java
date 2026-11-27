package cn.byteforce.seedj.file;

import java.nio.charset.StandardCharsets;

/**
 * 逻辑 key 的规则，所有 {@link FileService} provider 共用。
 *
 * <p>规则同时满足 coord 对象 key 与 S3/OSS 的对象 key：非空、≤512 字符、UTF-8 ≤1024 字节、 无控制字符 / NUL、不以 {@code /}
 * 开头或结尾、无空段、无 {@code .} / {@code ..} 段。 key 里的 {@code /} 只是普通字符，不代表目录。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class FileKeys {

    /** 字符数上限（S3 对象 key 为 1024 字节，这里按字符再收一道）。 */
    static final int MAX_CHARACTERS = 512;

    /** UTF-8 字节数上限（S3/OSS 对象 key 的上限）。 */
    static final int MAX_UTF8_BYTES = 1024;

    private FileKeys() {}

    /**
     * 校验 key，非法时抛 {@link FileErrorCode#INVALID_KEY}。
     *
     * @param key 待校验的逻辑 key
     */
    public static void requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw invalid("file key must not be blank");
        }
        if (key.length() > MAX_CHARACTERS) {
            throw invalid("file key is longer than " + MAX_CHARACTERS + " characters");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw invalid("file key is longer than " + MAX_UTF8_BYTES + " UTF-8 bytes");
        }
        if (key.startsWith("/") || key.endsWith("/")) {
            throw invalid("file key must not start or end with '/'");
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                throw invalid("file key must not contain control characters");
            }
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid("file key must not contain empty, '.' or '..' segments");
            }
        }
    }

    private static FileException invalid(String message) {
        return new FileException(FileErrorCode.INVALID_KEY, message);
    }
}
