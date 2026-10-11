package cn.byteforce.seedj.starter.eis.audit;

import cn.byteforce.seedj.starter.eis.audit.web.EisAuditAdminController;
import cn.byteforce.seedj.starter.eis.core.EisClock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.DispatcherServlet;

import java.nio.file.Path;

/**
 * EIS 审计自动配置：哈希链（{@code EisAuditAppender} 权威实现）与锚定。
 *
 * <p>前置：{@code JdbcTemplate} 与 {@link EisClock}（由 {@code starter-eis-core}
 * 提供）；表结构由部署侧执行 {@code META-INF/eis/ddl/eis-audit.ddl} 材料化。
 * 整体开关：{@code byteforce.eis.audit.enabled=false}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "byteforce.eis.audit", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EisAuditProperties.class)
public class EisAuditAutoConfiguration {

    /**
     * 锚定实现：配置了 {@code anchor-file} 时为文件锚定，否则日志锚定。
     *
     * @param properties 审计属性
     * @return 锚定实现
     */
    @Bean
    @ConditionalOnMissingBean
    public EisAuditAnchor eisAuditAnchor(EisAuditProperties properties) {
        String file = properties.getAnchorFile();
        if (file == null || file.isBlank()) {
            return new LoggingEisAuditAnchor();
        }
        return new FileEisAuditAnchor(Path.of(file));
    }

    /**
     * 审计哈希链（命令运行时依赖的 {@code EisAuditAppender}）。
     *
     * @param jdbcTemplate JDBC 模板
     * @param clock        EIS 时钟
     * @param anchor       锚定实现
     * @return 审计链
     */
    @Bean
    @ConditionalOnMissingBean
    public EisAuditChain eisAuditChain(JdbcTemplate jdbcTemplate, EisClock clock, EisAuditAnchor anchor) {
        return new EisAuditChain(jdbcTemplate, clock, anchor);
    }

    /**
     * 管理端点（可选）：链验证 / 链头 / 立即锚定。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(DispatcherServlet.class)
    static class WebConfiguration {

        /**
         * 审计管理端点。
         *
         * @param chain 审计链
         * @return 管理端点
         */
        @Bean
        @ConditionalOnMissingBean
        EisAuditAdminController eisAuditAdminController(EisAuditChain chain) {
            return new EisAuditAdminController(chain);
        }
    }
}
