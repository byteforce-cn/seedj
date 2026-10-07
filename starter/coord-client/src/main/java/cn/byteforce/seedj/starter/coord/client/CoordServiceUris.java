package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.function.Predicate;

/**
 * 服务名口径与 URI 改写（C6）：{@code http://<service-name>/...} 中 host 为服务名
 * （不含 {@code .}，非 {@code localhost}、非 IP）；命中不到实例即抛（不静默直连）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class CoordServiceUris {

    /** HTTP 可用实例：metadata 需含可用 host 与 port。 */
    static final Predicate<CoordServiceInstance> HTTP_CAPABLE =
            instance -> instance.getHost().isPresent() && instance.getPort() > 0;

    private CoordServiceUris() {
    }

    /**
     * host 是否按服务名解释（C6 约定）。
     *
     * @param host URI host
     * @return true 表示视为服务名（交由负载均衡解析）
     */
    static boolean isServiceReference(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return false;
        }
        // 含 '.'（域名 / IPv4）、含 ':'（IPv6，URI host 一般已去括号）——视为真实主机，直通
        return !host.contains(".") && !host.contains(":");
    }

    /**
     * 用实例的 metadata（protocol / address）改写 URI：
     * scheme 优先取实例 protocol（http / https），host / port 取实例地址，路径与查询保持原样。
     *
     * @param original 原始 URI（host = 服务名）
     * @param instance 选中实例
     * @return 改写后的 URI
     */
    static URI rewrite(URI original, CoordServiceInstance instance) {
        String host = instance.getHost()
                .orElseThrow(() -> new CoordNoAvailableInstanceException(instance.getServiceName()));
        int port = instance.getPort();
        if (port <= 0) {
            throw new CoordNoAvailableInstanceException(instance.getServiceName(), "实例 metadata 缺 port");
        }
        String scheme = instance.getProtocol();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            scheme = original.getScheme();
        }
        return UriComponentsBuilder.fromUri(original)
                .scheme(scheme)
                .host(host)
                .port(port)
                .build(true)
                .toUri();
    }
}
