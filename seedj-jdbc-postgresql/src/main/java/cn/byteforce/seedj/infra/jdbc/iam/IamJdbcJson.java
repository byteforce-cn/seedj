package cn.byteforce.seedj.infra.jdbc.iam;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON helpers shared by the IAM JDBC adapters. */
final class IamJdbcJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private IamJdbcJson() {
    }

    static String map(Map<String, Object> value) {
        return JSON.writeValueAsString(value == null ? Map.of() : value);
    }

    static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(JSON.readValue(json, MAP));
    }

    static String strings(Collection<String> value) {
        return JSON.writeValueAsString(value == null ? List.of() : value);
    }

    static List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, STRINGS);
    }
}
