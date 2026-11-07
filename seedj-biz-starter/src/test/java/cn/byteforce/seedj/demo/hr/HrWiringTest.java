package cn.byteforce.seedj.demo.hr;

import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.starter.cqrs.wiring.CommandAgents;
import cn.byteforce.seedj.starter.cqrs.wiring.CommandMetrics;
import cn.byteforce.seedj.cqrs.QueryAgent;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.demo.hr.app.HrCommandService;
import cn.byteforce.seedj.demo.hr.app.OnboardingAgent;
import cn.byteforce.seedj.demo.hr.config.HrConfiguration;
import cn.byteforce.seedj.demo.hr.domain.EmployeeOnboarded;
import cn.byteforce.seedj.demo.hr.domain.EmployeeRepository;
import cn.byteforce.seedj.demo.hr.query.EmployeeDirectoryProjection;
import cn.byteforce.seedj.demo.hr.query.EmployeeView;
import cn.byteforce.seedj.demo.hr.query.HrQueryService;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.id.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配验证：HR 模块只靠 core 的 CQRS 契约 + 两个外部 bean（字符串 ID 生成器、事件发布器）
 * 就能装起来，且投影的订阅 topic 来自 {@code @EventType} 而不是手写字符串。
 */
class HrWiringTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HrConfiguration.class, TestCollaborators.class);

    @Test
    @DisplayName("命令代理、查询代理、仓储、锁、事务边界都被装配出来")
    void wiresAllBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OnboardingAgent.class);
            assertThat(context).hasSingleBean(CommandRuntime.class);
            assertThat(context).hasSingleBean(CommandCatalog.class);
            assertThat(context.getBean(CommandCatalog.class)
                    .find("hr.employee.onboard").orElseThrow().isEventSourced()).isTrue();
            assertThat(context).hasSingleBean(HrCommandService.class);
            assertThat(context).hasSingleBean(HrQueryService.class);
            assertThat(context).hasSingleBean(EmployeeRepository.class);
            assertThat(context).hasSingleBean(EmployeeDirectoryProjection.class);
            assertThat(context).hasSingleBean(LockProvider.class);
            assertThat(context).hasSingleBean(TransactionRunner.class);
        });
    }

    @Test
    @DisplayName("每个读模型一个查询代理，泛型不能串号")
    void wiresOneQueryAgentPerReadModel() {
        runner.run(context -> {
            var optionalView = org.springframework.core.ResolvableType
                    .forClassWithGenerics(Optional.class, EmployeeView.class);
            var listView = org.springframework.core.ResolvableType
                    .forClassWithGenerics(List.class, EmployeeView.class);

            assertThat(context.getBeanNamesForType(org.springframework.core.ResolvableType
                    .forClassWithGenerics(QueryAgent.class, optionalView))).hasSize(1);
            assertThat(context.getBeanNamesForType(org.springframework.core.ResolvableType
                    .forClassWithGenerics(QueryAgent.class, listView))).hasSize(1);
        });
    }

    @Test
    @DisplayName("投影的订阅元数据由 @EventType 与投影名推导")
    void projectionSubscribesByLogicalName() {
        runner.run(context -> {
            EmployeeDirectoryProjection projection =
                    context.getBean(EmployeeDirectoryProjection.class);

            assertThat(projection.eventType()).isEqualTo(EmployeeOnboarded.class);
            assertThat(projection.subscription().group()).isEqualTo("demo.hr.employee.directory");
            assertThat(projection.subscription().topics())
                    .containsExactly("hr.employee.onboarded");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestCollaborators {

        @Bean
        IdGenerator<String> stringIdGenerator() {
            return () -> "test-" + SEQ.incrementAndGet();
        }

        @Bean
        EventPublisher eventPublisher() {
            return new EventPublisher() {
                @Override
                public void publish(DomainEvent event) {
                }

                @Override
                public void publish(EventMessage message) {
                }
            };
        }

        @Bean
        CommandAgents commandAgents(TransactionRunner transactionRunner, LockProvider lockProvider) {
            return new CommandAgents(transactionRunner, lockProvider, CommandAudit.noop(),
                    CommandMetrics.noop(), List.of());
        }
    }
}
