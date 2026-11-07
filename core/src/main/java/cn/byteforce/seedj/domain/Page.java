package cn.byteforce.seedj.domain;

import java.util.List;
import java.util.Objects;

/**
 * 一页结果。
 *
 * @param items 当前页条目
 * @param total 过滤后的总条数
 * @param page 页码（1 起）
 * @param size 每页条数
 * @param nextCursor 下一页游标；没有下一页时为 {@code null}
 * @param <T> 条目类型
 * @author ByteForce
 * @since 1.0.0
 */
public record Page<T>(List<T> items, long total, int page, int size, PageCursor nextCursor) {

    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (total < 0) {
            throw new IllegalArgumentException("total must be >= 0");
        }
    }

    /**
     * 兼容旧分页结果：没有游标。
     *
     * @param items 当前页条目
     * @param total 过滤后的总条数
     * @param page 页码（1 起）
     * @param size 每页条数
     */
    public Page(List<T> items, long total, int page, int size) {
        this(items, total, page, size, null);
    }

    /**
     * 空页。
     *
     * @param request 分页请求
     * @param <T> 条目类型
     * @return 空页
     */
    public static <T> Page<T> empty(PageRequest request) {
        return new Page<>(List.of(), 0, request.page(), request.size());
    }
}
