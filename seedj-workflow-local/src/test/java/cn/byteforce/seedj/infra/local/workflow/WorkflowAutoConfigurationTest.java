package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WorkflowAutoConfiguration.class));

    @Test
    void providesMemoryProviderByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowProvider.class);
            assertThat(context.getBean("memory")).isInstanceOf(InMemoryWorkflowProvider.class);
            assertThat(context.getBean(WorkflowProperties.class).getProvider())
                    .isEqualTo("memory");
        });
    }

    @Test
    void disabledTurnsOffProvider() {
        runner.withPropertyValues("seedj.workflow.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WorkflowProvider.class));
    }

    @Test
    void otherProviderSelectionDoesNotInstantiateMemory() {
        runner.withPropertyValues("seedj.workflow.provider=flowable")
                .run(context -> assertThat(context).doesNotHaveBean(WorkflowProvider.class));
    }

    @Test
    void userBeanWins() {
        runner.withBean(WorkflowProvider.class, StubProvider::new)
                .run(context -> assertThat(context.getBean(WorkflowProvider.class))
                        .isInstanceOf(StubProvider.class));
    }

    private static final class StubProvider implements WorkflowProvider {

        @Override
        public void register(cn.byteforce.seedj.workflow.spi.WorkflowDefinition definition) {
        }

        @Override
        public Optional<cn.byteforce.seedj.workflow.spi.WorkflowDefinition> findDefinition(
                cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId id,
                cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion version) {
            return Optional.empty();
        }

        @Override
        public cn.byteforce.seedj.workflow.spi.WorkflowInstance start(
                cn.byteforce.seedj.workflow.spi.StartWorkflowRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.byteforce.seedj.workflow.spi.WorkflowInstance act(
                cn.byteforce.seedj.workflow.spi.ActionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.byteforce.seedj.workflow.spi.WorkflowInstance cancel(
                cn.byteforce.seedj.workflow.spi.CancelWorkflowRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.byteforce.seedj.workflow.spi.WorkflowInstance terminate(
                cn.byteforce.seedj.workflow.spi.TerminateWorkflowRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<cn.byteforce.seedj.workflow.spi.WorkflowInstance> describe(
                cn.byteforce.seedj.workflow.spi.WorkflowInstanceId instanceId) {
            return Optional.empty();
        }

        @Override
        public List<cn.byteforce.seedj.workflow.spi.WorkflowInstance> search(
                cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery query) {
            return List.of();
        }

        @Override
        public List<cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent> history(
                cn.byteforce.seedj.workflow.spi.WorkflowInstanceId instanceId) {
            return List.of();
        }

        @Override
        public List<cn.byteforce.seedj.workflow.spi.HumanTask> listTasks(
                cn.byteforce.seedj.workflow.spi.TaskQuery query) {
            return List.of();
        }

        @Override
        public Optional<cn.byteforce.seedj.workflow.spi.HumanTask> describeTask(
                cn.byteforce.seedj.workflow.spi.TaskId taskId) {
            return Optional.empty();
        }

        @Override
        public Instant now() {
            return Instant.EPOCH;
        }

        @Override
        public WorkflowCapabilities capabilities() {
            return WorkflowCapabilities.basic();
        }

        @Override
        public void close() {
        }
    }
}
