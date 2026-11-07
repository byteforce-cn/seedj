package cn.byteforce.seedj.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 读取 eis 权威 Schema 与 manifest 的最小测试辅助：不引入新的运行时依赖。
 *
 * <p>只解析对象契约需要的结构：{@code title}、{@code allOf} 下的 {@code properties}/{@code required}，以及 manifest 的
 * {@code permissions[].id}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class EisSchemaReader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Pattern PERMISSION_ID =
            Pattern.compile("^\\s*-\\s+id:\\s+\"([^\"]+)\"\\s*$");

    private EisSchemaReader() {}

    /**
     * eis 对象契约的领域字段视图。
     *
     * @param title 契约标题
     * @param properties 领域字段（字段名 → 原始 JSON 结构）
     * @param required 必填字段
     */
    record EisObject(String title, Map<String, Object> properties, Set<String> required) {

        /**
         * 按名取字段结构。
         *
         * @param name 字段名
         * @return 字段结构
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> field(String name) {
            Object value = properties.get(name);
            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("eis schema has no field: " + name);
            }
            return (Map<String, Object>) value;
        }
    }

    /**
     * 读取对象 Schema。
     *
     * @param schemaFile eis schema 文件
     * @return 对象视图
     * @throws IOException 读取失败
     */
    static EisObject readObject(Path schemaFile) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(schemaFile, StandardCharsets.UTF_8));
        Map<String, Object> properties = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        collect(root, properties, required);
        return new EisObject(
                root.path("title").asString(schemaFile.getFileName().toString()),
                Map.copyOf(properties),
                Set.copyOf(required));
    }

    /**
     * 读取 manifest 中声明的权限 id。
     *
     * @param manifestFile eis manifest
     * @return 权限 id
     * @throws IOException 读取失败
     */
    static Set<String> readPermissionIds(Path manifestFile) throws IOException {
        List<String> lines = Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
        Set<String> ids = new LinkedHashSet<>();
        boolean inPermissions = false;
        for (String line : lines) {
            if (line.matches("^\\s*permissions:\\s*$")) {
                inPermissions = true;
                continue;
            }
            if (!inPermissions) {
                continue;
            }
            if (!line.isBlank() && !line.startsWith("    ")) {
                break;
            }
            Matcher matcher = PERMISSION_ID.matcher(line);
            if (matcher.matches()) {
                ids.add(matcher.group(1));
            }
        }
        return Set.copyOf(ids);
    }

    private static void collect(
            JsonNode node, Map<String, Object> properties, Set<String> required) {
        if (node == null || node.isNull()) {
            return;
        }
        JsonNode childRequired = node.get("required");
        if (childRequired != null && childRequired.isArray()) {
            for (JsonNode field : childRequired.values()) {
                required.add(field.asString());
            }
        }
        JsonNode childProperties = node.get("properties");
        if (childProperties != null && childProperties.isObject()) {
            for (Map.Entry<String, JsonNode> entry : childProperties.properties()) {
                properties.put(entry.getKey(), toJava(entry.getValue()));
            }
        }
        JsonNode allOf = node.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode part : allOf.values()) {
                collect(part, properties, required);
            }
        }
    }

    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.stringValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node.values()) {
                values.add(toJava(item));
            }
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            Collection<Map.Entry<String, JsonNode>> entries = node.properties();
            for (Map.Entry<String, JsonNode> entry : entries) {
                values.put(entry.getKey(), toJava(entry.getValue()));
            }
            return values;
        }
        return node.asString();
    }
}
