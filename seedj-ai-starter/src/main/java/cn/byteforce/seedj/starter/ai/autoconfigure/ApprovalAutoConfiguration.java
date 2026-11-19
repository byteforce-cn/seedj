package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalExpiryJob;
import cn.byteforce.seedj.starter.ai.approval.ApprovalNotifier;
import cn.byteforce.seedj.starter.ai.approval.ApprovalProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.ai.approval.LoggingApprovalNotifier;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 审批装配：只有 {@code seedj.ai.approval.enabled=true} 时才要求 {@link WorkflowProvider}。
 *
 * <p>没有工作流端口时启动失败并点名键，而不是让不可逆动作在运行期才发现「审批不可用」。审批人列表在构造期解析， 非法 UUID 或空列表同样启动失败。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(AiEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.ai.approval", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ApprovalProperties.class, AiProperties.class})
public class ApprovalAutoConfiguration {

    /**
     * 启动期校验：审批人与工作流端口都必须存在。
     *
     * @param properties 审批配置
     * @param workflows 工作流端口
     * @throws IllegalStateException 审批人为空、非 UUID，或没有 WorkflowProvider
     */
    public ApprovalAutoConfiguration(
            ApprovalProperties properties, ObjectProvider<WorkflowProvider> workflows) {
        if (properties.approverIds().isEmpty()) {
            throw new IllegalStateException("seedj.ai.approval.approvers 至少一个");
        }
        if (workflows.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "seedj.ai.approval.enabled=true 需要 WorkflowProvider bean；本地请加入"
                            + " seedj-workflow-local 并设置 seedj.workflow.provider=memory");
        }
    }

    /**
     * 通知端口：没有 IM 时只记日志。
     *
     * @return 通知端口
     */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalNotifier approvalNotifier() {
        return new LoggingApprovalNotifier();
    }

    /**
     * 审批服务。
     *
     * @param workflows 工作流端口
     * @param ledger 决策账本
     * @param issuer token 签发方
     * @param clock 取时入口
     * @param properties 审批配置
     * @param ai AI 配置
     * @param notifier 通知端口
     * @return 审批服务
     */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalService approvalService(
            WorkflowProvider workflows,
            DecisionLedger ledger,
            DecisionTokenIssuer issuer,
            Clock clock,
            ApprovalProperties properties,
            AiProperties ai,
            ApprovalNotifier notifier) {
        return new ApprovalService(workflows, ledger, issuer, clock, properties, ai, notifier);
    }

    /**
     * 启动期注册审批定义；失败阻断启动。
     *
     * @param approvals 审批服务
     * @return 运行器
     */
    @Bean
    public ApplicationRunner approvalDefinitionRegistrar(ApprovalService approvals) {
        return arguments -> approvals.registerDefinition();
    }

    /**
     * 过期扫描 bean；宿主需要 {@code @EnableScheduling} 才会真正运行。
     *
     * @param approvals 审批服务
     * @param clock 取时入口
     * @return 扫描任务
     */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalExpiryJob approvalExpiryJob(ApprovalService approvals, Clock clock) {
        return new ApprovalExpiryJob(approvals, clock);
    }
}
