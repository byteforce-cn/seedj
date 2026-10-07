package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoordServiceInstance} metadata 解析行为测试（约定 C1）。
 */
class CoordServiceInstanceTest {

    @Test
    void parsesStandardMetadata() {
        CoordServiceInstance instance = new CoordServiceInstance("orders", "i-1",
                "{\"address\":\"10.0.0.1:8081\",\"protocol\":\"https\",\"weight\":50,\"version\":\"1.2.3\"}");

        assertThat(instance.getAddress()).contains("10.0.0.1:8081");
        assertThat(instance.getHost()).contains("10.0.0.1");
        assertThat(instance.getPort()).isEqualTo(8081);
        assertThat(instance.getProtocol()).isEqualTo("https");
        assertThat(instance.getField("version")).contains("1.2.3");
        assertThat(instance.getField("weight")).contains("50");
    }

    @Test
    void fallsBackOnInvalidJson() {
        CoordServiceInstance instance = new CoordServiceInstance("orders", "i-1", "not-json");

        assertThat(instance.getAddress()).isEmpty();
        assertThat(instance.getHost()).isEmpty();
        assertThat(instance.getPort()).isEqualTo(-1);
        assertThat(instance.getProtocol()).isEqualTo("http");
        assertThat(instance.getField("anything")).isEmpty();
    }

    @Test
    void handlesNullAndBlankMetadata() {
        assertThat(new CoordServiceInstance("s", "i", null).getPort()).isEqualTo(-1);
        assertThat(new CoordServiceInstance("s", "i", "  ").getAddress()).isEmpty();
    }

    @Test
    void addressWithoutPortYieldsHostOnlyAndUnknownPort() {
        CoordServiceInstance instance = new CoordServiceInstance("s", "i", "{\"address\":\"justhost\"}");

        assertThat(instance.getHost()).contains("justhost");
        assertThat(instance.getPort()).isEqualTo(-1);
    }

    @Test
    void addressWithIllegalPortYieldsUnknownPort() {
        CoordServiceInstance instance = new CoordServiceInstance("s", "i", "{\"address\":\"host:abc\"}");

        assertThat(instance.getPort()).isEqualTo(-1);
    }

    @Test
    void protocolBlankFallsBackToDefault() {
        CoordServiceInstance instance = new CoordServiceInstance("s", "i", "{\"protocol\":\"  \"}");

        assertThat(instance.getProtocol()).isEqualTo("http");
    }

    @Test
    void wrapsSdkInstance() {
        // SDK 构造器参数序：instanceId, serviceName, metadata
        ServiceInstance sdk = new ServiceInstance("i-9", "orders", "{\"address\":\"h:1\"}");

        CoordServiceInstance instance = CoordServiceInstance.of(sdk);

        assertThat(instance.getServiceName()).isEqualTo("orders");
        assertThat(instance.getInstanceId()).isEqualTo("i-9");
        assertThat(instance.getMetadata()).isEqualTo("{\"address\":\"h:1\"}");
    }

    @Test
    void equalsAndHashCodeByIdentityAndMetadata() {
        CoordServiceInstance a = new CoordServiceInstance("s", "i", "{}");
        CoordServiceInstance b = new CoordServiceInstance("s", "i", "{}");
        CoordServiceInstance c = new CoordServiceInstance("s", "j", "{}");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
