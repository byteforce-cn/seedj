package cn.byteforce.seedj.data.core.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;

import java.io.Serial;

/**
 * 逻辑删除实体基类：在 {@link BaseEntity} 上追加 {@code deleted} 字段。
 *
 * <p>取值约定沿用 MyBatis-Plus 全局默认：{@code 0} 未删除、{@code 1} 已删除；
 * 删除与查询由逻辑删除插件自动改写 SQL，业务代码调用 {@code removeById} 等即生效。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class LogicDeletedEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 逻辑删除标记：{@code 0} 未删除、{@code 1} 已删除。
     */
    @TableLogic
    private Integer deleted;

    /**
     * 返回逻辑删除标记。
     *
     * @return 删除标记
     */
    public Integer getDeleted() {
        return deleted;
    }

    /**
     * 设置逻辑删除标记。
     *
     * @param deleted 删除标记
     */
    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
