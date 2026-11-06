package cn.byteforce.seedj.read;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次读访问的审计事件：记录范围摘要与命中的字段策略，不落具体行内容。
 *
 * @param id          事件 id
 * @param callerId    调用方 id
 * @param objectType  对象类型
 * @param operation   操作
 * @param filters     查询过滤条件
 * @param rowIds      命中行 id（find/history）
 * @param scopeDigest 行范围规范化摘要（SHA-256）
 * @param policyIds   命中的字段策略 id
 * @param at          时间
 * @author ByteForce
 * @since 1.0.0
 */
public record ReadAccessEvent(UUID id, String callerId, String objectType, Operation operation,
                              Map<String, Object> filters, List<UUID> rowIds,
                              String scopeDigest, Set<String> policyIds, Instant at) {

    /** 读操作类型。 */
    public enum Operation {

        /** 单对象读取。 */
        FIND,

        /** 列表查询。 */
        SEARCH,

        /** 历史读取。 */
        HISTORY
    }

    public ReadAccessEvent {
        id = Objects.requireNonNull(id, "id");
        objectType = Objects.requireNonNull(objectType, "objectType");
        operation = Objects.requireNonNull(operation, "operation");
        filters = Collections.unmodifiableMap(new LinkedHashMap<>(
                filters == null ? Map.of() : filters));
        rowIds = rowIds == null ? List.of() : List.copyOf(rowIds);
        scopeDigest = Objects.requireNonNull(scopeDigest, "scopeDigest");
        Set<String> ids = new LinkedHashSet<>();
        if (policyIds != null) {
            ids.addAll(policyIds);
        }
        policyIds = Collections.unmodifiableSet(ids);
        at = Objects.requireNonNull(at, "at");
    }
}
