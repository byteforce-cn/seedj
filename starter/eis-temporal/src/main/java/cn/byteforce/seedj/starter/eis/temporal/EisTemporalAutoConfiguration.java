package cn.byteforce.seedj.starter.eis.temporal;

import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * EIS 时点查询自动配置：`/objects` 与 `/time-travel` 端点（Servlet Web）。
 *
 * <p>前置：`EisObjectRepository` 与 `EisObjectTypeRegistry`（由 {@code starter-eis-core}
 * 提供，需要已注册的对象类型与已物化的对象表）。开关：
 * {@code byteforce.eis.temporal.enabled=false}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "byteforce.eis.temporal", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EisTemporalProperties.class)
public class EisTemporalAutoConfiguration {

    /**
     * 时点查询端点。
     *
     * @param objects     对象存储
     * @param objectTypes 对象类型注册表
     * @return 端点
     */
    @Bean
    @ConditionalOnMissingBean
    public EisTemporalController eisTemporalController(EisObjectRepository objects,
                                                       EisObjectTypeRegistry objectTypes) {
        return new EisTemporalController(objects, objectTypes);
    }

    /**
     * 端点级异常映射。
     *
     * @return 异常处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public EisTemporalExceptionHandler eisTemporalExceptionHandler() {
        return new EisTemporalExceptionHandler();
    }
}
