package cn.byteforce.seedj.infra.jdbc.im;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * IM JDBC 适配器共用的 JSON 助手：消息体在库里是 {@code jsonb}，在 Java 里是 {@code Map}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImJdbcJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private ImJdbcJson() {}

    static String write(Map<String, Object> value) {
        return JSON.writeValueAsString(value == null ? Map.of() : value);
    }

    static Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(JSON.readValue(json, MAP));
    }
}
