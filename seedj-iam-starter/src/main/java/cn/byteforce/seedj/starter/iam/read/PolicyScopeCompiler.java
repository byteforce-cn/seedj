package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeKeyCatalog;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.PolicyOperandSource;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.read.ReadModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把 IAM 的 ALLOW/DENY 策略编译成受限行范围谓词。
 *
 * <p>只有 {@code subject.*}、{@code environment.*} 与字面量可以折叠成常量； {@code resource.<field>}
 * 保留为谓词变量；字段对字段比较、未知 context key、 未知字段与不支持的算子都在编译期失败。
 *
 * <p>语义：ALLOW 之间 OR，DENY 取反后 AND；没有 ALLOW 结果是 {@link ScopePredicate.None}， 绝不默认放行。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PolicyScopeCompiler {

    private static final String READ = "read";

    private final IamPolicyRepository policies;

    private final ObjectModuleRegistry modules;

    private final ScopeKeyCatalog keys;

    /**
     * 构造编译器。
     *
     * @param policies 策略仓库
     * @param modules 模块注册表
     * @param keys 已声明请求事实 key
     */
    public PolicyScopeCompiler(
            IamPolicyRepository policies, ObjectModuleRegistry modules, ScopeKeyCatalog keys) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /**
     * 编译某对象在请求上下文中的行范围。
     *
     * @param objectType 对象类型
     * @param caller 调用方
     * @param context 请求事实
     * @return 谓词（没有 ALLOW 时为 {@code None}）
     */
    public ScopePredicate compile(String objectType, Caller caller, ScopeContext context) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(context, "context");
        ReadModel model =
                modules.find(objectType)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown object type: " + objectType));
        List<ScopePredicate> allows = new ArrayList<>();
        List<ScopePredicate> denyParts = new ArrayList<>();
        for (IamPolicy policy : policies.findEnabled(caller.tenantId())) {
            if (!matches(policy.actions(), READ) || !matches(policy.resources(), objectType)) {
                continue;
            }
            ScopePredicate condition =
                    compileCondition(
                            policy.condition(), model.schema(), caller, context, policy.id());
            if (policy.effect() == IamPolicyEffect.ALLOW) {
                allows.add(condition);
            } else {
                denyParts.add(ScopePredicate.not(condition));
            }
        }
        if (allows.isEmpty()) {
            return ScopePredicate.none();
        }
        ScopePredicate result = ScopePredicates.or(allows);
        if (result instanceof ScopePredicate.None) {
            return result;
        }
        for (ScopePredicate deny : denyParts) {
            result = ScopePredicates.and(List.of(result, deny));
        }
        return result;
    }

    /** 启动期校验全部启用策略；未知字段/context key/算子/字段对字段比较都失败。 */
    public void validateAll() {
        for (IamPolicy policy :
                policies.findEnabled(cn.byteforce.seedj.iam.IamUser.DEFAULT_TENANT)) {
            if (!matches(policy.actions(), READ)) {
                continue;
            }
            for (ReadModel model : modules.all()) {
                if (matches(policy.resources(), model.objectType())) {
                    validateCondition(policy.condition(), model.schema(), policy.id());
                }
            }
        }
    }

    private ScopePredicate compileCondition(
            PolicyCondition condition,
            ObjectSchema schema,
            Caller caller,
            ScopeContext context,
            String policyId) {
        return switch (condition) {
            case PolicyCondition.Always ignored -> ScopePredicate.all();
            case PolicyCondition.And and ->
                    ScopePredicates.and(
                            and.conditions().stream()
                                    .map(
                                            part ->
                                                    compileCondition(
                                                            part, schema, caller, context,
                                                            policyId))
                                    .toList());
            case PolicyCondition.Or or ->
                    ScopePredicates.or(
                            or.conditions().stream()
                                    .map(
                                            part ->
                                                    compileCondition(
                                                            part, schema, caller, context,
                                                            policyId))
                                    .toList());
            case PolicyCondition.Not not ->
                    ScopePredicate.not(
                            compileCondition(not.condition(), schema, caller, context, policyId));
            case PolicyCondition.Exists exists -> exists(exists, schema, caller, context, policyId);
            case PolicyCondition.Compare compare ->
                    compare(compare, schema, caller, context, policyId);
        };
    }

    private ScopePredicate exists(
            PolicyCondition.Exists exists,
            ObjectSchema schema,
            Caller caller,
            ScopeContext context,
            String policyId) {
        PolicyOperand operand = exists.operand();
        if (operand.source() == PolicyOperandSource.RESOURCE) {
            requireField(schema, operand.path(), policyId);
            return ScopePredicate.notNull(operand.path());
        }
        Object value = constant(operand, caller, context, policyId);
        return value == null ? ScopePredicate.none() : ScopePredicate.all();
    }

    private ScopePredicate compare(
            PolicyCondition.Compare compare,
            ObjectSchema schema,
            Caller caller,
            ScopeContext context,
            String policyId) {
        PolicyComparisonOperator operator = compare.operator();
        if (operator != PolicyComparisonOperator.EQ
                && operator != PolicyComparisonOperator.NE
                && operator != PolicyComparisonOperator.IN) {
            throw unsupported(policyId, "operator " + operator);
        }
        PolicyOperand left = compare.left();
        PolicyOperand right = compare.right();
        boolean leftResource = left.source() == PolicyOperandSource.RESOURCE;
        boolean rightResource = right.source() == PolicyOperandSource.RESOURCE;
        if (leftResource && rightResource) {
            throw unsupported(policyId, "field-to-field comparison");
        }
        if (!leftResource && !rightResource) {
            Object leftValue = constant(left, caller, context, policyId);
            Object rightValue = constant(right, caller, context, policyId);
            return constantCompare(operator, leftValue, rightValue)
                    ? ScopePredicate.all()
                    : ScopePredicate.none();
        }
        if (leftResource) {
            requireField(schema, left.path(), policyId);
            Object value = constant(right, caller, context, policyId);
            return predicate(operator, left.path(), value, policyId);
        }
        if (operator == PolicyComparisonOperator.IN) {
            throw unsupported(policyId, "constant IN resource field");
        }
        requireField(schema, right.path(), policyId);
        Object value = constant(left, caller, context, policyId);
        return predicate(operator, right.path(), value, policyId);
    }

    private static ScopePredicate predicate(
            PolicyComparisonOperator operator, String field, Object value, String policyId) {
        if (value == null) {
            return ScopePredicate.none();
        }
        return switch (operator) {
            case EQ -> ScopePredicate.eq(field, value);
            case NE -> ScopePredicate.not(ScopePredicate.eq(field, value));
            case IN -> {
                if (!(value instanceof Collection<?> collection)) {
                    throw unsupported(policyId, "IN requires a collection constant");
                }
                yield ScopePredicate.in(field, collection);
            }
            default -> throw unsupported(policyId, "operator " + operator);
        };
    }

    private Object constant(
            PolicyOperand operand, Caller caller, ScopeContext context, String policyId) {
        return switch (operand.source()) {
            case LITERAL -> operand.literal();
            case SUBJECT -> subjectValue(caller, operand.path());
            case ENVIRONMENT -> environmentValue(context, operand.path(), policyId);
            case RESOURCE -> throw unsupported(policyId, "resource operand is not constant");
            case ACTION -> throw unsupported(policyId, "action operand is not supported");
        };
    }

    private Object environmentValue(ScopeContext context, String path, String policyId) {
        try {
            keys.require(path);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "policy " + policyId + " references undeclared environment key: " + path,
                    error);
        }
        Object value = context.get(path).orElse(null);
        if (value == null) {
            return null;
        }
        try {
            return keys.require(path).type().validate(path, value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "policy " + policyId + " environment key " + path + " has wrong type", error);
        }
    }

    private static Object subjectValue(Caller caller, String path) {
        return switch (path) {
            case "userId", "sub" -> caller.id();
            case "username" -> caller.username();
            case "tenantId", "tenant_id" -> caller.tenantId();
            case "roles" -> caller.roles();
            case "permissions" -> caller.permissions();
            case "groups" -> caller.groups();
            case "amr" -> caller.amr();
            case "acr" -> caller.acr();
            default -> caller.attributes().get(path);
        };
    }

    private void validateCondition(
            PolicyCondition condition, ObjectSchema schema, String policyId) {
        switch (condition) {
            case PolicyCondition.Always ignored -> {}
            case PolicyCondition.And and ->
                    and.conditions().forEach(part -> validateCondition(part, schema, policyId));
            case PolicyCondition.Or or ->
                    or.conditions().forEach(part -> validateCondition(part, schema, policyId));
            case PolicyCondition.Not not -> validateCondition(not.condition(), schema, policyId);
            case PolicyCondition.Exists exists ->
                    validateOperand(exists.operand(), schema, policyId);
            case PolicyCondition.Compare compare -> {
                if (compare.operator() != PolicyComparisonOperator.EQ
                        && compare.operator() != PolicyComparisonOperator.NE
                        && compare.operator() != PolicyComparisonOperator.IN) {
                    throw unsupported(policyId, "operator " + compare.operator());
                }
                boolean leftResource = compare.left().source() == PolicyOperandSource.RESOURCE;
                boolean rightResource = compare.right().source() == PolicyOperandSource.RESOURCE;
                if (leftResource && rightResource) {
                    throw unsupported(policyId, "field-to-field comparison");
                }
                if (!leftResource
                        && !rightResource
                        && compare.operator() == PolicyComparisonOperator.IN) {
                    // constant-only IN is valid; nothing else to check
                }
                validateOperand(compare.left(), schema, policyId);
                validateOperand(compare.right(), schema, policyId);
            }
        }
    }

    private void validateOperand(PolicyOperand operand, ObjectSchema schema, String policyId) {
        switch (operand.source()) {
            case RESOURCE -> requireField(schema, operand.path(), policyId);
            case ENVIRONMENT -> {
                try {
                    keys.require(operand.path());
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException(
                            "policy "
                                    + policyId
                                    + " references undeclared environment key: "
                                    + operand.path(),
                            error);
                }
            }
            case ACTION -> throw unsupported(policyId, "action operand is not supported");
            case SUBJECT, LITERAL -> {}
        }
    }

    private static void requireField(ObjectSchema schema, String field, String policyId) {
        if (schema.field(field).isEmpty()) {
            throw new IllegalArgumentException(
                    "policy " + policyId + " references unknown resource field: " + field);
        }
    }

    private static IllegalArgumentException unsupported(String policyId, String detail) {
        return new IllegalArgumentException("policy " + policyId + " is not compilable: " + detail);
    }

    private static boolean constantCompare(
            PolicyComparisonOperator operator, Object left, Object right) {
        return switch (operator) {
            case EQ -> equal(left, right);
            case NE -> !equal(left, right);
            case IN ->
                    right instanceof Collection<?> values
                            && values.stream().anyMatch(value -> equal(left, value));
            default -> false;
        };
    }

    private static boolean equal(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Collection<?> leftValues
                && right instanceof Collection<?> rightValues) {
            return leftValues.size() == rightValues.size() && leftValues.containsAll(rightValues);
        }
        if (left instanceof Collection<?> leftValues) {
            return leftValues.stream().anyMatch(value -> equal(value, right));
        }
        if (right instanceof Collection<?> rightValues) {
            return rightValues.stream().anyMatch(value -> equal(left, value));
        }
        if (left instanceof Number l && right instanceof Number r) {
            return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString())) == 0;
        }
        return left.equals(right) || String.valueOf(left).equals(String.valueOf(right));
    }

    private static boolean matches(Set<String> patterns, String value) {
        for (String pattern : patterns) {
            if ("*".equals(pattern) || pattern.equals(value)) {
                return true;
            }
            if (pattern.endsWith("*")
                    && value.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
