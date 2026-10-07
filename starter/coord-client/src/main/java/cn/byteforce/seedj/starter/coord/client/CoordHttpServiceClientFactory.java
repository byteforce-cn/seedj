package cn.byteforce.seedj.starter.coord.client;

import org.springframework.web.client.RestClient;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.function.UnaryOperator;

/**
 * 声明式 HTTP 客户端工厂（RestClient 形态，Phase 3）：基于框架内置
 * {@code HttpServiceProxyFactory}，不自研声明式客户端替代品；接口使用
 * {@code @HttpExchange} / {@code @GetExchange} 等框架注解，URL host 为服务名
 * （{@code http://orders-service/...}）。
 *
 * <p>客户端弹性（超时 / 重试 / 降级）不在本工厂重造：通过
 * {@code customizer} 由应用侧组合（如 Resilience4j 装饰）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordHttpServiceClientFactory {

    private final CoordLoadBalancer loadBalancer;
    private final CoordCircuitBreakerReporter reporterOrNull;

    /**
     * @param loadBalancer 负载均衡
     * @param reporter     熔断状态观察桥（未启用时为 null）
     */
    public CoordHttpServiceClientFactory(CoordLoadBalancer loadBalancer, CoordCircuitBreakerReporter reporter) {
        this.loadBalancer = loadBalancer;
        this.reporterOrNull = reporter;
    }

    /**
     * 创建声明式接口代理（默认 RestClient）。
     *
     * @param clientInterface {@code @HttpExchange} 接口类型
     * @param <T>             接口泛型
     * @return 代理实例
     */
    public <T> T restClient(Class<T> clientInterface) {
        return restClient(clientInterface, UnaryOperator.identity());
    }

    /**
     * 创建声明式接口代理（可定制 RestClient.Builder）。
     *
     * @param clientInterface {@code @HttpExchange} 接口类型
     * @param customizer      构建定制（超时、消息转换器、Resilience4j 装饰等）
     * @param <T>             接口泛型
     * @return 代理实例
     */
    public <T> T restClient(Class<T> clientInterface, UnaryOperator<RestClient.Builder> customizer) {
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new CoordLoadBalancingClientHttpRequestInterceptor(loadBalancer, reporterOrNull));
        builder = customizer.apply(builder);
        RestClient restClient = builder.build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(org.springframework.web.client.support.RestClientAdapter.create(restClient))
                .build();
        return factory.createClient(clientInterface);
    }
}
