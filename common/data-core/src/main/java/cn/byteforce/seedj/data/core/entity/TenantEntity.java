package cn.byteforce.seedj.data.core.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

import java.io.Serial;

/**
 * 多租户实体基类：在 {@link BaseEntity} 上追加 {@code tenantId} 字段（插入时自动填充）。
 *
 * <p>启用条件：starter-data-mybatis 的租户插件（{@code byteforce.mybatis.tenant.enabled=true}）。
 * 填充值取 {@code RequestContext.tenantId()}；启用租户插件后，查询/更新 SQL 会由
 * {@code TenantLineInnerInterceptor} 自动追加租户条件（不匹配即拒绝，构造跨租户泄漏防线）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class TenantEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户 ID（插入时自动填充，取当前请求上下文）。
     */
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /**
     * 返回租户 ID。
     *
     * @return 租户 ID
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * 设置租户 ID。
     *
     * @param tenantId 租户 ID
     */
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
