package cn.byteforce.seedj.starter.ai.autoconfigure;

import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.ai.approval.im.ApprovalCardActionHandler;
import cn.byteforce.seedj.starter.ai.approval.im.ApprovalCardNotifier;
import cn.byteforce.seedj.starter.im.ImSystemNotifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 有 IM 依赖时的审批卡片适配器；没有 IM 时整个配置不加载，审批仍走 HTTP。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        afterName = {
            "cn.byteforce.seedj.starter.ai.autoconfigure.ApprovalAutoConfiguration",
            "cn.byteforce.seedj.starter.im.ImCommandAutoConfiguration"
        })
@ConditionalOnClass(ImSystemNotifier.class)
@ConditionalOnBean({ApprovalService.class, ImSystemNotifier.class})
@ConditionalOnProperty(prefix = "seedj.ai.approval", name = "enabled", havingValue = "true")
public class ApprovalImAutoConfiguration {

    @Bean
    @Primary
    public ApprovalCardNotifier approvalCardNotifier(ImSystemNotifier notifier) {
        return new ApprovalCardNotifier(notifier);
    }

    @Bean
    public ApprovalCardActionHandler approvalCardActionHandler(ApprovalService approvals) {
        return new ApprovalCardActionHandler(approvals);
    }
}
