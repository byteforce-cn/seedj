package cn.byteforce.seedj.cqrs.wiring;

import cn.byteforce.seedj.biz.autoconfigure.BizEnabledMarker;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.web.CommandController;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import cn.byteforce.seedj.domain.jdbc.TemporalTables;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.domain.web.ObjectQueryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 命令运行时与 HTTP 面的默认装配、provider 选择与失败快。
 */
class TemporalCommandAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TemporalCommandAutoConfiguration.class))
            .withBean(BizEnabledMarker.class);

    @Test
    @DisplayName("无 DataSource 时默认内存存储，并提供运行时与控制器")
    void memoryDefaultsProvideRuntimeCatalogAndControllers() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TemporalCommandAutoConfiguration.class))
                .withBean(BizEnabledMarker.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CommandRuntime.class);
                    assertThat(context).hasSingleBean(CommandCatalog.class);
                    assertThat(context.getBean(CommandStore.class))
                            .isInstanceOf(InMemoryCommandStore.class);
                    assertThat(context.getBean(TemporalStore.class))
                            .isInstanceOf(InMemoryTemporalStore.class);
                    assertThat(context).hasSingleBean(CommandController.class);
                    assertThat(context).hasSingleBean(ObjectQueryController.class);
                });
    }

    @Test
    @DisplayName("seedj.command.enabled=false 时关闭运行时与控制器")
    void commandDisabledSwitchBacksOffRuntimeAndControllers() {
        runner.withPropertyValues("seedj.command.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CommandRuntime.class);
            assertThat(context).doesNotHaveBean(CommandCatalog.class);
            assertThat(context).doesNotHaveBean(CommandStore.class);
        });
    }

    @Test
    @DisplayName("provider=postgres 缺 DataSource 时启动失败，不静默降级")
    void postgresProviderWithoutDataSourceFailsFast() {
        runner.withPropertyValues("seedj.command.provider=postgres").run(context ->
                assertThat(context.getStartupFailure())
                        .hasMessageContaining("requires a DataSource"));
    }

    @Test
    @DisplayName("对象 provider=postgres 缺表注册时启动失败")
    void objectPostgresProviderWithoutTablesFailsFast() {
        runner.withPropertyValues("seedj.object.provider=postgres")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("TemporalTables"));
    }

    @Test
    @DisplayName("TemporalTable bean 自动汇总为 TemporalTables")
    void collectsTemporalTables() {
        TemporalTable table = new TemporalTable("probe", "probe_object",
                new LinkedHashMap<>(Map.of("name", "TEXT")));

        runner.withBean(TemporalTable.class, () -> table).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TemporalTables.class);
            assertThat(context.getBean(TemporalTables.class).require("probe")).isEqualTo(table);
        });
    }

    @Test
    @DisplayName("命令代理 bean 自动进入 CommandCatalog")
    void collectsAgentBeansIntoCatalog() {
        runner.withUserConfiguration(AgentConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandCatalog.class);
            assertThat(context.getBean(CommandCatalog.class).types()).containsExactly("probe.update");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AgentConfiguration {

        @Bean
        ProbeAgent probeAgent(CommandRuntime runtime) {
            return new ProbeAgent(runtime);
        }
    }

    static final class ProbeAgent extends TemporalCommandAgent<ProbeCommand> {

        ProbeAgent(CommandRuntime runtime) {
            super(runtime, "probe.update", "probe", ProbeCommand.class);
        }

        @Override
        protected UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(ProbeCommand command, Optional<cn.byteforce.seedj.domain.TemporalRow> current,
                                       Instant now, UUID actor) {
            throw new UnsupportedOperationException();
        }
    }

    record ProbeCommand(UUID target) {
    }
}
