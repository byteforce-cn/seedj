package cn.byteforce.seedj.cqrs;

import java.util.Objects;
import java.util.UUID;

/**
 * 内置标识编解码器。
 *
 * <p>覆盖框架自身会产出的三种标识：{@code String}（默认 {@code IdGenerator<String>}）、
 * {@code Long}（雪花原生）、{@code UUID}（EIS 审计契约）。业务值对象（如
 * {@code OrderId(String value)}）请自行实现 {@link IdCodec}，或包装内置实现。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IdCodecs {

    private IdCodecs() {
    }

    /**
     * 字符串标识：文本即标识本身。
     */
    public static IdCodec<String> strings() {
        return new IdCodec<>() {
            @Override
            public String encode(String id) {
                return requireText(id);
            }

            @Override
            public String decode(String encoded) {
                return requireText(encoded);
            }
        };
    }

    /**
     * 数值标识：十进制文本，无前导零。
     */
    public static IdCodec<Long> longs() {
        return new IdCodec<>() {
            @Override
            public String encode(Long id) {
                return Long.toString(Objects.requireNonNull(id, "id"));
            }

            @Override
            public Long decode(String encoded) {
                try {
                    return Long.parseLong(requireText(encoded));
                } catch (NumberFormatException e) {
                    throw new CqrsException(CqrsErrorCode.INVALID_ID,
                            "not a decimal long: " + encoded, e);
                }
            }
        };
    }

    /**
     * UUID 标识：RFC 4122 小写规范形式（带连字符）。
     */
    public static IdCodec<UUID> uuids() {
        return new IdCodec<>() {
            @Override
            public String encode(UUID id) {
                return Objects.requireNonNull(id, "id").toString();
            }

            @Override
            public UUID decode(String encoded) {
                try {
                    return UUID.fromString(requireText(encoded));
                } catch (IllegalArgumentException e) {
                    throw new CqrsException(CqrsErrorCode.INVALID_ID,
                            "not a UUID: " + encoded, e);
                }
            }
        };
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new CqrsException(CqrsErrorCode.INVALID_ID, "identifier must not be blank");
        }
        return value;
    }
}
