package cn.byteforce.seedj.starter.eis.core;

import java.util.List;

/**
 * 命令处理器结果：事件草稿列表 + 主对象指针（可选）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisCommandHandlerResult {

    private final List<EisEventDraft> events;

    private final EisObjectRef created;

    /**
     * 构造处理器结果。
     *
     * @param events  事件草稿列表（不可空，可为空列表）
     * @param created 主对象指针（可空）
     */
    public EisCommandHandlerResult(List<EisEventDraft> events, EisObjectRef created) {
        this.events = events == null ? List.of() : List.copyOf(events);
        this.created = created;
    }

    /**
     * 获取事件草稿列表。
     *
     * @return 事件草稿列表
     */
    public List<EisEventDraft> getEvents() {
        return events;
    }

    /**
     * 获取主对象指针。
     *
     * @return 对象引用；无则为 {@code null}
     */
    public EisObjectRef getCreated() {
        return created;
    }
}
