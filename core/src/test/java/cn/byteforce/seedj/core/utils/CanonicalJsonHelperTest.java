package cn.byteforce.seedj.core.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 规范化 JSON：确定性键序、转义、数字字面量与深度防护。 */
class CanonicalJsonHelperTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    @DisplayName("对象键按 Unicode 码点排序，无多余空白")
    void canonicalizeSortsKeys() {
        JsonNode node = CanonicalJsonHelper.parse("{\"b\":2, \"a\":1}");

        assertThat(CanonicalJsonHelper.canonicalize(node)).isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(CanonicalJsonHelper.pythonDumps(node)).isEqualTo("{\"a\": 1, \"b\": 2}");
    }

    @Test
    @DisplayName("键前缀关系按剩余码点数决定顺序")
    void canonicalizeOrdersKeyPrefixes() {
        JsonNode node = CanonicalJsonHelper.parse("{\"ab\":1,\"a\":2}");

        assertThat(CanonicalJsonHelper.canonicalize(node)).isEqualTo("{\"a\":2,\"ab\":1}");
    }

    @Test
    @DisplayName("数组保持顺序并使用模式分隔符")
    void canonicalizeArrays() {
        JsonNode node = CanonicalJsonHelper.parse("[1,true,false,null,\"s\"]");

        assertThat(CanonicalJsonHelper.canonicalize(node)).isEqualTo("[1,true,false,null,\"s\"]");
        assertThat(CanonicalJsonHelper.pythonDumps(node))
                .isEqualTo("[1, true, false, null, \"s\"]");
    }

    @Test
    @DisplayName("字符串转义覆盖引号、反斜杠、控制字符与非 ASCII")
    void canonicalizeEscapesStrings() {
        String text = "\"" + "\\" + "\b\f\n\r\t" + (char) 1 + "汉";
        String expected =
                "\"" + "\\\"" + "\\\\" + "\\b" + "\\f" + "\\n" + "\\r" + "\\t" + "\\" + "u0001"
                        + "汉" + "\"";

        assertThat(CanonicalJsonHelper.canonicalize(NODES.textNode(text))).isEqualTo(expected);
    }

    @Test
    @DisplayName("整数值浮点保留一位小数，其余走纯十进制")
    void canonicalizeFloats() {
        assertThat(CanonicalJsonHelper.canonicalize(NODES.numberNode(1.0d))).isEqualTo("1.0");
        assertThat(CanonicalJsonHelper.canonicalize(NODES.numberNode(1.5d))).isEqualTo("1.5");
        assertThat(CanonicalJsonHelper.canonicalize(NODES.numberNode(1e17)))
                .isEqualTo("100000000000000000");
        assertThat(CanonicalJsonHelper.canonicalize(NODES.numberNode(new BigDecimal("2.50"))))
                .isEqualTo("2.5");
        assertThat(CanonicalJsonHelper.canonicalize(NODES.numberNode(new BigDecimal("7"))))
                .isEqualTo("7.0");
    }

    @Test
    @DisplayName("NaN/Infinity 被拒绝")
    void rejectsNonFiniteNumbers() {
        assertThatThrownBy(() -> CanonicalJsonHelper.canonicalize(NODES.numberNode(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN/Infinity");
        assertThatThrownBy(
                        () ->
                                CanonicalJsonHelper.canonicalize(
                                        NODES.numberNode(Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN/Infinity");
    }

    @Test
    @DisplayName("null 节点渲染为 null 字面量")
    void canonicalizeNullNode() {
        assertThat(CanonicalJsonHelper.canonicalize(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("不支持的节点类型显式失败")
    void rejectsUnsupportedNodeType() {
        JsonNode binary = NODES.binaryNode(new byte[] {1, 2});

        assertThatThrownBy(() -> CanonicalJsonHelper.canonicalize(binary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 JSON 节点类型");
    }

    @Test
    @DisplayName("嵌套深度超过上限时失败")
    void rejectsTooDeepNesting() {
        ArrayNode node = NODES.arrayNode();
        for (int i = 0; i < 520; i++) {
            ArrayNode parent = NODES.arrayNode();
            parent.add(node);
            node = parent;
        }
        JsonNode deep = node;

        assertThatThrownBy(() -> CanonicalJsonHelper.canonicalize(deep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("嵌套深度超过上限");
    }

    @Test
    @DisplayName("payload 规范化把空白与 null 视为空对象")
    void canonicalizePayloadNormalises() {
        assertThat(CanonicalJsonHelper.canonicalizePayload(null)).isEqualTo("{}");
        assertThat(CanonicalJsonHelper.canonicalizePayload("  ")).isEqualTo("{}");
        assertThat(CanonicalJsonHelper.canonicalizePayload("null")).isEqualTo("{}");
        assertThat(CanonicalJsonHelper.canonicalizePayload("{\"b\":1,\"a\":2}"))
                .isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    @DisplayName("parse 对空白返回 null，对非法文本给出原因")
    void parseHandlesEmptyAndInvalid() {
        assertThat(CanonicalJsonHelper.parse(null)).isNull();
        assertThat(CanonicalJsonHelper.parse("   ")).isNull();
        assertThat(CanonicalJsonHelper.parse("{\"a\":1}")).isNotNull();
        assertThatThrownBy(() -> CanonicalJsonHelper.parse("{oops"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析 JSON 文本")
                .hasCauseInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> CanonicalJsonHelper.canonicalizePayload("{oops"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
