package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * 事件草稿：命令处理器产出的事件意图，由运行时物化为 {@link EisEvent} 并落库。
 *
 * <p>{@code type} 遵循 {@code ^[a-z][a-z0-9_.]*$}（如 {@code iam.user.created}）；
 * {@code subjects} 为事件主体对象 ID 列表；{@code payload} 为事件承载数据
 * （不得包含敏感字段，如凭证散列）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisEventDraft {

    private final String type;

    private final List<UUID> subjects;

    private final JsonNode payload;

    /**
     * 构造事件草稿。
     *
     * @param type     事件类型
     * @param subjects 主体对象 ID 列表（可空）
     * @param payload  事件载荷（可空，落库时空载荷为 {@code {}}）
     */
    public EisEventDraft(String type, List<UUID> subjects, JsonNode payload) {
        this.type = type;
        this.subjects = subjects == null ? List.of() : List.copyOf(subjects);
        this.payload = payload;
    }

    /**
     * 获取事件类型。
     *
     * @return 事件类型
     */
    public String getType() {
        return type;
    }

    /**
     * 获取主体列表。
     *
     * @return 主体对象 ID 列表
     */
    public List<UUID> getSubjects() {
        return subjects;
    }

    /**
     * 获取事件载荷。
     *
     * @return 载荷 JSON；可能为 {@code null}
     */
    public JsonNode getPayload() {
        return payload;
    }
}
