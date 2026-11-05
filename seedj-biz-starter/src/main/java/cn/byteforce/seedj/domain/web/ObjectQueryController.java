package cn.byteforce.seedj.domain.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.jdbc.TemporalTables;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    private final TemporalTables tables;

    /**
     * 构造控制器。
     *
     * @param store 对象存储
     */
    public ObjectQueryController(TemporalStore store) {
        this(store, null);
    }

    /**
     * 构造带读投影剔除的控制器。
     *
     * @param store  对象存储
     * @param tables 对象表注册表；可空
     */
    public ObjectQueryController(TemporalStore store, TemporalTables tables) {
        this.store = Objects.requireNonNull(store, "store");
        this.tables = tables;
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

    private TemporalRow redact(TemporalRow row) {
        if (tables == null) {
            return row;
        }
        Map<String, Object> fields = new LinkedHashMap<>(row.fields());
        tables.writeOnlyColumns(row.objectType()).forEach(fields::remove);
        return new TemporalRow(row.objectType(), row.version(), fields);
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
