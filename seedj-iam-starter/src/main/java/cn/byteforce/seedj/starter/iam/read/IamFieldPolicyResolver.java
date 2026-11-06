package cn.byteforce.seedj.starter.iam.read;

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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 逐字段判定 {@code read} / {@code read.masked}，映射为 FULL/MASKED/HIDDEN。
 *
 * <p>没有匹配策略（deniedBy 为空）时不输出该字段，保留 schema 默认；显式 DENY 直接
 * HIDDEN，DENY 优先。writeOnly 字段不参与判定。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamFieldPolicyResolver implements FieldPolicyResolver {

    private final IamPolicyDecisionPoint pdp;

    private final ObjectModuleRegistry modules;

    /**
     * 构造解析器。
     *
     * @param pdp     策略决策点
     * @param modules 模块注册表
     */
    public IamFieldPolicyResolver(IamPolicyDecisionPoint pdp, ObjectModuleRegistry modules) {
        this.pdp = Objects.requireNonNull(pdp, "pdp");
        this.modules = Objects.requireNonNull(modules, "modules");
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
            IamAuthorizationRequest request = request(objectType, field, caller, context);
            IamAuthorizationDecision read = pdp.decide(request);
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
            IamAuthorizationDecision masked = pdp.decide(new IamAuthorizationRequest(
                    request.subject(), "read.masked", request.resourceType(),
                    request.resourceId(), request.resourceAttributes(),
                    request.environment()));
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

    private static IamAuthorizationRequest request(String objectType, FieldDefinition field,
                                                   Caller caller, ScopeContext context) {
        IamSubject subject = new IamSubject(caller.id(), caller.username(), caller.tenantId(),
                caller.roles(), caller.permissions(), caller.groups(), caller.amr(),
                caller.acr(), caller.attributes());
        return new IamAuthorizationRequest(subject, "read", objectType, field.name(),
                Map.of("classification", field.privacy().name()), context.facts());
    }
}
