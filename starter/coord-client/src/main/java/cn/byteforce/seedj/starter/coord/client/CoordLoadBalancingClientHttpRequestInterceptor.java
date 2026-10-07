package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;
import java.net.URI;

/**
 * RestClient 负载均衡拦截器（C6）：host 为服务名时经 {@link CoordLoadBalancer} 选实例并改写 URI；
 * 可选上报调用成败至 coord 熔断观察桥（transport 级语义）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordLoadBalancingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private final CoordLoadBalancer loadBalancer;
    private final CoordCircuitBreakerReporter reporterOrNull;

    /**
     * @param loadBalancer 负载均衡
     * @param reporter     熔断状态观察桥（未启用时为 null，不做任何上报）
     */
    public CoordLoadBalancingClientHttpRequestInterceptor(CoordLoadBalancer loadBalancer,
                                                          CoordCircuitBreakerReporter reporter) {
        this.loadBalancer = loadBalancer;
        this.reporterOrNull = reporter;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        URI original = request.getURI();
        String serviceName = original.getHost();
        if (!CoordServiceUris.isServiceReference(serviceName)) {
            return execution.execute(request, body);
        }
        CoordServiceInstance instance = loadBalancer.select(serviceName, CoordServiceUris.HTTP_CAPABLE);
        URI rewritten = CoordServiceUris.rewrite(original, instance);
        HttpRequest wrapped = new HttpRequestWrapper(request) {
            @Override
            public URI getURI() {
                return rewritten;
            }
        };
        try {
            ClientHttpResponse response = execution.execute(wrapped, body);
            if (reporterOrNull != null) {
                reporterOrNull.reportSuccess(serviceName);
            }
            return response;
        } catch (IOException | RuntimeException e) {
            if (reporterOrNull != null) {
                reporterOrNull.reportFailure(serviceName);
            }
            throw e;
        }
    }
}
