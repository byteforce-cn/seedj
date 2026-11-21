package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * local/test 的本地决策点：按调用方权限声明判定，保留“无权限 403”的测试语义。
 *
 * <p>这是 IAM 决策点缺席时的兜底，必须排在 IAM 之后装配：{@code afterName} 指向 IAM 自动配置， 注册顺序保证
 * {@code @ConditionalOnMissingBean} 看到 IAM 的组合决策点后让位，也保证生产 profile 没有 IAM 时启动失败（fail-closed）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        after = BizSecurityAutoConfiguration.class,
        afterName = "cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration")
@Profile({"local", "test"})
public class LocalAuthorizationDecisionPointAutoConfiguration {

    /**
     * 本地开发决策点。
     *
     * @return 决策点
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthorizationDecisionPoint localAuthorizationDecisionPoint() {
        return request ->
                request.subject().permissions().contains(request.action())
                        ? AuthorizationDecision.allow("local-caller-permissions")
                        : AuthorizationDecision.deny(
                                "NO_PERMISSION",
                                "missing permission: " + request.action(),
                                "local-caller-permissions");
    }
}
