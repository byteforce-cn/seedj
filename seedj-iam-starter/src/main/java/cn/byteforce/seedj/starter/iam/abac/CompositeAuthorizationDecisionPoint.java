package cn.byteforce.seedj.starter.iam.abac;

import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationRequest;
import java.util.Objects;

/**
 * 组合决策点：04 授权契约（RBAC + SoD + 声明条件）与 ABAC 策略引擎的内部实现。
 *
 * <p>字段级读取（{@code action} 以 {@code read.} 开头或带 {@code classification}）
 * 走策略引擎；命令权限走契约检查。带资源属性的命令同时询问两个引擎，任一拒绝即拒绝。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CompositeAuthorizationDecisionPoint implements AuthorizationDecisionPoint {

    private final AuthorizationDecisionPoint contract;

    private final IamPolicyDecisionPoint policies;

    /**
     * 构造组合决策点。
     *
     * @param contract 04 授权契约决策点
     * @param policies ABAC 策略引擎；{@code null} 表示未启用
     */
    public CompositeAuthorizationDecisionPoint(
            AuthorizationDecisionPoint contract, IamPolicyDecisionPoint policies) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.policies = policies;
    }

    @Override
    public AuthorizationDecision decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        boolean fieldRead =
                request.action().startsWith("read.")
                        || request.resourceAttributes().containsKey("classification");
        if (policies == null) {
            return contract.decide(request);
        }
        if (fieldRead) {
            return policies.decide(request);
        }
        AuthorizationDecision contractDecision = contract.decide(request);
        if (!contractDecision.allowed()
                || !request.resourceAttributes().containsKey("classification")) {
            return contractDecision;
        }
        AuthorizationDecision policyDecision = policies.decide(request);
        return policyDecision.allowed() ? contractDecision : policyDecision;
    }

}
