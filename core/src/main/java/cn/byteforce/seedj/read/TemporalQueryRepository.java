package cn.byteforce.seedj.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 单表类型化查询仓储：覆盖约 80% 的等值过滤 + 排序 + 分页场景。
 *
 * <p>复杂 join/聚合走 {@link QuerySpec}；两者都经过 {@link ReadGateway}，所以行范围、 字段策略与读审计的行为一致。业务代码不需要直接接触
 * {@code TemporalStore}。
 *
 * @param <T> 读模型类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class TemporalQueryRepository<T> {

    private final ReadGateway gateway;

    private final String objectType;

    private final String sortField;

    private final SortDirection direction;

    private final Function<Map<String, Object>, T> mapper;

    /**
     * 构造仓储。
     *
     * @param gateway 读网关
     * @param objectType 对象类型
     * @param sortField 默认排序字段
     * @param direction 默认排序方向
     * @param mapper 行字段 → 读模型
     */
    public TemporalQueryRepository(
            ReadGateway gateway,
            String objectType,
            String sortField,
            SortDirection direction,
            Function<Map<String, Object>, T> mapper) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectType = Objects.requireNonNull(objectType, "objectType");
        this.sortField = Objects.requireNonNull(sortField, "sortField");
        this.direction = direction == null ? SortDirection.ASC : direction;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 等值过滤分页查询。
     *
     * @param equals 等值过滤（字段 → 值）
     * @param request 分页；空表示默认分页
     * @param caller 调用方
     * @return 类型化页
     */
    public Page<T> find(Map<String, Object> equals, PageRequest request, Caller caller) {
        TemporalSearch search =
                new TemporalSearch(
                        objectType,
                        equals == null ? Map.of() : equals,
                        null,
                        null,
                        null,
                        sortField,
                        direction,
                        request == null ? PageRequest.defaults() : request);
        Page<TemporalRow> rows = gateway.search(objectType, search, caller);
        return new Page<>(
                rows.items().stream().map(row -> mapper.apply(row.fields())).toList(),
                rows.total(),
                rows.page(),
                rows.size());
    }

    /**
     * 第一页等值查询。
     *
     * @param equals 等值过滤
     * @param caller 调用方
     * @return 类型化页
     */
    public Page<T> find(Map<String, Object> equals, Caller caller) {
        return find(equals, PageRequest.defaults(), caller);
    }
}
