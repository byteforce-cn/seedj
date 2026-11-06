package cn.byteforce.seedj.domain.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 对象时点查询 HTTP 面。
 *
 * <p>过滤完全交给存储层：{@code as_of} / {@code recorded_as_of} / {@code min_version}
 * 的边界、半开区间与 409 语义由 {@link TemporalStore} 保证，控制器只做参数解析。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public final class ObjectQueryController {

    private final TemporalStore store;

    private final ObjectSchemaRegistry schemas;

    private static final Set<String> RESERVED_PARAMETERS = Set.of(
            "page", "size", "sort", "direction", "as_of", "recorded_as_of");

    /**
     * 构造控制器。
     *
     * @param store 对象存储
     */
    public ObjectQueryController(TemporalStore store) {
        this(store, ObjectSchemaRegistry.empty());
    }

    /**
     * 构造带读投影剔除的控制器。
     *
     * @param store   对象存储
     * @param schemas 对象 schema 注册表；未知类型不剔除字段
     */
    public ObjectQueryController(TemporalStore store, ObjectSchemaRegistry schemas) {
        this.store = Objects.requireNonNull(store, "store");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
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
        TemporalRow row;
        try {
            row = store.find(objectType, id, new TemporalQuery(validAt, recordedAt, minVersion))
                    .orElseThrow(() -> new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                            "object not found"));
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    error.getMessage() == null ? "unknown object type" : error.getMessage());
        }
        return ObjectQueryResponse.from(redact(row));
    }

    /**
     * 列表查询：等值过滤 + 分页 + 排序 + 时点。
     *
     * <p>保留字之外的查询参数都是过滤条件；未知过滤/排序字段由存储层统一拒绝为 400，
     * 不会拼进 SQL。</p>
     *
     * @param objectType 对象类型
     * @param params     查询参数
     * @return 一页对象
     */
    @GetMapping("/objects/{objectType}")
    public ObjectPageResponse list(@PathVariable String objectType,
                                   @RequestParam Map<String, String> params) {
        Map<String, Object> filters = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (!RESERVED_PARAMETERS.contains(name)) {
                filters.put(name, value);
            }
        });
        PageRequest page = new PageRequest(intParam(params, "page", 1),
                intParam(params, "size", 20));
        SortDirection direction = direction(params.get("direction"));
        Page<TemporalRow> result;
        try {
            result = store.search(new TemporalSearch(objectType, filters,
                    parse(params.get("as_of"), "as_of"),
                    parse(params.get("recorded_as_of"), "recorded_as_of"), null,
                    params.get("sort"), direction, page));
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    error.getMessage() == null ? "unknown object type" : error.getMessage());
        }
        return ObjectPageResponse.from(result, this::redact);
    }

    private TemporalRow redact(TemporalRow row) {
        Set<String> writeOnly = schemas.find(row.objectType())
                .map(schema -> schema.writeOnlyFields())
                .orElse(Set.of());
        if (writeOnly.isEmpty()) {
            return row;
        }
        Map<String, Object> fields = new LinkedHashMap<>(row.fields());
        writeOnly.forEach(fields::remove);
        return new TemporalRow(row.objectType(), row.version(), fields);
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
