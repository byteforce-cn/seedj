package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * WebClient 负载均衡过滤器（C6，reactive 形态）：与
 * {@link CoordLoadBalancingClientHttpRequestInterceptor} 同语义的 URI 改写与
 * 熔断观察上报。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordLoadBalancingExchangeFilterFunction implements ExchangeFilterFunction {

    private final CoordLoadBalancer loadBalancer;
    private final CoordCircuitBreakerReporter reporterOrNull;

    /**
     * @param loadBalancer 负载均衡
     * @param reporter     熔断状态观察桥（未启用时为 null）
     */
    public CoordLoadBalancingExchangeFilterFunction(CoordLoadBalancer loadBalancer,
                                                    CoordCircuitBreakerReporter reporter) {
        this.loadBalancer = loadBalancer;
        this.reporterOrNull = reporter;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        URI original = request.url();
        String serviceName = original.getHost();
        if (!CoordServiceUris.isServiceReference(serviceName)) {
            return next.exchange(request);
        }
        // 选择 / 改写失败不属于「对目标服务的调用成败」，不上报观察桥（与 RestClient 形态一致）
        CoordServiceInstance instance;
        URI rewritten;
        try {
            instance = loadBalancer.select(serviceName, CoordServiceUris.HTTP_CAPABLE);
            rewritten = CoordServiceUris.rewrite(original, instance);
        } catch (RuntimeException e) {
            return Mono.error(e);
        }
        ClientRequest rewrittenRequest = ClientRequest.from(request).url(rewritten).build();
        return next.exchange(rewrittenRequest)
                // transport 级：收到响应即成功（HTTP 状态语义由调用方处理，与 RestClient 形态一致）
                .doOnNext(response -> {
                    if (reporterOrNull != null) {
                        reporterOrNull.reportSuccess(serviceName);
                    }
                })
                .doOnError(error -> {
                    if (reporterOrNull != null) {
                        reporterOrNull.reportFailure(serviceName);
                    }
                });
    }
}
