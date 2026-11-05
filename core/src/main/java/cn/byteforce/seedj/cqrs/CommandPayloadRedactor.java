package cn.byteforce.seedj.cqrs;

import java.util.Map;

/**
 * 命令记录载荷脱敏 seam：只影响落库载荷，不改变幂等指纹。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CommandPayloadRedactor {

    Map<String, Object> redact(Map<String, Object> payload);

    static CommandPayloadRedactor noop() {
        return payload -> payload;
    }
}
