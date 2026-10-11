package cn.byteforce.seedj.starter.eis.core;

import cn.byteforce.seedj.starter.eis.core.web.EisCommandController;
import cn.byteforce.seedj.starter.eis.core.web.EisCommandExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link EisCoreAutoConfiguration} 装配边界测试（Bean 存在性 / 审计必配 / 开关）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisCoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EisCoreAutoConfiguration.class))
            .withBean("jdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean("transactionManager", PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));

    @Test
    void registersCoreBeansWithAuditAppender() {
        runner.withBean(EisAuditAppender.class, () -> request -> { })
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
        runner.run(context -> assertThat(context).hasFailed());
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
                .withBean(EisAuthorizer.class, () -> request -> EisAuthorizer.EisAuthorizationDecision.allowed())
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
