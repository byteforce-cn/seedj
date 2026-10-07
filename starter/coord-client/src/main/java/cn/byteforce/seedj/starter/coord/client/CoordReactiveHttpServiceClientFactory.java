package cn.byteforce.seedj.starter.coord.client;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.function.UnaryOperator;

/**
 * 声明式 HTTP 客户端工厂（WebClient 形态，Phase 3；仅当 classpath 存在 webflux 时注册）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordReactiveHttpServiceClientFactory {

    private final CoordLoadBalancer loadBalancer;
    private final CoordCircuitBreakerReporter reporterOrNull;

    /**
     * @param loadBalancer 负载均衡
     * @param reporter     熔断状态观察桥（未启用时为 null）
     */
    public CoordReactiveHttpServiceClientFactory(CoordLoadBalancer loadBalancer,
                                                 CoordCircuitBreakerReporter reporter) {
        this.loadBalancer = loadBalancer;
        this.reporterOrNull = reporter;
    }

    /**
     * 创建声明式接口代理（默认 WebClient）。
     *
     * @param clientInterface {@code @HttpExchange} 接口类型
     * @param <T>             接口泛型
     * @return 代理实例
     */
    public <T> T webClient(Class<T> clientInterface) {
        return webClient(clientInterface, UnaryOperator.identity());
    }

    /**
     * 创建声明式接口代理（可定制 WebClient.Builder）。
     *
     * @param clientInterface {@code @HttpExchange} 接口类型
     * @param customizer      构建定制
     * @param <T>             接口泛型
     * @return 代理实例
     */
    public <T> T webClient(Class<T> clientInterface, UnaryOperator<WebClient.Builder> customizer) {
        WebClient.Builder builder = WebClient.builder()
                .filter(new CoordLoadBalancingExchangeFilterFunction(loadBalancer, reporterOrNull));
        builder = customizer.apply(builder);
        WebClient webClient = builder.build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient))
                .build();
        return factory.createClient(clientInterface);
    }
}
