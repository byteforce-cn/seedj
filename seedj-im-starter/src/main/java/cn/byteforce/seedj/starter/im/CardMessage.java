package cn.byteforce.seedj.starter.im;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 系统卡片：结构化标题/正文/动作，外加老客户端可读的纯文本降级。
 *
 * @param schema 卡片 schema（例如 {@code agent.approval.card.v1}）
 * @param title 标题
 * @param text 正文
 * @param fallbackText 纯文本降级；不能为空
 * @param cardId 卡片幂等键；{@code null} 表示每次新卡
 * @param data 附加在 body 顶层的结构化数据（例如 {@code decision_id}）
 * @param actions 按钮
 * @author ByteForce
 * @since 1.0.0
 */
public record CardMessage(
        String schema,
        String title,
        String text,
        String fallbackText,
        String cardId,
        Map<String, Object> data,
        List<CardAction> actions) {

    public CardMessage {
        schema = text(schema, "schema");
        title = text(title, "title");
        fallbackText = text(fallbackText, "fallbackText");
        cardId = cardId == null || cardId.isBlank() ? null : cardId;
        data = data == null ? Map.of() : Map.copyOf(data);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("actions cannot be empty");
        }
    }

    /**
     * 卡片协议 body：结构化字段与 {@code data} 合并到顶层。
     *
     * @return 可存储的 body
     */
    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", schema);
        body.put("title", title);
        if (text != null && !text.isBlank()) {
            body.put("text", text);
        }
        body.put("fallback_text", fallbackText);
        if (cardId != null) {
            body.put("card_id", cardId);
        }
        body.putAll(data);
        body.put(
                "actions",
                actions.stream()
                        .map(action -> Map.of("id", action.id(), "label", action.label()))
                        .toList());
        return body;
    }

    /**
     * 卡片按钮。
     *
     * @param id 动作标识（回传时原样带回）
     * @param label 按钮文字
     */
    public record CardAction(String id, String label) {

        public CardAction {
            id = text(id, "id");
            label = text(label, "label");
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
