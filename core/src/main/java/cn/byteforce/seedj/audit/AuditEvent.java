package cn.byteforce.seedj.audit;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 审计摘要使用的事件投影：只保留参与承诺的事件 id / 类型 / 正文。
 *
 * <p>投影按审计条目 {@code payload.event_ids} 的顺序传给 {@link AuditPayloadDigest}；本类型不负责排序。
 *
 * @param id 事件 ID（UUID）
 * @param type 事件类型
 * @param payload 事件正文；{@code null} 视为空对象
 * @author ByteForce
 * @since 1.0.0
 */
public record AuditEvent(UUID id, String type, JsonNode payload) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        payload = payload == null ? MAPPER.createObjectNode() : payload;
    }
}
