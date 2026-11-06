package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 双时间轴列表查询：等值过滤、时点、排序与分页。
 *
 * <p>过滤/排序字段的可否由对象 schema 决定，未知字段在存储实现里统一映射为无效载荷，
 * 不拼接进 SQL。{@code minVersion} 只适用于单对象读取，列表查询传非空即拒绝。</p>
 *
 * @param objectType   对象类型
 * @param equals       等值过滤（字段 → 原始值）
 * @param asOf         业务时点
 * @param recordedAsOf 系统时点
 * @param minVersion   必须为空（列表不适用）
 * @param sortField    排序字段；空表示按 id 升序
 * @param direction    排序方向；空表示升序
 * @param page         分页
 * @author ByteForce
 * @since 1.0.0
 */
public record TemporalSearch(String objectType, Map<String, Object> equals, Instant asOf,
                             Instant recordedAsOf, Long minVersion, String sortField,
                             SortDirection direction, PageRequest page) {

    public TemporalSearch {
        if (objectType == null || objectType.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "object type is required");
        }
        equals = equals == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(equals));
        sortField = sortField == null || sortField.isBlank() ? null : sortField;
        direction = direction == null ? SortDirection.ASC : direction;
        page = page == null ? PageRequest.defaults() : page;
        if (minVersion != null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "min_version is not supported for list queries");
        }
    }

    /**
     * 从当前时点开始的第一页。
     *
     * @param objectType 对象类型
     * @return 查询
     */
    public static TemporalSearch current(String objectType) {
        return new TemporalSearch(objectType, Map.of(), null, null, null, null, SortDirection.ASC,
                PageRequest.defaults());
    }
}
