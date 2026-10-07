package cn.byteforce.seedj.starter.data.mybatis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MyBatis-Plus 基线配置属性。
 *
 * <p>前缀：{@code byteforce.mybatis}（与 CodeEnum 注册配置 {@code byteforce.mybatis.code-enum} 同源）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.mybatis")
public class MybatisBaselineProperties {

    /**
     * 是否启用 MyBatis-Plus 基线（审计填充、分页、租户），默认 {@code true}。
     */
    private boolean enabled = true;

    /**
     * 审计字段自动填充。
     */
    private final Audit audit = new Audit();

    /**
     * 分页插件。
     */
    private final Pagination pagination = new Pagination();

    /**
     * 租户插件（默认关闭，显式开启）。
     */
    private final Tenant tenant = new Tenant();

    /**
     * 返回是否启用基线。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用基线。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回审计配置。
     *
     * @return 审计配置
     */
    public Audit getAudit() {
        return audit;
    }

    /**
     * 返回分页配置。
     *
     * @return 分页配置
     */
    public Pagination getPagination() {
        return pagination;
    }

    /**
     * 返回租户配置。
     *
     * @return 租户配置
     */
    public Tenant getTenant() {
        return tenant;
    }

    /**
     * 审计填充配置。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    public static class Audit {

        /**
         * 是否启用审计字段填充，默认 {@code true}。
         */
        private boolean enabled = true;

        /**
         * 是否从 {@code RequestContext} 读取操作人（用户名），默认 {@code true}。
         */
        private boolean useRequestContext = true;

        /**
         * 返回是否启用审计填充。
         *
         * @return 是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用审计填充。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回是否使用请求上下文填充操作人。
         *
         * @return 是否使用
         */
        public boolean isUseRequestContext() {
            return useRequestContext;
        }

        /**
         * 设置是否使用请求上下文填充操作人。
         *
         * @param useRequestContext 是否使用
         */
        public void setUseRequestContext(boolean useRequestContext) {
            this.useRequestContext = useRequestContext;
        }
    }

    /**
     * 分页插件配置。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    public static class Pagination {

        /**
         * 是否启用分页插件，默认 {@code true}。
         */
        private boolean enabled = true;

        /**
         * 单页最大条数（超出按上限截断，防大页攻击），默认 {@code 500}。
         */
        private Long maxLimit = 500L;

        /**
         * 返回是否启用分页插件。
         *
         * @return 是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用分页插件。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回单页最大条数。
         *
         * @return 最大条数
         */
        public Long getMaxLimit() {
            return maxLimit;
        }

        /**
         * 设置单页最大条数。
         *
         * @param maxLimit 最大条数
         */
        public void setMaxLimit(Long maxLimit) {
            this.maxLimit = maxLimit;
        }
    }

    /**
     * 租户插件配置。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    public static class Tenant {

        /**
         * 是否启用租户插件，默认 {@code false}。
         */
        private boolean enabled = false;

        /**
         * 租户列名，默认 {@code tenant_id}。
         */
        private String column = "tenant_id";

        /**
         * 忽略租户条件的表名（大小写不敏感），如全局字典表。
         */
        private Set<String> ignoreTables = new LinkedHashSet<>();

        /**
         * 返回是否启用租户插件。
         *
         * @return 是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用租户插件。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 返回租户列名。
         *
         * @return 列名
         */
        public String getColumn() {
            return column;
        }

        /**
         * 设置租户列名。
         *
         * @param column 列名
         */
        public void setColumn(String column) {
            this.column = column;
        }

        /**
         * 返回忽略表集合。
         *
         * @return 忽略表
         */
        public Set<String> getIgnoreTables() {
            return ignoreTables;
        }

        /**
         * 设置忽略表集合。
         *
         * @param ignoreTables 忽略表
         */
        public void setIgnoreTables(Set<String> ignoreTables) {
            this.ignoreTables = ignoreTables;
        }
    }
}
