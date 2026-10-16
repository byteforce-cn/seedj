package cn.byteforce.seedj.starter.eis.authz;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远程调用有界超时测试：端点挂起时必须在配置时长内 fail-closed
 * （{@link EisAuthzRemoteException}），不得无限占用消费方请求线程。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzTimeoutTest {

    /**
     * 端点的挂起时长：明显大于测试使用的读取超时（200ms），
     * 又能在「未设超时」的失败路径上快速返回，避免测试真的挂死。
     */
    private static final Duration HANG = Duration.ofMillis(1500);

    private static final Duration READ_TIMEOUT = Duration.ofMillis(200);

    private HttpServer server;

    private String baseUrl;

    @BeforeEach
    void startHangingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(HANG.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"allowed\":true,\"reason_code\":\"ALLOWED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void checkFailsWithTimeoutExceptionInsteadOfHanging() {
        EisAuthzProperties properties = new EisAuthzProperties();
        properties.setReadTimeout(READ_TIMEOUT);
        EisAuthzClient client = new EisAuthzClient(RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(EisAuthzAutoConfiguration.requestFactory(properties))
                .build());

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.check("p", "iam.user.create", Instant.now(), null))
                .isInstanceOf(EisAuthzRemoteException.class)
                .hasMessageContaining("超时");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(HANG);
    }

    @Test
    void remoteExceptionOnTimeoutCarriesGatewayTimeoutStatus() {
        EisAuthzProperties properties = new EisAuthzProperties();
        properties.setReadTimeout(READ_TIMEOUT);
        EisAuthzClient client = new EisAuthzClient(RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(EisAuthzAutoConfiguration.requestFactory(properties))
                .build());

        assertThatThrownBy(() -> client.check("p", "iam.user.create", Instant.now(), null))
                .isInstanceOf(EisAuthzRemoteException.class)
                .extracting(failure -> ((EisAuthzRemoteException) failure).getStatus())
                .isEqualTo(504);
    }

    @Test
    void tokenFetchFailsWithTimeoutExceptionInsteadOfHanging() {
        EisAuthzProperties properties = new EisAuthzProperties();
        properties.setReadTimeout(READ_TIMEOUT);
        EisAuthzClientCredentialsTokenProvider provider = new EisAuthzClientCredentialsTokenProvider(
                RestClient.builder().requestFactory(EisAuthzAutoConfiguration.requestFactory(properties)).build(),
                baseUrl + "/oauth2/token", "svc-hr", "s3cr3t", null, Duration.ZERO);

        assertThatThrownBy(provider::accessToken)
                .isInstanceOf(EisAuthzRemoteException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void connectionFailureFailsClosedAsRemoteException() {
        EisAuthzProperties properties = new EisAuthzProperties();
        EisAuthzClient client = new EisAuthzClient(RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestFactory(EisAuthzAutoConfiguration.requestFactory(properties))
                .build());

        assertThatThrownBy(() -> client.check("p", "iam.user.create", Instant.now(), null))
                .isInstanceOf(EisAuthzRemoteException.class)
                .hasMessageContaining("授权服务调用失败");
    }

    @Test
    void defaultTimeoutsAreBounded() {
        EisAuthzProperties properties = new EisAuthzProperties();

        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void autoConfigurationProvidesBoundedDefaultsWithoutAnyTimeoutKey() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisAuthzAutoConfiguration.class))
                .withPropertyValues("byteforce.eis.authz.base-url=" + baseUrl)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EisAuthzProperties properties = context.getBean(EisAuthzProperties.class);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void autoConfigurationAppliesConfiguredReadTimeout() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisAuthzAutoConfiguration.class))
                .withPropertyValues("byteforce.eis.authz.base-url=" + baseUrl,
                        "byteforce.eis.authz.read-timeout=200ms",
                        "byteforce.eis.authz.cache-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EisAuthzClient client = context.getBean(EisAuthzClient.class);
                    long start = System.nanoTime();
                    assertThatThrownBy(() -> client.check("p", "iam.user.create", Instant.now(), null))
                            .isInstanceOf(EisAuthzRemoteException.class)
                            .hasMessageContaining("超时");
                    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(HANG);
                });
    }

    @Test
    void autoConfigurationAppliesTimeoutsToTokenEndpoint() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisAuthzAutoConfiguration.class))
                .withPropertyValues("byteforce.eis.authz.base-url=" + baseUrl,
                        "byteforce.eis.authz.token-uri=" + baseUrl + "/oauth2/token",
                        "byteforce.eis.authz.client-id=svc-hr",
                        "byteforce.eis.authz.client-secret=s3cr3t",
                        "byteforce.eis.authz.read-timeout=200ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EisAuthzTokenProvider provider = context.getBean(EisAuthzTokenProvider.class);
                    assertThatThrownBy(provider::accessToken)
                            .isInstanceOf(EisAuthzRemoteException.class)
                            .hasMessageContaining("超时");
                });
    }
}
