package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.ai.approval.im.ApprovalCardActionHandler;
import cn.byteforce.seedj.starter.ai.approval.im.ApprovalCardNotifier;
import cn.byteforce.seedj.starter.im.ImSystemNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** IM 适配器：审批 + IM 两个 bean 都在时才装配。 */
class ApprovalImWiringTest {

    private static final ApplicationContextRunner RUNNER =
            new ApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withConfiguration(AutoConfigurations.of(ApprovalImAutoConfiguration.class))
                    .withPropertyValues("seedj.ai.approval.enabled=true");

    @Test
    @DisplayName("审批 + IM 都在：装配卡片通知与动作处理器")
    void wiresAdapters() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApprovalCardNotifier.class);
                    assertThat(context).hasSingleBean(ApprovalCardActionHandler.class);
                });
    }

    @Test
    @DisplayName("没有审批：不装配")
    void noApprovalDoesNotWire() {
        RUNNER.withPropertyValues("seedj.ai.approval.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApprovalCardNotifier.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Host {

        @Bean
        ApprovalService approvalService() {
            return mock(ApprovalService.class);
        }

        @Bean
        ImSystemNotifier imSystemNotifier() {
            return mock(ImSystemNotifier.class);
        }
    }
}
