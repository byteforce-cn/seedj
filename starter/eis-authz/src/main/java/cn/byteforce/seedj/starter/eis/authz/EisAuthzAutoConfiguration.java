package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

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
     * @param store          授权数据存储
     * @param attributeSources 属性源（可空 = 无属性源，属性条件 fail-closed）
     * @return 引擎
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EisAuthzEngine.class)
    @ConditionalOnBean(EisAuthzStore.class)
    public EisAuthzEngine eisAuthzEngine(EisAuthzStore store,
                                         ObjectProvider<EisAuthzAttributeSource> attributeSources) {
        return new EisAuthzEngine(store, attributeSources.getIfAvailable());
    }

    /**
     * 远程授权客户端（配置了 base-url 时）。
     *
     * @param properties     授权属性
     * @param tokenProviders 服务间令牌提供者（可空 = 明文直连）
     * @return 客户端
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "byteforce.eis.authz", name = "base-url")
    public EisAuthzClient eisAuthzClient(EisAuthzProperties properties,
                                         ObjectProvider<EisAuthzTokenProvider> tokenProviders) {
        return new EisAuthzClient(RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory(properties)).build(),
                tokenProviders.getIfAvailable());
    }

    /**
     * OAuth2 client_credentials 令牌提供者（配置了任一服务凭据属性时；
     * 配置不完整在建 Bean 时 fail-fast）。
     *
     * @param properties 授权属性
     * @return 令牌提供者
     */
    @Bean
    @ConditionalOnMissingBean(EisAuthzTokenProvider.class)
    @Conditional(EisAuthzCredentialsConfiguredCondition.class)
    public EisAuthzClientCredentialsTokenProvider eisAuthzClientCredentialsTokenProvider(
            EisAuthzProperties properties) {
        return new EisAuthzClientCredentialsTokenProvider(
                RestClient.builder().requestFactory(requestFactory(properties)).build(),
                properties.getTokenUri(), properties.getClientId(), properties.getClientSecret(),
                properties.getScope(), properties.getTokenRefreshSkew());
    }

    /**
     * 有界超时的 HTTP 请求工厂（两个 RestClient 共用装配口径）：
     * connect 走 JDK HttpClient，read 走请求级超时。缺省即生效，避免「不配置 = 无限等待」。
     *
     * @param properties 授权属性（{@code connect-timeout} / {@code read-timeout}）
     * @return 请求工厂
     */
    static ClientHttpRequestFactory requestFactory(EisAuthzProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
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
     * 服务凭据配置探测：{@code token-uri} / {@code client-id} / {@code client-secret}
     * 任一非空即匹配（随后由令牌提供者构造期校验完整性，避免部分配置静默退化）。
     */
    static class EisAuthzCredentialsConfiguredCondition extends SpringBootCondition {

        private static final List<String> KEYS = List.of("token-uri", "client-id", "client-secret");

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            List<String> present = new ArrayList<>();
            for (String key : KEYS) {
                String value = context.getEnvironment().getProperty("byteforce.eis.authz." + key);
                if (StringUtils.hasText(value)) {
                    present.add(key);
                }
            }
            if (present.isEmpty()) {
                return ConditionOutcome.noMatch("未配置服务凭据（byteforce.eis.authz.client-id 等）");
            }
            return ConditionOutcome.match("已配置服务凭据属性: " + present);
        }
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
