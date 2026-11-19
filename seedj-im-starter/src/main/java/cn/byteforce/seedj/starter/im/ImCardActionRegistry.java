package cn.byteforce.seedj.starter.im;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 卡片 schema → 处理器；重复 schema 是启动失败。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImCardActionRegistry {

    private final Map<String, ImCardActionHandler> handlers;

    public ImCardActionRegistry(List<? extends ImCardActionHandler> handlers) {
        Map<String, ImCardActionHandler> map = new HashMap<>();
        if (handlers != null) {
            for (ImCardActionHandler handler : handlers) {
                Objects.requireNonNull(handler, "handler");
                ImCardActionHandler previous = map.putIfAbsent(handler.schema(), handler);
                if (previous != null) {
                    throw new IllegalStateException(
                            "duplicate card action handler for schema "
                                    + handler.schema()
                                    + ": "
                                    + previous.getClass().getName()
                                    + " and "
                                    + handler.getClass().getName());
                }
            }
        }
        this.handlers = Map.copyOf(map);
    }

    public Optional<ImCardActionHandler> find(String schema) {
        return Optional.ofNullable(handlers.get(schema));
    }
}
