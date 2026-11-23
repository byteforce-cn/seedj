package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.infra.coord.CoordProperties;
import cn.byteforce.seedj.infra.coord.CoordProviderGuardAutoConfiguration;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoordWorkflowProviderWiringTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(CoordWorkflowAutoConfiguration.class))
                    .withBean(CoordProperties.class)
                    .withBean(
                            CoordClient.class,
                            () -> mock(CoordClient.class, RETURNS_DEEP_STUBS));

    @Test
    void providerCoordWiresTheCoordBean() {
        runner.withPropertyValues(
                        "seedj.workflow.provider=coord",
                        "seedj.workflow.coord.sign-admin=sign-admin",
                        "spring.application.name=app")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasBean("coord");
                            assertThat(context.getBean("coord"))
                                    .isInstanceOf(CoordWorkflowProvider.class);
                        });
    }

    @Test
    void providerMemoryDoesNotWireCoord() {
        runner.withPropertyValues("seedj.workflow.provider=memory")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(WorkflowProvider.class)).isEmpty();
                        });
    }

    @Test
    void disabledWorkflowDoesNotWireCoord() {
        runner.withPropertyValues(
                        "seedj.workflow.provider=coord",
                        "seedj.workflow.enabled=false",
                        "seedj.workflow.coord.sign-admin=sign-admin")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(WorkflowProvider.class)).isEmpty();
                        });
    }

    @Test
    void missingSignAdminFailsStartup() {
        runner.withPropertyValues("seedj.workflow.provider=coord")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining(
                                            "seedj.workflow.coord.sign-admin is required");
                        });
    }

    @Test
    void existingWorkflowProviderWins() {
        WorkflowProvider custom = mock(WorkflowProvider.class);
        runner.withBean(WorkflowProvider.class, () -> custom)
                .withPropertyValues(
                        "seedj.workflow.provider=coord",
                        "seedj.workflow.coord.sign-admin=sign-admin")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(WorkflowProvider.class)).isSameAs(custom);
                            assertThat(context).doesNotHaveBean("coord");
                        });
    }

    @Test
    void guardRejectsCoordProviderWhenCoordIsDisabled() {
        runner.withConfiguration(
                        AutoConfigurations.of(CoordProviderGuardAutoConfiguration.class))
                .withPropertyValues(
                        "seedj.workflow.provider=coord",
                        "seedj.workflow.coord.sign-admin=sign-admin",
                        "seedj.coord.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining(
                                            "coord provider selected but seedj.coord.enabled is"
                                                    + " not true");
                        });
    }
}
