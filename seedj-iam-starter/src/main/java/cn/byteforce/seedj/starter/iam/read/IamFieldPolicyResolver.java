package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.read.FieldAccess;
import cn.byteforce.seedj.read.FieldPolicy;
import cn.byteforce.seedj.read.FieldPolicyResolver;
import cn.byteforce.seedj.read.ReadModel;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationSubject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 逐字段判定 {@code read} / {@code read.masked}，映射为 FULL/MASKED/HIDDEN。
 *
 * <p>没有匹配策略（policyId 为空）时不输出该字段，保留 schema 默认；显式 DENY 直接
 * HIDDEN，DENY 优先。writeOnly 字段不参与判定。判定走唯一授权决策点（组合实现），
 * 不直接持有 ABAC 引擎。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamFieldPolicyResolver implements FieldPolicyResolver {

    private final AuthorizationDecisionPoint decisions;

    private final ObjectModuleRegistry modules;

    private final Clock clock;

    /**
     * 构造解析器。
     *
     * @param decisions 唯一授权决策点
     * @param clock     取时入口
     * @param modules   模块注册表
     */
    public IamFieldPolicyResolver(AuthorizationDecisionPoint decisions, Clock clock,
                                  ObjectModuleRegistry modules) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    /**
     * 兼容旧装配：使用系统时钟。
     *
     * @param decisions 唯一授权决策点
     * @param modules   模块注册表
     */
    public IamFieldPolicyResolver(AuthorizationDecisionPoint decisions,
                                  ObjectModuleRegistry modules) {
        this(decisions, Clocks.system(), modules);
    }

    /**
     * 兼容旧装配：把策略引擎适配为决策点；生产装配必须传真实决策点。
     *
     * @param pdp     策略引擎
     * @param modules 模块注册表
     * @deprecated 使用 {@link #IamFieldPolicyResolver(AuthorizationDecisionPoint,
     *             ObjectModuleRegistry)}，让字段策略与命令共用同一决策点。
     */
    @Deprecated(forRemoval = false)
    public IamFieldPolicyResolver(IamPolicyDecisionPoint pdp, ObjectModuleRegistry modules) {
        this(policyAdapter(Objects.requireNonNull(pdp, "pdp")), Clocks.system(), modules);
    }

    private static AuthorizationDecisionPoint policyAdapter(IamPolicyDecisionPoint pdp) {
        return request -> {
            IamAuthorizationDecision decision = pdp.decide(toIamRequest(request));
            if (decision.allowed()) {
                return AuthorizationDecision.allow(decision.policyId());
            }
            return AuthorizationDecision.deny(decision.reason(), decision.reason(),
                    decision.policyId());
        };
    }

    private static IamAuthorizationRequest toIamRequest(AuthorizationRequest request) {
        AuthorizationSubject subject = request.subject();
        return new IamAuthorizationRequest(new IamSubject(subject.id(), subject.username(),
                subject.tenantId(), subject.roles(), subject.permissions(), subject.groups(),
                subject.amr(), subject.acr(), subject.attributes()), request.action(),
                request.resourceType(), request.resourceId(), request.resourceAttributes(),
                request.context());
    }

    @Override
    public boolean supports(String objectType) {
        return modules.find(objectType).isPresent();
    }

    @Override
    public FieldPolicy resolve(String objectType, Caller caller, ScopeContext context) {
        ReadModel model = modules.find(objectType).orElseThrow(
                () -> new IllegalArgumentException("unknown object type: " + objectType));
        Map<String, FieldAccess> fields = new LinkedHashMap<>();
        for (FieldDefinition field : model.schema().fields()) {
            if (field.writeOnly()) {
                continue;
            }
            AuthorizationDecision read = decisions.decide(request(objectType, field, caller,
                    context, "read"));
            if (read.allowed()) {
                fields.put(field.name(), new FieldAccess(FieldAccess.Action.FULL,
                        read.policyId()));
                continue;
            }
            if (read.policyId() != null) {
                fields.put(field.name(), new FieldAccess(FieldAccess.Action.HIDDEN,
                        read.policyId()));
                continue;
            }
            AuthorizationDecision masked = decisions.decide(request(objectType, field, caller,
                    context, "read.masked"));
            if (masked.allowed()) {
                fields.put(field.name(), new FieldAccess(FieldAccess.Action.MASKED,
                        masked.policyId()));
            } else if (masked.policyId() != null) {
                fields.put(field.name(), new FieldAccess(FieldAccess.Action.HIDDEN,
                        masked.policyId()));
            }
        }
        return new FieldPolicy(fields);
    }

    private AuthorizationRequest request(String objectType, FieldDefinition field,
                                         Caller caller, ScopeContext context, String action) {
        return new AuthorizationRequest(AuthorizationSubject.from(caller), action, objectType,
                field.name(), Map.of("classification", field.privacy().name()), clock.now(),
                context.facts());
    }
}
