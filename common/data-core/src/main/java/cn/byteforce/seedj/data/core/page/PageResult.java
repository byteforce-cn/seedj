package cn.byteforce.seedj.data.core.page;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果（响应侧）：记录集 + 总数 + 页码信息 + 总页数。
 *
 * @param <T> 记录类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前页记录。
     */
    private final List<T> records;

    /**
     * 记录总数。
     */
    private final long total;

    /**
     * 当前页码（从 1 开始）。
     */
    private final long pageNum;

    /**
     * 每页条数。
     */
    private final long pageSize;

    /**
     * 总页数。
     */
    private final long pages;

    private PageResult(List<T> records, long total, long pageNum, long pageSize, long pages) {
        // 不可变包装（外部已由 of() 做 List.copyOf 快照；此处保证字段对静态分析可见地不可变）
        this.records = Collections.unmodifiableList(records);
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = pages;
    }

    /**
     * 构造分页结果（自动计算总页数）。
     *
     * @param records  当前页记录；为 {@code null} 时按空列表处理
     * @param total    记录总数（负数按 0 处理）
     * @param pageNum  当前页码
     * @param pageSize 每页条数（非正数时总页数为 0）
     * @param <T>      记录类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        List<T> safeRecords = (records != null) ? List.copyOf(records) : List.of();
        long safeTotal = Math.max(total, 0);
        long pages = (pageSize > 0) ? (safeTotal + pageSize - 1) / pageSize : 0;
        return new PageResult<>(safeRecords, safeTotal, pageNum, pageSize, pages);
    }

    /**
     * 构造空分页结果。
     *
     * @param pageNum  当前页码
     * @param pageSize 每页条数
     * @param <T>      记录类型
     * @return 空分页结果
     */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return of(List.of(), 0, pageNum, pageSize);
    }

    /**
     * 返回当前页记录。
     *
     * @return 当前页记录（不可变）
     */
    public List<T> getRecords() {
        return records;
    }

    /**
     * 返回记录总数。
     *
     * @return 记录总数
     */
    public long getTotal() {
        return total;
    }

    /**
     * 返回当前页码。
     *
     * @return 当前页码
     */
    public long getPageNum() {
        return pageNum;
    }

    /**
     * 返回每页条数。
     *
     * @return 每页条数
     */
    public long getPageSize() {
        return pageSize;
    }

    /**
     * 返回总页数。
     *
     * @return 总页数
     */
    public long getPages() {
        return pages;
    }
}
