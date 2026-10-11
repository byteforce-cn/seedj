package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 规范化 JSON：确定性文本序列化的唯一入口（跨语言可比对）。
 *
 * <p>两套模式，均有明确参考实现：</p>
 * <ul>
 *   <li>{@link #canonicalize(JsonNode)}——<b>审计规范化</b>：键按字典序排序、无多余空白
 *       （{@code {"a":1,"b":2}}）、UTF-8 不转义非 ASCII；语义权威为
 *       {@code 05-audit-contract/hash-chain.spec.md §2}（测试向量
 *       {@code 447c6e29…7410} 逐位校验）；</li>
 *   <li>{@link #pythonDumps(JsonNode)}——<b>幂等指纹模式</b>：与参考内核
 *       （{@code kernel.py}）的 {@code json.dumps(..., sort_keys=True, ensure_ascii=False)}
 *       对齐（分隔符为 {@code ", "} / {@code ": "}），用于同幂等键内容比对。</li>
 * </ul>
 *
 * <p><b>规则覆盖声明</b>：字符串转义与整数字面量与参考实现一致；浮点数的极端
 * 表现形式（如 {@code 1e+30}）与 Python {@code repr} 存在已知差异——EIS 域内数值
 * 按契约以整数或字符串表达（金额字符串化），该差异不落在契约面。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CanonicalJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 按 Unicode 码点排序（与 Python {@code sort_keys} 一致；Java 默认 UTF-16 序在补充平面字符上会不同）。 */
    private static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.codePointCount(i, a.length()), b.codePointCount(j, b.length()));
    };

    private CanonicalJson() {
    }

    /**
     * 审计规范化（无空白、键排序）。
     *
     * @param node JSON 节点；{@code null} 视为 {@code null} 字面量
     * @return 规范化文本
     */
    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb, ",", ":");
        return sb.toString();
    }

    /**
     * 审计 payload 规范化：空白或缺失输入视为 {@code {}}（规范 §2）。
     *
     * @param rawJson 原始 JSON 文本（可为空）
     * @return 规范化文本
     */
    public static String canonicalizePayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        JsonNode node = MAPPER.readTree(rawJson);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "{}";
        }
        return canonicalize(node);
    }

    /**
     * 幂等指纹模式（与参考内核 {@code json.dumps} 缺省分隔符对齐）。
     *
     * @param node JSON 节点
     * @return 含 {@code ", "} / {@code ": "} 分隔符的确定性文本
     */
    public static String pythonDumps(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb, ", ", ": ");
        return sb.toString();
    }

    /**
     * 解析 JSON 文本（宽松：空文本返回 {@code null}）。
     *
     * @param rawJson JSON 文本
     * @return 解析后的节点；空文本为 {@code null}
     */
    public static JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        return MAPPER.readTree(rawJson);
    }

    private static void write(JsonNode node, StringBuilder sb, String itemSep, String kvSep) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            writeObject(node, sb, itemSep, kvSep);
        } else if (node.isArray()) {
            writeArray(node, sb, itemSep, kvSep);
        } else if (node.isString()) {
            writeString(node.stringValue(), sb);
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
        } else if (node.isFloatingPointNumber()) {
            sb.append(numberText(node.doubleValue()));
        } else {
            throw new IllegalArgumentException("不支持的 JSON 节点类型: " + node.getNodeType());
        }
    }

    private static void writeObject(JsonNode node, StringBuilder sb, String itemSep, String kvSep) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.propertyNames().iterator();
        while (it.hasNext()) {
            names.add(it.next());
        }
        names.sort(CODE_POINT_ORDER);
        sb.append('{');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(itemSep);
            }
            writeString(names.get(i), sb);
            sb.append(kvSep);
            write(node.get(names.get(i)), sb, itemSep, kvSep);
        }
        sb.append('}');
    }

    private static void writeArray(JsonNode node, StringBuilder sb, String itemSep, String kvSep) {
        sb.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) {
                sb.append(itemSep);
            }
            write(node.get(i), sb, itemSep, kvSep);
        }
        sb.append(']');
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * 数字字面量：整数精确；浮点采用最短十进制（整值保留一位小数，与 repr 对齐）。
     */
    private static String numberText(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON 不支持 NaN/Infinity: " + value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e16) {
            return String.format("%.1f", value);
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.toPlainString();
    }
}
