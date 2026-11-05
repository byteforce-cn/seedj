package cn.byteforce.seedj.core.utils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

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
 * <p><b>健壮性声明</b>：</p>
 * <ul>
 *   <li>输入嵌套深度上限 {@value #MAX_DEPTH}，超限抛 {@link IllegalArgumentException}；</li>
 *   <li>非法 JSON 文本在 {@link #parse} / {@link #canonicalizePayload} 中统一包装为
 *       {@link IllegalArgumentException}，原始异常作为 cause 保留；</li>
 *   <li>对象重复键依赖底层解析器的默认策略（后者覆盖），与 Python
 *       {@code object_pairs_hook} 的差异不在本类保证范围内。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CanonicalJsonHelper {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 递归深度上限，防御恶意嵌套导致的栈溢出。 */
    private static final int MAX_DEPTH = 512;

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

    private CanonicalJsonHelper() {
    }

    /**
     * 审计规范化（无空白、键排序）。
     *
     * @param node JSON 节点；{@code null} 视为 {@code null} 字面量
     * @return 规范化文本
     * @throws IllegalArgumentException 嵌套超过 {@value #MAX_DEPTH} 或含不支持的节点类型
     */
    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb, ",", ":", 0);
        return sb.toString();
    }

    /**
     * 审计 payload 规范化：空白或缺失输入视为 {@code {}}（规范 §2）。
     *
     * @param rawJson 原始 JSON 文本（可为空）
     * @return 规范化文本
     * @throws IllegalArgumentException 文本非法或嵌套超限
     */
    public static String canonicalizePayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        JsonNode node = readTree(rawJson);
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
     * @throws IllegalArgumentException 嵌套超过 {@value #MAX_DEPTH} 或含不支持的节点类型
     */
    public static String pythonDumps(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb, ", ", ": ", 0);
        return sb.toString();
    }

    /**
     * 解析 JSON 文本（宽松：空文本返回 {@code null}）。
     *
     * @param rawJson JSON 文本
     * @return 解析后的节点；空文本为 {@code null}
     * @throws IllegalArgumentException 文本非法
     */
    public static JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        return readTree(rawJson);
    }

    private static JsonNode readTree(String rawJson) {
        try {
            return MAPPER.readTree(rawJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无法解析 JSON 文本", e);
        }
    }

    private static void write(JsonNode node, StringBuilder sb, String itemSep, String kvSep, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON 嵌套深度超过上限: " + MAX_DEPTH);
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            writeObject(node, sb, itemSep, kvSep, depth);
        } else if (node.isArray()) {
            writeArray(node, sb, itemSep, kvSep, depth);
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

    private static void writeObject(JsonNode node, StringBuilder sb, String itemSep, String kvSep, int depth) {
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
            write(node.get(names.get(i)), sb, itemSep, kvSep, depth + 1);
        }
        sb.append('}');
    }

    private static void writeArray(JsonNode node, StringBuilder sb, String itemSep, String kvSep, int depth) {
        sb.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) {
                sb.append(itemSep);
            }
            write(node.get(i), sb, itemSep, kvSep, depth + 1);
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
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * 数字字面量渲染。
     *
     * <p>整数值浮点（{@code |v| < 1e16}）保留一位小数（如 {@code "1.0"}）；
     * 其余走 {@link BigDecimal#toPlainString()} 的纯十进制表示。</p>
     *
     * <p><b>已知差异</b>：与 Python {@code repr} 在指数表示形式（如 {@code 1e+16}、
     * {@code 1e-07}）上不一致，详见类头“规则覆盖声明”。</p>
     */
    private static String numberText(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON 不支持 NaN/Infinity: " + value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e16) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.toPlainString();
    }
}