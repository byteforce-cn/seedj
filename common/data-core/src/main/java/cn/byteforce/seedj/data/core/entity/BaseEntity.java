package cn.byteforce.seedj.data.core.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类：主键与审计字段（创建/更新人、创建/更新时间，自动填充）。
 *
 * <p>字段填充由 starter-data-mybatis 的审计填充器完成：</p>
 * <ul>
 *   <li>时间字段取填充时刻；</li>
 *   <li>操作人字段取 {@code RequestContext.username()}（未配置请求上下文或匿名时留空）。</li>
 * </ul>
 *
 * <p>如需逻辑删除 / 多租户能力，分别继承 {@link LogicDeletedEntity} / {@link TenantEntity}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（默认雪花 ID，由 MyBatis-Plus 全局配置决定生成策略）。
     */
    @TableId
    private Long id;

    /**
     * 创建时间（插入时自动填充）。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 创建人（插入时自动填充，取当前用户名）。
     */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /**
     * 更新时间（插入与更新时自动填充）。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 更新人（插入与更新时自动填充，取当前用户名）。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /**
     * 返回主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置主键。
     *
     * @param id 主键
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 返回创建时间。
     *
     * @return 创建时间
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置创建时间。
     *
     * @param createTime 创建时间
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * 返回创建人。
     *
     * @return 创建人
     */
    public String getCreateBy() {
        return createBy;
    }

    /**
     * 设置创建人。
     *
     * @param createBy 创建人
     */
    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    /**
     * 返回更新时间。
     *
     * @return 更新时间
     */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置更新时间。
     *
     * @param updateTime 更新时间
     */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * 返回更新人。
     *
     * @return 更新人
     */
    public String getUpdateBy() {
        return updateBy;
    }

    /**
     * 设置更新人。
     *
     * @param updateBy 更新人
     */
    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }
}
