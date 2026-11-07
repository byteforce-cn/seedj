package cn.byteforce.seedj.starter.domain.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageCursor;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalFilter;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.read.ReadGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 通用对象查询 HTTP 面（internal surface，默认关闭）。
 *
 * <p>所有读取都经 {@link ReadGateway}：对象权限、行范围、字段策略与审计不降级。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public final class ObjectQueryController {

    private static final Set<String> RESERVED_PARAMETERS = Set.of(
            "page", "size", "sort", "direction", "as_of", "recorded_as_of", "cursor");

    private static final List<String> INJECTION_MARKERS = List.of("'", "\"", ";", "--", "/*",
            "*/");

    private final ReadGateway gateway;

    private final CallerProvider callers;

    /**
     * 构造控制器。
     *
     * @param gateway 读网关
     * @param callers 当前调用方
     */
    public ObjectQueryController(ReadGateway gateway, CallerProvider callers) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    /**
     * 按 ID 查询对象。
     *
     * @param objectType   对象类型
     * @param id           对象 ID
     * @param asOf         业务时点（ISO-8601）
     * @param recordedAsOf 系统时点（ISO-8601）
     * @param minVersion   最小对象版本
     * @return 对象响应
     */
    @GetMapping("/objects/{objectType}/{id}")
    public ObjectQueryResponse find(
            @PathVariable String objectType,
            @PathVariable UUID id,
            @RequestParam(value = "as_of", required = false) String asOf,
            @RequestParam(value = "recorded_as_of", required = false) String recordedAsOf,
            @RequestParam(value = "min_version", required = false) Long minVersion) {
        Instant validAt = parse(asOf, "as_of");
        Instant recordedAt = parse(recordedAsOf, "recorded_as_of");
        if (minVersion != null && minVersion < 1) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "min_version must be >= 1");
        }
        TemporalRow row = gateway.find(objectType, id,
                        new TemporalQuery(validAt, recordedAt, minVersion), caller())
                .orElseThrow(() -> new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                        "object not found"));
        return ObjectQueryResponse.from(row);
    }

    /**
     * 列表查询：受限过滤器（{@code field__op=...}）+ keyset 分页 + 排序 + 时点。
     *
     * <p>普通 {@code field=value} 保持等值兼容；{@code field__in=v1,v2}、
     * {@code field__gte=...}、{@code field__between=a,b}、{@code field__like=...}
     * 等只在 schema 声明为 filterable 时生效。含引号/分号/注释片段的值直接 400。</p>
     *
     * @param objectType 对象类型
     * @param params     查询参数
     * @return 一页对象
     */
    @GetMapping("/objects/{objectType}")
    public ObjectPageResponse list(@PathVariable String objectType,
                                   @RequestParam Map<String, String> params) {
        Map<String, Object> equals = new LinkedHashMap<>();
        List<TemporalFilter> filters = new ArrayList<>();
        params.forEach((name, value) -> {
            if (RESERVED_PARAMETERS.contains(name)) {
                return;
            }
            rejectInjection(name, value);
            int separator = name.indexOf("__");
            if (separator < 0) {
                equals.put(name, value);
                return;
            }
            String field = name.substring(0, separator);
            String op = name.substring(separator + 2);
            filters.add(filter(field, op, value));
        });
        PageRequest page = new PageRequest(intParam(params, "page", 1),
                intParam(params, "size", 20));
        PageCursor cursor = params.containsKey("cursor")
                ? PageCursor.parse(params.get("cursor")) : null;
        String sort = params.get("sort");
        if ((sort == null || sort.isBlank()) && cursor != null) {
            sort = cursor.sortField();
        }
        Page<TemporalRow> result = gateway.search(objectType,
                new TemporalSearch(objectType, equals, filters,
                        parse(params.get("as_of"), "as_of"),
                        parse(params.get("recorded_as_of"), "recorded_as_of"), null,
                        sort, direction(params.get("direction")), page, cursor),
                caller());
        return ObjectPageResponse.from(result, row -> row);
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
                    throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                            "between requires exactly two values: " + field);
                }
                yield new TemporalFilter.Between(field, bounds.get(0), bounds.get(1));
            }
            case "like" -> new TemporalFilter.Like(field, value);
            default -> throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "unknown filter operator: " + op);
        };
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "filter value is required");
        }
        List<String> values = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        if (values.isEmpty()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "filter value is required");
        }
        return values;
    }

    private static void rejectInjection(String name, String value) {
        if (value == null) {
            return;
        }
        for (String marker : INJECTION_MARKERS) {
            if (value.contains(marker)) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        "illegal characters in filter " + name);
            }
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        "illegal characters in filter " + name);
            }
        }
    }

    private Caller caller() {
        return callers.find().orElseThrow(() -> new ContractException(
                ContractErrorCode.UNAUTHENTICATED, "caller is required"));
    }

    private static int intParam(Map<String, String> params, String name, int defaultValue) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "invalid " + name + ": " + raw);
        }
    }

    private static SortDirection direction(String raw) {
        if (raw == null || raw.isBlank()) {
            return SortDirection.ASC;
        }
        try {
            return SortDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "invalid direction: " + raw);
        }
    }

    private static Instant parse(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "invalid " + name);
        }
    }
}
