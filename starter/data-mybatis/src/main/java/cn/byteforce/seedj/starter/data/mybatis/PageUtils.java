package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.data.core.page.PageQuery;
import cn.byteforce.seedj.data.core.page.PageResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页模型与 MyBatis-Plus 的转换工具。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PageUtils {

    private PageUtils() {
    }

    /**
     * 把请求侧 {@link PageQuery} 转为 MyBatis-Plus 分页对象（页码最小 1、每页最小 1）。
     *
     * @param query 分页参数
     * @param <T>   记录类型
     * @return MyBatis-Plus 分页对象
     */
    public static <T> Page<T> toPage(PageQuery query) {
        long pageNum = Math.max(query.getPageNum(), 1);
        long pageSize = Math.max(query.getPageSize(), 1);
        return new Page<>(pageNum, pageSize);
    }

    /**
     * 把 MyBatis-Plus 分页结果转为 {@link PageResult}。
     *
     * @param page MyBatis-Plus 分页结果
     * @param <T>  记录类型
     * @return 统一分页结果
     */
    public static <T> PageResult<T> toResult(IPage<T> page) {
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
