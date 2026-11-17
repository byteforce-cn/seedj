package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** {@code seedj.im.*} 的默认值与显式绑定。 */
class ImPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("空属性时所有默认值命中")
    void emptyPropertiesYieldDefaults() {
        runner.run(
                context -> {
                    ImProperties properties = context.getBean(ImProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.push().provider()).isEqualTo("local");
                    assertThat(properties.tcp().isEnabled()).isTrue();
                    assertThat(properties.tcp().port()).isEqualTo(9090);
                    assertThat(properties.ws().isEnabled()).isTrue();
                    assertThat(properties.ws().port()).isEqualTo(9091);
                    assertThat(properties.ws().path()).isEqualTo("/ws");
                    assertThat(properties.heartbeatTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(properties.maxFrameLength()).isEqualTo(1_048_576);
                    assertThat(properties.maxSessionsPerUser()).isEqualTo(1000);
                    assertThat(properties.store()).isEqualTo(ImProperties.Store.MEMORY);
                    assertThat(properties.sessionOverflow())
                            .isEqualTo(ImProperties.SessionOverflow.REJECT);
                    assertThat(properties.message().maxBodyBytes()).isEqualTo(65_536);
                    assertThat(properties.message().retention()).isNull();
                    assertThat(properties.message().maxAttachmentBytes()).isEqualTo(104_857_600);
                    assertThat(properties.message().retentionSweep())
                            .isEqualTo(Duration.ofHours(1));
                    assertThat(properties.message().retentionBatch()).isEqualTo(500);
                    assertThat(properties.sync().pageSize()).isEqualTo(200);
                    assertThat(properties.sync().isReplayHistory()).isFalse();
                });
    }

    @Test
    @DisplayName("tcp.port=0 原样保留，供测试绑定随机端口")
    void bindsZeroTcpPort() {
        runner.withPropertyValues("seedj.im.tcp.port=0")
                .run(
                        context ->
                                assertThat(context.getBean(ImProperties.class).tcp().port())
                                        .isZero());
    }

    @Test
    @DisplayName("push.provider 可覆盖")
    void bindsPushProvider() {
        runner.withPropertyValues("seedj.im.push.provider=COORD", "seedj.im.push.node-id=im-1")
                .run(
                        context -> {
                            ImProperties.Push push = context.getBean(ImProperties.class).push();
                            assertThat(push.provider()).isEqualTo("coord");
                            assertThat(push.nodeId()).isEqualTo("im-1");
                        });
    }

    @Test
    @DisplayName("retention 与附件上限可覆盖")
    void bindsRetentionAndAttachmentLimits() {
        runner.withPropertyValues(
                        "seedj.im.message.retention=PT720H",
                        "seedj.im.message.retention-sweep=PT5M",
                        "seedj.im.message.retention-batch=10",
                        "seedj.im.message.max-attachment-bytes=1024")
                .run(
                        context -> {
                            ImProperties.Message message =
                                    context.getBean(ImProperties.class).message();
                            assertThat(message.retention()).isEqualTo(Duration.ofDays(30));
                            assertThat(message.retentionSweep()).isEqualTo(Duration.ofMinutes(5));
                            assertThat(message.retentionBatch()).isEqualTo(10);
                            assertThat(message.maxAttachmentBytes()).isEqualTo(1024);
                        });
    }

    @Test
    @DisplayName("store 与溢出策略可覆盖")
    void bindsStoreAndSessionOverflow() {
        runner.withPropertyValues("seedj.im.store=jdbc", "seedj.im.session-overflow=evict-oldest")
                .run(
                        context -> {
                            ImProperties properties = context.getBean(ImProperties.class);
                            assertThat(properties.store()).isEqualTo(ImProperties.Store.JDBC);
                            assertThat(properties.sessionOverflow())
                                    .isEqualTo(ImProperties.SessionOverflow.EVICT_OLDEST);
                        });
    }

    @Test
    @DisplayName("零或负值被默认值兜底")
    void nonPositiveValuesFallBackToDefaults() {
        runner.withPropertyValues(
                        "seedj.im.message.max-body-bytes=0",
                        "seedj.im.sync.page-size=-1",
                        "seedj.im.max-sessions-per-user=0")
                .run(
                        context -> {
                            ImProperties properties = context.getBean(ImProperties.class);
                            assertThat(properties.message().maxBodyBytes()).isEqualTo(65_536);
                            assertThat(properties.sync().pageSize()).isEqualTo(200);
                            assertThat(properties.maxSessionsPerUser()).isEqualTo(1000);
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImProperties.class)
    static class PropertiesConfig {}
}
