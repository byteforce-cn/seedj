package cn.byteforce.seedj.core.security;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * 调用方标识 → 命令 actor 的编解码。
 *
 * <p>IdP 的主体标识不一定是 UUID：OIDC {@code sub} 常见为 {@code "u-1"}、邮箱或
 * 拼接串。命令运行时的 actor 是 UUID 契约，因此边界必须显式选择策略：</p>
 *
 * <ul>
 *   <li>{@link #uuid()}：要求 {@code Caller.id()} 本身是 UUID；否则明确返回 401，
 *       不静默失败；</li>
 *   <li>{@link #nameUuid()}：把任意主体标识稳定映射为 UUID（名称型），适用于 IdP
 *       不发放 UUID 的部署；同一标识始终得到同一 UUID。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CallerIdCodec {

    /**
     * 把调用方映射为命令 actor。
     *
     * @param caller 调用方
     * @return actor 标识
     */
    UUID toActor(Caller caller);

    /**
     * 主体标识必须是 UUID。
     *
     * @return UUID 策略
     */
    static CallerIdCodec uuid() {
        return caller -> {
            String id = requireId(caller);
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException error) {
                throw new ContractException(ContractErrorCode.UNAUTHENTICATED,
                        "caller id is not a UUID: " + id);
            }
        };
    }

    /**
     * 把任意主体标识稳定映射为名称型 UUID（RFC 4122 v3）。
     *
     * <p>映射是单向且确定性的：不要把它当作用户身份存储，也不要跨环境复用同一命名空间。</p>
     *
     * @return 名称 UUID 策略
     */
    static CallerIdCodec nameUuid() {
        return caller -> UUID.nameUUIDFromBytes(
                ("seedj:caller:" + requireId(caller)).getBytes(StandardCharsets.UTF_8));
    }

    private static String requireId(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        String id = caller.id();
        if (id == null || id.isBlank()) {
            throw new ContractException(ContractErrorCode.UNAUTHENTICATED,
                    "caller id is required");
        }
        return id;
    }
}
