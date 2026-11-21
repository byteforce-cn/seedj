package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.PageCursor;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalFilter;
import cn.byteforce.seedj.domain.TemporalSearch;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 读 HTTP 面的唯一参数解析器：通用对象面与声明式查询面共用。
 *
 * <p>保留字、过滤操作符、分页/时点/排序/游标与注入字符拒绝只在这里定义。两个入口的差异 只剩路由前缀与响应映射；同一个请求在两个入口必须得到相同的状态码与错误信封。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ObjectQueryParameters {

    /** 两个读入口共享的保留字；出现在查询串里不再作为过滤字段。 */
    public static final Set<String> RESERVED =
            Set.of(
                    "page",
                    "size",
                    "sort",
                    "direction",
                    "as_of",
                    "recorded_as_of",
                    "cursor",
                    "min_version");

    private static final List<String> INJECTION_MARKERS = List.of("'", "\"", ";", "--", "/*", "*/");

    private ObjectQueryParameters() {}

    /**
     * 解析后的查询参数。
     *
     * @param equals 等值过滤（字段 → 原始值）
     * @param filters 受限过滤 AST
     * @param page 分页
     * @param cursor keyset 游标；{@code null} 表示第一页
     * @param sortField 排序字段；{@code null} 表示用声明的默认值
     * @param direction 排序方向
     * @param asOf 业务时点
     * @param recordedAsOf 系统时点
     * @author ByteForce
     * @since 1.0.0
     */
    public record Parsed(
            Map<String, Object> equals,
            List<TemporalFilter> filters,
            PageRequest page,
            PageCursor cursor,
            String sortField,
            SortDirection direction,
            Instant asOf,
            Instant recordedAsOf) {

        public Parsed {
            equals = equals == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(equals));
            filters = filters == null ? List.of() : List.copyOf(filters);
            page = page == null ? PageRequest.defaults() : page;
            direction = direction == null ? SortDirection.ASC : direction;
            sortField = sortField == null || sortField.isBlank() ? null : sortField;
        }

        /**
         * 构造列表查询；没有显式 {@code sort} 时使用声明的默认排序。
         *
         * @param objectType 对象类型
         * @param defaultSort 声明的默认排序字段
         * @return 列表查询
         */
        public TemporalSearch toSearch(String objectType, String defaultSort) {
            return new TemporalSearch(
                    objectType,
                    equals,
                    filters,
                    asOf,
                    recordedAsOf,
                    null,
                    sortField == null ? defaultSort : sortField,
                    direction,
                    page,
                    cursor);
        }
    }

    /**
     * 解析查询串。
     *
     * @param params 原始参数（每个键取第一个值）
     * @return 解析结果
     */
    public static Parsed parse(Map<String, String> params) {
        Map<String, String> values = params == null ? Map.of() : params;
        Map<String, Object> equals = new LinkedHashMap<>();
        List<TemporalFilter> filters = new ArrayList<>();
        values.forEach(
                (name, value) -> {
                    if (RESERVED.contains(name)) {
                        return;
                    }
                    rejectInjection(name, value);
                    int separator = name.indexOf("__");
                    if (separator < 0) {
                        equals.put(name, value);
                        return;
                    }
                    filters.add(
                            filter(
                                    name.substring(0, separator),
                                    name.substring(separator + 2),
                                    value));
                });
        rejectListMinVersion(values.get("min_version"));
        PageRequest page =
                new PageRequest(intParam(values, "page", 1), intParam(values, "size", 20));
        PageCursor cursor =
                values.containsKey("cursor") ? PageCursor.parse(values.get("cursor")) : null;
        String sort = values.get("sort");
        if ((sort == null || sort.isBlank()) && cursor != null) {
            sort = cursor.sortField();
        }
        return new Parsed(
                equals,
                filters,
                page,
                cursor,
                sort,
                direction(values.get("direction")),
                instant(values.get("as_of"), "as_of"),
                instant(values.get("recorded_as_of"), "recorded_as_of"));
    }

    /**
     * 解析单对象读取的业务时点。
     *
     * @param raw 原始值
     * @return 时点；空为 {@code null}
     */
    public static Instant asOf(String raw) {
        return instant(raw, "as_of");
    }

    /**
     * 解析单对象读取的系统时点。
     *
     * @param raw 原始值
     * @return 时点；空为 {@code null}
     */
    public static Instant recordedAsOf(String raw) {
        return instant(raw, "recorded_as_of");
    }

    /**
     * 解析单对象读取的最小版本。
     *
     * @param raw 原始值
     * @return 版本；空为 {@code null}
     */
    public static Long minVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException error) {
            throw invalid("invalid min_version: " + raw);
        }
        if (value < 1) {
            throw invalid("min_version must be >= 1");
        }
        return value;
    }

    private static void rejectListMinVersion(String raw) {
        if (raw != null && !raw.isBlank()) {
            throw invalid("min_version is not supported for list queries");
        }
    }

    private static TemporalFilter filter(String field, String op, String value) {
        return switch (op) {
            case "eq" -> new TemporalFilter.Eq(field, value);
            case "ne" -> new TemporalFilter.Ne(field, value);
            case "in" -> new TemporalFilter.In(field, csv(value));
            case "gt" -> new TemporalFilter.Gt(field, value);
            case "gte" -> new TemporalFilter.Gte(field, value);
            case "lt" -> new TemporalFilter.Lt(field, value);
            case "lte" -> new TemporalFilter.Lte(field, value);
            case "between" -> {
                List<String> bounds = csv(value);
                if (bounds.size() != 2) {
                    throw invalid("between requires exactly two values: " + field);
                }
                yield new TemporalFilter.Between(field, bounds.get(0), bounds.get(1));
            }
            case "like" -> new TemporalFilter.Like(field, value);
            default -> throw invalid("unknown filter operator: " + op);
        };
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("filter value is required");
        }
        List<String> values = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        if (values.isEmpty()) {
            throw invalid("filter value is required");
        }
        return values;
    }

    private static void rejectInjection(String name, String value) {
        if (value == null) {
            return;
        }
        for (String marker : INJECTION_MARKERS) {
            if (value.contains(marker)) {
                throw invalid("illegal characters in filter " + name);
            }
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw invalid("illegal characters in filter " + name);
            }
        }
    }

    private static int intParam(Map<String, String> params, String name, int defaultValue) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw invalid("invalid " + name + ": " + raw);
        }
    }

    private static SortDirection direction(String raw) {
        if (raw == null || raw.isBlank()) {
            return SortDirection.ASC;
        }
        try {
            return SortDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid("invalid direction: " + raw);
        }
    }

    private static Instant instant(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException error) {
            throw invalid("invalid " + name);
        }
    }

    private static ContractException invalid(String message) {
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
    }
}
