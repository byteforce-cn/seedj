package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CoordServiceUris} 服务名口径与 URI 改写测试（C6）。
 */
class CoordServiceUrisTest {

    private static CoordServiceInstance instance(String service, String metadata) {
        return new CoordServiceInstance(service, "i-1", metadata);
    }

    @Test
    void singleLabelHostIsTreatedAsServiceReference() {
        assertThat(CoordServiceUris.isServiceReference("orders-service")).isTrue();
        assertThat(CoordServiceUris.isServiceReference("orders")).isTrue();
    }

    @Test
    void realHostsAndLocalhostArePassedThrough() {
        assertThat(CoordServiceUris.isServiceReference("example.com")).isFalse();
        assertThat(CoordServiceUris.isServiceReference("10.0.0.1")).isFalse();
        assertThat(CoordServiceUris.isServiceReference("localhost")).isFalse();
        assertThat(CoordServiceUris.isServiceReference("")).isFalse();
        assertThat(CoordServiceUris.isServiceReference(null)).isFalse();
    }

    @Test
    void rewriteUsesInstanceAddressAndKeepsPathAndQuery() {
        URI original = URI.create("http://orders-service/api/orders?page=2");
        CoordServiceInstance target = instance("orders-service",
                "{\"address\":\"10.0.0.5:8081\",\"protocol\":\"http\"}");

        URI rewritten = CoordServiceUris.rewrite(original, target);

        assertThat(rewritten.getScheme()).isEqualTo("http");
        assertThat(rewritten.getHost()).isEqualTo("10.0.0.5");
        assertThat(rewritten.getPort()).isEqualTo(8081);
        assertThat(rewritten.getPath()).isEqualTo("/api/orders");
        assertThat(rewritten.getQuery()).isEqualTo("page=2");
    }

    @Test
    void rewriteHonoursInstanceProtocol() {
        URI original = URI.create("http://orders-service/api");
        CoordServiceInstance target = instance("orders-service",
                "{\"address\":\"orders.internal:9443\",\"protocol\":\"https\"}");

        assertThat(CoordServiceUris.rewrite(original, target).getScheme()).isEqualTo("https");
    }

    @Test
    void rewriteFailsLoudWithoutAddress() {
        assertThatThrownBy(() -> CoordServiceUris.rewrite(URI.create("http://orders/api"),
                instance("orders", "{}")))
                .isInstanceOf(CoordNoAvailableInstanceException.class);
    }

    @Test
    void httpCapableFilterRequiresHostAndPort() {
        assertThat(CoordServiceUris.HTTP_CAPABLE.test(instance("orders", "{\"address\":\"h:1\"}"))).isTrue();
        assertThat(CoordServiceUris.HTTP_CAPABLE.test(instance("orders", "{}"))).isFalse();
    }
}
