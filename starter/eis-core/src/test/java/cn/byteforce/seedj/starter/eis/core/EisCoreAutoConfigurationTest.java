package cn.byteforce.seedj.starter.eis.core;

import cn.byteforce.seedj.starter.eis.core.web.EisCommandController;
import cn.byteforce.seedj.starter.eis.core.web.EisCommandExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EisCoreAutoConfiguration} 装配边界测试（Bean 存在性 / 审计必配 / 开关）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisCoreAutoConfigurationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EisCoreAutoConfiguration.class))
            .withBean("jdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean("transactionManager", PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));

    private static EisAuthorizer allowAll() {
        return request -> EisAuthorizer.EisAuthorizationDecision.allowed();
    }

    private static EisCommandRequest command() {
        return new EisCommandRequest("demo.op", MAPPER.readTree("{}"), "k1", List.of(), ACTOR);
    }

    /**
     * 配置好最小提交夹具（mock 存储 + 单处理器）的 runner。
     *
     * @return 可提交命令的 runner
     */
    private ApplicationContextRunner submittableRunner() {
        EisCommandStore commandStore = mock(EisCommandStore.class);
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        return runner.withBean(EisAuditAppender.class, () -> request -> { })
                .withBean(EisCommandStore.class, () -> commandStore)
                .withBean(EisEventStore.class, () -> mock(EisEventStore.class))
                .withBean(EisObjectRepository.class, () -> mock(EisObjectRepository.class))
                .withBean(EisCommandHandler.class, StubHandler::new);
    }

    @Test
    void registersCoreBeansWithAuditAppender() {
        runner.withBean(EisAuditAppender.class, () -> request -> { })
                .withBean(EisAuthorizer.class, EisCoreAutoConfigurationTest::allowAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisClock.class);
                    assertThat(context).hasSingleBean(EisObjectTypeRegistry.class);
                    assertThat(context).hasSingleBean(EisObjectRepository.class);
                    assertThat(context).hasSingleBean(EisCommandStore.class);
                    assertThat(context).hasSingleBean(EisEventStore.class);
                    assertThat(context).hasSingleBean(EisCommandRuntime.class);
                    assertThat(context).hasSingleBean(EisOutboxDispatcher.class);
                    assertThat(context).hasSingleBean(EisNotifyHook.class);
                });
    }

    @Test
    void failsWithoutAuditAppender() {
        runner.withBean(EisAuthorizer.class, EisCoreAutoConfigurationTest::allowAll)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsWhenNoAuthorizerAndNoOptIn() {
        runner.withBean(EisAuditAppender.class, () -> request -> { })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("allow-all-authorizer");
                });
    }

    @Test
    void usesApplicationAuthorizerWhenProvided() {
        submittableRunner()
                .withBean(EisAuthorizer.class, () -> request ->
                        EisAuthorizer.EisAuthorizationDecision.denied("缺少权限", "NO_PERMISSION"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EisCommandResult result = context.getBean(EisCommandRuntime.class).submit(command());
                    assertThat(result.getStatus()).isEqualTo("rejected");
                    assertThat(result.getCode()).isEqualTo("EIS-403-001");
                });
    }

    @Test
    void allowsAllWhenExplicitlyOptedIn() {
        submittableRunner()
                .withPropertyValues("byteforce.eis.core.allow-all-authorizer=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisAuthorizer.class);
                    EisCommandResult result = context.getBean(EisCommandRuntime.class).submit(command());
                    assertThat(result.getStatus()).isEqualTo("accepted");
                });
    }

    @Test
    void disabledByPropertyRegistersNothing() {
        runner.withBean(EisAuditAppender.class, () -> request -> { })
                .withPropertyValues("byteforce.eis.core.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EisCommandRuntime.class);
                    assertThat(context).doesNotHaveBean(EisObjectRepository.class);
                });
    }

    @Test
    void collectsObjectTypesAndAuthorizerOverrides() {
        EisObjectType type = EisObjectType.builder("t_item").field("name", EisFieldKind.TEXT).build();
        runner.withBean(EisAuditAppender.class, () -> request -> { })
                .withBean(EisObjectType.class, () -> type)
                .withBean(EisAuthorizer.class, EisCoreAutoConfigurationTest::allowAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EisObjectTypeRegistry registry = context.getBean(EisObjectTypeRegistry.class);
                    assertThat(registry.all()).containsExactly(type);
                    assertThat(context).hasSingleBean(EisAuthorizer.class);
                });
    }

    @Test
    void webRunnerRegistersCommandEndpointAndResolver() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EisCoreAutoConfiguration.class))
                .withBean("jdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(EisAuditAppender.class, () -> request -> { })
                .withBean(EisAuthorizer.class, EisCoreAutoConfigurationTest::allowAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EisCommandController.class);
                    assertThat(context).hasSingleBean(EisCommandExceptionHandler.class);
                    assertThat(context).hasSingleBean(EisActorResolver.class);
                });
    }

    @Test
    void handlerDuplicatesFailFast() {
        EisCommandHandler first = new StubHandler();
        EisCommandHandler second = new StubHandler();
        runner.withBean(EisAuditAppender.class, () -> request -> { })
                .withBean(EisAuthorizer.class, EisCoreAutoConfigurationTest::allowAll)
                .withBean("first", EisCommandHandler.class, () -> first)
                .withBean("second", EisCommandHandler.class, () -> second)
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 重复注册用的占位处理器。
     */
    private static final class StubHandler implements EisCommandHandler {

        @Override
        public java.util.Set<String> commandTypes() {
            return java.util.Set.of("demo.op");
        }

        @Override
        public EisCommandHandlerResult handle(EisCommandContext context) {
            return new EisCommandHandlerResult(List.of(), null);
        }
    }
}
