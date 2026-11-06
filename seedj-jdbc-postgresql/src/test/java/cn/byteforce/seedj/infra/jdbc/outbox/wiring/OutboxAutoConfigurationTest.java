package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class));

    @Test
    @DisplayName("有 store 时提供 OutboxWriter / OutboxRelay / OutboxMetrics")
    void providesWriterAndRelay() {
        runner.withBean(OutboxStore.class, FakeOutboxStore::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxWriter.class);
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).hasSingleBean(OutboxMetrics.class);
                });
    }

    @Test
    @DisplayName("没有 store 时 writer 仍在，但 append 抛 STORE_UNAVAILABLE")
    void writerFailsLazilyWithoutStore() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboxWriter.class);
            assertThat(context).hasSingleBean(OutboxRelay.class);

            OutboxWriter writer = context.getBean(OutboxWriter.class);
            assertThatThrownBy(() -> writer.append(OutboxRecord.of("m-1", "dest", "k", "type",
                    "p".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(OutboxException.class)
                    .extracting(e -> ((OutboxException) e).getErrorCode())
                    .isEqualTo(OutboxErrorCode.STORE_UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("seedj.outbox.enabled=false 时不提供 bean")
    void disabled() {
        runner.withPropertyValues("seedj.outbox.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OutboxWriter.class);
                    assertThat(context).doesNotHaveBean(OutboxRelay.class);
                });
    }

    @Test
    @DisplayName("没有 sink 时 relay 空转，不影响启动")
    void relayIdlesWithoutSink() {
        runner.withBean(OutboxStore.class, FakeOutboxStore::new)
                .run(context -> assertThat(context).hasSingleBean(OutboxRelay.class));
    }
}
