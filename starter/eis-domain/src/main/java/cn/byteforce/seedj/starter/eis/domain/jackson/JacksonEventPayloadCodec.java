package cn.byteforce.seedj.starter.eis.domain.jackson;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;
import cn.byteforce.seedj.starter.eis.domain.spi.EventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcaster;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcasterRegistry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Jackson 实现的 {@link EventPayloadCodec}，upcasting 真正发生的地方。
 *
 * <p>读取分三种情况：</p>
 * <ul>
 *   <li>{@code stored == current}：直接反序列化；</li>
 *   <li>{@code stored < current}：先读成结构化 Map，逐版本 upcast，最后映射到当前类；</li>
 *   <li>{@code stored > current}：数据比代码新（回滚部署 / 连错库），直接失败。</li>
 * </ul>
 *
 * <p><b>不要注入 Web 层的 ObjectMapper</b>：{@code starter-json} 把 {@code Long} 序列化成字符串，
 * 而事件 payload 是内部存储格式，参与 upcaster 的 Map 运算与哈希，必须保持数值形态。
 * 请使用独立的 {@code JsonMapper.builder().build()}（自动配置已如此装配）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JacksonEventPayloadCodec implements EventPayloadCodec {

    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    private final EventTypeRegistry eventTypes;

    private final EventUpcasterRegistry upcasters;

    /**
     * 构造编解码器。
     *
     * @param objectMapper 专用 ObjectMapper（不得复用 Web 序列化契约）
     * @param eventTypes   事件类型注册表
     * @param upcasters    upcaster 注册表
     */
    public JacksonEventPayloadCodec(ObjectMapper objectMapper, EventTypeRegistry eventTypes,
                                    EventUpcasterRegistry upcasters) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.eventTypes = Objects.requireNonNull(eventTypes, "eventTypes must not be null");
        this.upcasters = Objects.requireNonNull(upcasters, "upcasters must not be null");
    }

    @Override
    public String encode(DomainEvent event) {
        Objects.requireNonNull(event, "domain event must not be null");
        return objectMapper.writeValueAsString(event);
    }

    @Override
    public DomainEvent decode(String eventType, int schemaVersion, String payload) {
        Class<? extends DomainEvent> current = eventTypes.resolve(eventType);
        int currentVersion = eventTypes.versionOf(current);

        if (schemaVersion == currentVersion) {
            return objectMapper.readValue(payload, current);
        }
        if (schemaVersion > currentVersion) {
            throw new IllegalStateException("""
                    Event "%s" was written with schema v%d, but this build only knows v%d.
                    The stored data is ahead of the running code (rollback deployment, or the app
                    points at a database written by a newer version).
                    Fix: deploy the code that knows v%d, or add @EventType(version = %d) with the
                         matching event fields.
                    """.formatted(eventType, schemaVersion, currentVersion, schemaVersion, schemaVersion));
        }
        if (schemaVersion < 1) {
            throw new IllegalStateException("""
                    Event "%s" has stored schema_version=%d, but versions start at 1.
                    Fix: check evt_log.schema_version for rows written outside this framework.
                    """.formatted(eventType, schemaVersion));
        }

        List<EventUpcaster> chain = upcasters.chainFor(eventType, schemaVersion, currentVersion);
        Map<String, Object> node = objectMapper.readValue(payload, PAYLOAD);
        for (EventUpcaster step : chain) {
            node = step.upcast(node);
            if (node == null) {
                throw new IllegalStateException("""
                        %s returned null while upcasting "%s" v%d -> v%d.
                        Fix: mutate the payload map in place and return it (see EventUpcaster.of).
                        """.formatted(step, eventType, step.fromVersion(), step.toVersion()));
            }
        }
        return objectMapper.convertValue(node, current);
    }
}
