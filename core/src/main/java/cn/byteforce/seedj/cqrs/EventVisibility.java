package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.utils.FieldNames;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.event.DomainEvent;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 事件溯源事件字段的可见性校验。
 *
 * <p>默认路径在写 outbox 前按 schema 白名单过滤事件载荷；事件溯源路径的事件是类型化
 * record，平台无法安全过滤，因此在事务内失败关闭：事件属性落在 schema 且
 * {@code eventVisible=false} 时，命令整体回滚。schema 之外的额外属性（如聚合标识）允许。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class EventVisibility {

    private EventVisibility() {
    }

    /**
     * 校验事件属性不携带 writeOnly / sensitive 字段。
     *
     * @param events     待发布事件（先取后存，save 会清空）
     * @param schema     对象 schema
     * @param jsonMapper 事件属性展开
     * @throws IllegalStateException 事件携带不可见字段
     */
    static void requireVisible(List<? extends DomainEvent> events, ObjectSchema schema,
                               JsonMapper jsonMapper) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(jsonMapper, "jsonMapper");
        for (DomainEvent event : events) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = jsonMapper.convertValue(event, Map.class);
            for (String key : properties.keySet()) {
                String field = FieldNames.snakeCase(key);
                Optional<FieldDefinition> declared = schema.field(field);
                if (declared.isPresent() && !declared.get().eventVisible()) {
                    throw new IllegalStateException("event "
                            + event.getClass().getSimpleName()
                            + " carries non-event-visible field " + field);
                }
            }
        }
    }
}
