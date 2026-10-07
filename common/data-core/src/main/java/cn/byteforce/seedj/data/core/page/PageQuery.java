package cn.byteforce.seedj.data.core.page;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询参数（请求侧）。
 *
 * <p>缺省 {@code pageNum=1}、{@code pageSize=10}；转换与上限约束由
 * starter-data-mybatis 的 {@code PageUtils} / 分页插件（max-limit）保证。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 页码（从 1 开始）。
     */
    private long pageNum = 1;

    /**
     * 每页条数。
     */
    private long pageSize = 10;

    /**
     * 返回页码。
     *
     * @return 页码
     */
    public long getPageNum() {
        return pageNum;
    }

    /**
     * 设置页码（小于 1 时按 1 处理，由消费方归一化）。
     *
     * @param pageNum 页码
     */
    public void setPageNum(long pageNum) {
        this.pageNum = pageNum;
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
     * 设置每页条数（上限由分页插件 max-limit 约束）。
     *
     * @param pageSize 每页条数
     */
    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }
}
