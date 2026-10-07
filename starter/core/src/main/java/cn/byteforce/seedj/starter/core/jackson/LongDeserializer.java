package cn.byteforce.seedj.starter.core.jackson;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Long 反序列化器：同时接受字符串与数字形式（与
 * {@link LongToStringSerializer} 对称，ADR-0003）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class LongDeserializer extends ValueDeserializer<Long> {

    private LongDeserializer() {}

    /** 单例。 */
    public static final LongDeserializer INSTANCE = new LongDeserializer();

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();

        // null 值
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        // 字符串形式
        if (token == JsonToken.VALUE_STRING) {
            String text = p.getText().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException e) {
                throw InvalidFormatException.from(p,
                        "无法将字符串解析为 Long: " + text, text, Long.class);
            }
        }

        // 数字形式
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }

        // 其他类型交给 Jackson 默认逻辑
        return (Long) ctxt.handleUnexpectedToken(Long.class, p);
    }
}