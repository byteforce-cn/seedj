package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * EIS 授权自动配置：本地判定引擎（权威）与远程缓存客户端。
 *
 * <p>装配矩阵：</p>
 * <ul>
 *   <li>存在 {@link EisAuthzStore} Bean（权威服务）：注册 {@link EisAuthzEngine}
 *       （{@code @Primary} 的 {@link EisAuthzOperations}）；</li>
 *   <li>配置 {@code byteforce.eis.authz.base-url}（消费方）：注册 {@link EisAuthzClient}，
 *       缓存启用时叠 {@link EisAuthzCachingOperations}（按具体类型注入）；</li>
 *   <li>Web 环境且存在操作面：注册 {@code /resolve} {@code /check} 端点。</li>
 * </ul>
 *
 * <p>判定唯一权威 = 本引擎；coord Policy 不接入判定路径（评估点 13）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "byteforce.eis.authz", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EisAuthzProperties.class)
public class EisAuthzAutoConfiguration {

    /**
     * 本地判定引擎（权威；存在存储 Bean 时装配）。
     *
     * @param store 授权数据存储
     * @return 引擎
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EisAuthzEngine.class)
    @ConditionalOnBean(EisAuthzStore.class)
    public EisAuthzEngine eisAuthzEngine(EisAuthzStore store) {
        return new EisAuthzEngine(store);
    }

    /**
     * 远程授权客户端（配置了 base-url 时）。
     *
     * @param properties 授权属性
     * @return 客户端
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "byteforce.eis.authz", name = "base-url")
    public EisAuthzClient eisAuthzClient(EisAuthzProperties properties) {
        return new EisAuthzClient(RestClient.builder().baseUrl(properties.getBaseUrl()).build());
    }

    /**
     * 进程内 TTL 缓存装饰（消费方按类型注入的 {@code @Primary} 候选；fail-open）。
     *
     * @param client     远程客户端
     * @param properties 授权属性
     * @return 缓存操作面
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    @ConditionalOnBean(EisAuthzClient.class)
    public EisAuthzCachingOperations eisAuthzCachingOperations(EisAuthzClient client,
                                                               EisAuthzProperties properties) {
        return new EisAuthzCachingOperations(client, properties.getCacheTtl(), properties.isCacheEnabled());
    }

    /**
     * Web 面：授权端点（需要操作面存在：权威=本地引擎，消费方=缓存客户端；
     * 两者皆无时该应用未配置本能力，启动即失败——fail-fast 口径与审计一致）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(DispatcherServlet.class)
    static class WebConfiguration {

        /**
         * 授权端点（操作面优先取 {@code @Primary} 的本地引擎）。
         *
         * @param operations 授权操作面
         * @return 端点
         */
        @Bean
        @ConditionalOnMissingBean
        EisAuthzController eisAuthzController(EisAuthzOperations operations) {
            return new EisAuthzController(operations);
        }

        /**
         * 端点级异常映射。
         *
         * @param controller 已装配的端点（保证映射与端点同生共死）
         * @return 异常处理器
         */
        @Bean
        @ConditionalOnMissingBean
        EisAuthzExceptionHandler eisAuthzExceptionHandler(EisAuthzController controller) {
            return new EisAuthzExceptionHandler();
        }
    }
}
