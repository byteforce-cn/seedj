package cn.byteforce.seedj.infra.coord;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 能力私有 KV 键空间：{@code <namespace>:coord:<capability>:<key>}。
 *
 * <p>同一能力的记录都落在一个前缀下，才能用一次 range 扫描取齐。键、后缀与命名空间都不允许含 NUL （coord 存储编码用 NUL 作分隔）。
 */
final class CoordKeySpace {

    private static final String ROOT = ":coord:";

    private final String prefix;

    private CoordKeySpace(String prefix) {
        this.prefix = prefix;
    }

    static CoordKeySpace of(String namespace, String capability) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("coord namespace must not be blank");
        }
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("coord capability must not be blank");
        }
        requireNoNul(namespace, "namespace");
        requireNoNul(capability, "capability");
        return new CoordKeySpace(namespace + ROOT + capability + ":");
    }

    String key(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        requireNoNul(suffix, "suffix");
        return prefix + suffix;
    }

    String prefix() {
        return prefix;
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 前缀扫描的上界：前缀最后一个非 {@code 0xFF} 字节加一。
     *
     * <p>UTF-8 字节序与 KV 的字节序一致，所以上界可以覆盖所有以该前缀开头的键。
     */
    static byte[] rangeEndOf(String prefix) {
        byte[] bytes = bytes(prefix);
        for (int i = bytes.length - 1; i >= 0; i--) {
            int value = bytes[i] & 0xFF;
            if (value != 0xFF) {
                byte[] end = Arrays.copyOf(bytes, i + 1);
                end[i] = (byte) (value + 1);
                return end;
            }
        }
        throw new IllegalArgumentException("coord key prefix is all 0xFF");
    }

    private static void requireNoNul(String value, String name) {
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
    }
}
