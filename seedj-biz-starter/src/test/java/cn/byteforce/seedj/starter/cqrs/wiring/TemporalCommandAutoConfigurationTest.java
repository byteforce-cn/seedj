package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.biz.autoconfigure.BizEnabledMarker;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.starter.cqrs.web.CommandController;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
            .withBean(BizEnabledMarker.class)
            .withPropertyValues("seedj.command.degraded-mode=true");

    @Test
    @DisplayName("无 DataSource 时默认内存存储，并提供运行时与控制器")
    void memoryDefaultsProvideRuntimeCatalogAndControllers() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TemporalCommandAutoConfiguration.class))
                .withBean(BizEnabledMarker.class)
                .withPropertyValues("seedj.command.degraded-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CommandRuntime.class);
                    assertThat(context).hasSingleBean(CommandCatalog.class);
                    assertThat(context.getBean(CommandStore.class))
                            .isInstanceOf(InMemoryCommandStore.class);
                    assertThat(context.getBean(TemporalStore.class))
                            .isInstanceOf(InMemoryTemporalStore.class);
                    assertThat(context).hasSingleBean(CommandController.class);
                    assertThat(context).doesNotHaveBean(
                            cn.byteforce.seedj.starter.domain.web.ObjectQueryController.class);
                });
    }

    @Test
    @DisplayName("缺事务/锁/outbox 默认启动失败，消息指向 degraded-mode")
    void missingDependenciesFailFastByDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TemporalCommandAutoConfiguration.class))
                .withBean(BizEnabledMarker.class)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("seedj.command.degraded-mode"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("degraded-mode=true 才允许降级，并打印 WARN 横幅")
    void degradedModeStartsAndWarns(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandRuntime.class);
        });
        assertThat(output).contains("command runtime running in degraded mode");
        assertThat(output).contains("tx=direct");
    }

    @Test
    @DisplayName("require-outbox=false：允许无 outbox，但仍要求锁与事务")
    void requireOutboxFalseAllowsMissingOutbox() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TemporalCommandAutoConfiguration.class))
                .withBean(BizEnabledMarker.class)
                .withBean(TransactionRunner.class, TransactionRunner::direct)
                .withBean(LockProvider.class, LockProvider::disabled)
                .withPropertyValues("seedj.command.require-outbox=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CommandRuntime.class);
                });
    }

    @Test
    @DisplayName("seedj.command.enabled=false 时关闭运行时与控制器")
    void commandDisabledSwitchBacksOffRuntimeAndControllers() {
        runner.withPropertyValues("seedj.command.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CommandRuntime.class);
            assertThat(context).doesNotHaveBean(CommandCatalog.class);
            assertThat(context).doesNotHaveBean(CommandStore.class);
            assertThat(context).hasSingleBean(TemporalStore.class);
        });
    }

    @Test
    @DisplayName("seedj.object.enabled=false 时关闭对象存储与查询控制器")
    void objectDisabledBacksOffStoreAndQueryController() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TemporalCommandAutoConfiguration.class))
                .withBean(BizEnabledMarker.class)
                .withPropertyValues("seedj.object.enabled=false", "seedj.command.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TemporalStore.class);
                    assertThat(context).doesNotHaveBean(
                            cn.byteforce.seedj.starter.domain.web.ObjectQueryController.class);
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
    @DisplayName("ObjectSchema bean 自动派生 TemporalTables（无需手工表定义）")
    void derivesTemporalTablesFromObjectSchema() {
        ObjectSchema schema = ObjectSchema.builder("probe", "probe_object")
                .field("name", FieldType.TEXT)
                .build();

        runner.withBean(ObjectSchema.class, () -> schema).run(context -> {
            assertThat(context).hasNotFailed();
            TemporalTables tables = context.getBean(TemporalTables.class);
            assertThat(tables.require("probe").columns()).containsEntry("name", "TEXT");
            assertThat(tables.require("probe").fieldTypes())
                    .containsEntry("name", FieldType.TEXT);
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
            super(runtime, CommandDescriptor.builder()
                    .type("probe.update")
                    .objectType("probe")
                    .payloadType(ProbeCommand.class)
                    .permission("probe.write")
                    .build());
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
