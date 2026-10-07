package cn.byteforce.seedj.starter.data.mybatis;

import cn.byteforce.seedj.core.context.RequestContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 {@link RequestContext} 的租户条件处理器。
 *
 * <p>语义（fail-closed）：</p>
 * <ul>
 *   <li>上下文存在租户 ID → SQL 追加 {@code tenant_col = ?}；</li>
 *   <li>上下文缺失租户 ID → 抛 {@link IllegalStateException} 拒绝执行，
 *       构造跨租户泄漏防线（后台任务需先写入租户上下文；
 *       确有跨租户需求时用 MyBatis-Plus {@code @InterceptorIgnore(tenantLine = "true")} 显式豁免）；</li>
 *   <li>{@code ignoreTables} 中的表不追加条件（大小写不敏感）。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class RequestContextTenantLineHandler implements TenantLineHandler {

    private final String tenantColumn;

    private final Set<String> normalizedIgnoreTables;

    /**
     * 构造处理器。
     *
     * @param tenantColumn 租户列名
     * @param ignoreTables 忽略表集合
     */
    public RequestContextTenantLineHandler(String tenantColumn, Set<String> ignoreTables) {
        this.tenantColumn = tenantColumn;
        this.normalizedIgnoreTables = ignoreTables.stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = RequestContext.tenantId().orElse(null);
        if (tenantId == null) {
            throw new IllegalStateException(
                    "多租户已启用但当前上下文缺少租户 ID（RequestContext.tenantId）；"
                            + "请检查身份采集链路，或使用 @InterceptorIgnore(tenantLine = \"true\") 显式豁免");
        }
        return new LongValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return tenantColumn;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return tableName != null
                && normalizedIgnoreTables.contains(tableName.toLowerCase(java.util.Locale.ROOT));
    }
}
