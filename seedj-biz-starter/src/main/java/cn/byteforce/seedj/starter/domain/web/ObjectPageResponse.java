package cn.byteforce.seedj.starter.domain.web;

import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.TemporalRow;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 对象列表响应（线格式使用 snake_case）。
 *
 * @param items 当前页对象
 * @param page 页码（1 起）
 * @param size 每页条数
 * @param total 过滤后的总数
 * @param nextCursor 下一页游标；没有下一页时为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ObjectPageResponse(
        List<ObjectQueryResponse> items, int page, int size, long total, String nextCursor) {

    public ObjectPageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /**
     * 兼容旧响应：没有游标。
     *
     * @param items 当前页对象
     * @param page 页码
     * @param size 每页条数
     * @param total 总数
     */
    public ObjectPageResponse(List<ObjectQueryResponse> items, int page, int size, long total) {
        this(items, page, size, total, null);
    }

    /**
     * 从存储页映射。
     *
     * @param page 存储页
     * @param redact 行脱敏函数
     * @return HTTP 响应
     */
    public static ObjectPageResponse from(
            Page<TemporalRow> page, java.util.function.UnaryOperator<TemporalRow> redact) {
        return new ObjectPageResponse(
                page.items().stream().map(redact).map(ObjectQueryResponse::from).toList(),
                page.page(),
                page.size(),
                page.total(),
                page.nextCursor() == null ? null : page.nextCursor().encode());
    }
}
