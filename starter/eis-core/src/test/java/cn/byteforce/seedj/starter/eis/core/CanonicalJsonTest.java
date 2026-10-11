package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CanonicalJson} 规范化契约测试（审计模式 / 指纹模式 / 转义 / 排序 / 数字）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CanonicalJsonTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void canonicalizeSortsKeysWithoutWhitespace() {
        JsonNode node = MAPPER.readTree("{\"b\": 2, \"a\": 1, \"nested\": {\"y\": [1, 2], \"x\": \"s\"}}");

        assertThat(CanonicalJson.canonicalize(node))
                .isEqualTo("{\"a\":1,\"b\":2,\"nested\":{\"x\":\"s\",\"y\":[1,2]}}");
    }

    @Test
    void canonicalizeEscapesControlCharsAndKeepsUnicodeRaw() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("s", "a\"b\\c\n\t\u0001é");

        assertThat(CanonicalJson.canonicalize(node))
                .isEqualTo("{\"s\":\"a\\\"b\\\\c\\n\\t\\u0001é\"}");
    }

    @Test
    void canonicalizeSortsByCodePointNotUtf16Unit() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("\uD83D\uDE00", 2); // 😀 U+1F600（补充平面）
        node.put("\uE000", 1);       // 私用区 U+E000（BMP 高位区，UTF-16 序会更靠后）

        assertThat(CanonicalJson.canonicalize(node)).isEqualTo("{\"\uE000\":1,\"\uD83D\uDE00\":2}");
    }

    @Test
    void canonicalizeHandlesScalarsAndNumbers() {
        assertThat(CanonicalJson.canonicalize(MAPPER.readTree("null"))).isEqualTo("null");
        assertThat(CanonicalJson.canonicalize(MAPPER.readTree("true"))).isEqualTo("true");
        assertThat(CanonicalJson.canonicalize(MAPPER.readTree("42"))).isEqualTo("42");
        assertThat(CanonicalJson.canonicalize(MAPPER.readTree("123456789012345678901234567890")))
                .isEqualTo("123456789012345678901234567890");
        assertThat(CanonicalJson.canonicalize(MAPPER.readTree("1.5"))).isEqualTo("1.5");
        assertThat(CanonicalJson.canonicalize(MAPPER.readTree("1.0"))).isEqualTo("1.0");
        assertThat(CanonicalJson.canonicalize(null)).isEqualTo("null");
    }

    @Test
    void canonicalizePayloadTreatsBlankAsEmptyObject() {
        assertThat(CanonicalJson.canonicalizePayload(null)).isEqualTo("{}");
        assertThat(CanonicalJson.canonicalizePayload("")).isEqualTo("{}");
        assertThat(CanonicalJson.canonicalizePayload("  ")).isEqualTo("{}");
        assertThat(CanonicalJson.canonicalizePayload("null")).isEqualTo("{}");
        assertThat(CanonicalJson.canonicalizePayload(" {\"b\": 2, \"a\": 1} "))
                .isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void pythonDumpsUsesSpacedSeparators() {
        JsonNode node = MAPPER.readTree("{\"b\": [1, 2], \"a\": {\"k\": \"v\"}}");

        assertThat(CanonicalJson.pythonDumps(node))
                .isEqualTo("{\"a\": {\"k\": \"v\"}, \"b\": [1, 2]}");
    }

    @Test
    void parseReturnsNullForBlankAndNodeForText() {
        assertThat(CanonicalJson.parse("")).isNull();
        assertThat(CanonicalJson.parse("   ")).isNull();
        assertThat(CanonicalJson.parse("{\"a\":1}").get("a").asInt()).isEqualTo(1);
    }
}
