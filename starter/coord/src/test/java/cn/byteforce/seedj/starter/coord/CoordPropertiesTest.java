package cn.byteforce.seedj.starter.coord;

import cn.byteforce.coord.sdk.CoordConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CoordProperties} 到 {@link CoordConfig} 的映射测试（含 fail-closed 校验面）。
 */
class CoordPropertiesTest {

    @Test
    void defaultsMirrorSdkDefaults() {
        CoordConfig config = new CoordProperties().toBuilder().build();

        assertThat(config.getAgentHost()).isEqualTo("127.0.0.1");
        assertThat(config.getAgentPort()).isEqualTo(19527);
        assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.isAutoRestoreWatches()).isTrue();
        assertThat(config.getHeartbeatThreads()).isEqualTo(4);
        assertThat(config.isUseTls()).isFalse();
        assertThat(config.getAuthTokenSupplier()).isNull();
    }

    @Test
    void mapsCustomValues() {
        CoordProperties properties = new CoordProperties();
        properties.setAgentHost("coord.internal");
        properties.setAgentPort(19528);
        properties.setRequestTimeout(Duration.ofSeconds(3));
        properties.setAutoRestoreWatches(false);
        properties.setHeartbeatThreads(2);
        properties.setAllowInsecurePlaintext(true);
        properties.getTls().setEnabled(true);
        properties.getTls().setCaCertPath("/etc/coord/ca.pem");
        properties.getTls().setClientCertPath("/etc/coord/client.pem");
        properties.getTls().setClientKeyPath("/etc/coord/client-key.pem");

        CoordConfig config = properties.toBuilder().build();

        assertThat(config.getAgentHost()).isEqualTo("coord.internal");
        assertThat(config.getAgentPort()).isEqualTo(19528);
        assertThat(config.getRequestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.isAutoRestoreWatches()).isFalse();
        assertThat(config.getHeartbeatThreads()).isEqualTo(2);
        assertThat(config.isUseTls()).isTrue();
        assertThat(config.getTlsCaCertPath()).isEqualTo("/etc/coord/ca.pem");
        assertThat(config.getTlsClientCertPath()).isEqualTo("/etc/coord/client.pem");
        assertThat(config.getTlsClientKeyPath()).isEqualTo("/etc/coord/client-key.pem");
    }

    @Test
    void tlsWithoutCaCertFailsClosed() {
        CoordProperties properties = new CoordProperties();
        properties.setAgentHost("coord.internal");
        properties.getTls().setEnabled(true);

        assertThatThrownBy(() -> properties.toBuilder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tlsCaCertPath");
    }

    @Test
    void plaintextToNonLoopbackFailsClosed() {
        CoordProperties properties = new CoordProperties();
        properties.setAgentHost("10.0.0.1");

        assertThatThrownBy(() -> properties.toBuilder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plaintext");
    }
}
