package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import cn.byteforce.seedj.iam.authz.ConditionOutcome;
import cn.byteforce.seedj.iam.authz.IamAttributeSource;
import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.IamCheckResult;
import cn.byteforce.seedj.iam.authz.IamCondition;
import cn.byteforce.seedj.iam.authz.IamConditionEvaluator;
import cn.byteforce.seedj.iam.authz.IamPermissionCondition;
import cn.byteforce.seedj.iam.authz.IamProxy;
import cn.byteforce.seedj.iam.authz.IamResolution;
import cn.byteforce.seedj.iam.authz.SodEnforcement;
import cn.byteforce.seedj.iam.authz.SodEvaluator;
import cn.byteforce.seedj.iam.authz.SodFinding;
import cn.byteforce.seedj.iam.authz.SodRule;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 04 授权契约的解析与检查实现：时点绑定、代理展开、SoD、声明条件。
 *
 * <p>绑定只引用既有 {@code iam_role}；角色权限仍由 RBAC 仓储提供。条件只来自 声明的 {@link IamPermissionCondition}，请求不能携带条件。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamContractAuthorizationService implements AuthorizationDecisionPoint {

    private final IamBindingStore bindings;

    private final IamAuthorizationRepository roles;

    private final IamAttributeSource attributes;

    private final Map<String, IamCondition> conditions;

    private final List<SodRule> sodRules;

    private final IamConditionEvaluator evaluator = new IamConditionEvaluator();

    /**
     * 构造服务。
     *
     * @param bindings 绑定/代理存储
     * @param roles 角色权限仓储
     * @param attributes 属性源
     * @param conditions 权限条件声明
     * @param ruleSet SoD 规则
     */
    public IamContractAuthorizationService(
            IamBindingStore bindings,
            IamAuthorizationRepository roles,
            IamAttributeSource attributes,
            List<IamPermissionCondition> conditions,
            SodRuleSet ruleSet) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        Map<String, IamCondition> declared = new LinkedHashMap<>();
        if (conditions != null) {
            for (IamPermissionCondition condition : conditions) {
                if (declared.putIfAbsent(condition.permission(), condition.condition()) != null) {
                    throw new IllegalArgumentException(
                            "duplicate condition for permission: " + condition.permission());
                }
            }
        }
        this.conditions = Map.copyOf(declared);
        this.sodRules = ruleSet == null ? List.of() : ruleSet.rules();
    }

    /**
     * 决策点入口：把授权请求映射为 04 授权契约检查。
     *
     * @param request 授权请求
     * @return 决策
     */
    @Override
    public AuthorizationDecision decide(AuthorizationRequest request) {
        IamCheckResult result =
                check(request.subject().id(), request.action(), request.asOf(), null, null, null);
        if (result.allowed()) {
            return new AuthorizationDecision(true, null, result.reason(), result.reasonCode());
        }
        return AuthorizationDecision.deny(
                result.reasonCode(), result.reason(), result.attributeState().wireName());
    }

    /**
     * 解析主体在指定时点的角色与权限。
     *
     * @param principal 主体
     * @param asOf 业务时点
     * @param recordedAsOf 系统时点；可空
     * @param scope 作用域过滤；空表示不过滤
     * @return 解析结果
     */
    public IamResolution resolve(
            String principal, Instant asOf, Instant recordedAsOf, Map<String, String> scope) {
        Access access = load(principal, asOf, recordedAsOf, scope);
        List<String> warnings =
                access.findings().stream()
                        .filter(finding -> finding.enforcement() == SodEnforcement.WARN)
                        .map(SodFinding::ruleId)
                        .toList();
        return new IamResolution(
                List.copyOf(access.roles()), List.copyOf(access.permissions()), warnings);
    }

    /**
     * 检查权限。
     *
     * @param principal 主体
     * @param permission 权限
     * @param asOf 业务时点
     * @param recordedAsOf 系统时点；可空
     * @param providedSnapshot 调用方已拉取的快照；可空
     * @param providedState 调用方快照状态；可空（缺省 fresh）
     * @return 检查结果
     */
    public IamCheckResult check(
            String principal,
            String permission,
            Instant asOf,
            Instant recordedAsOf,
            Map<String, Object> providedSnapshot,
            AttributeState providedState) {
        Access access = load(principal, asOf, recordedAsOf, Map.of());
        if (!access.permissions().contains(permission)) {
            return new IamCheckResult(
                    false,
                    "permission not granted: " + permission,
                    "NO_PERMISSION",
                    AttributeState.NOT_REQUIRED);
        }
        for (SodFinding finding : access.findings()) {
            if (finding.enforcement() == SodEnforcement.PREVENT
                    && finding.permissions().contains(permission)) {
                return new IamCheckResult(
                        false,
                        "separation of duties conflict: " + finding.ruleId(),
                        finding.ruleId(),
                        AttributeState.NOT_REQUIRED);
            }
        }
        IamCondition condition = conditions.get(permission);
        if (condition == null) {
            return new IamCheckResult(
                    true, "permission granted", "ALLOWED", AttributeState.NOT_REQUIRED);
        }
        AttributeLookup lookup =
                providedSnapshot == null
                        ? attributes.lookup(principal, asOf, recordedAsOf)
                        : new AttributeLookup(
                                providedState == null ? AttributeState.FRESH : providedState,
                                snapshot(principal, asOf, recordedAsOf, providedSnapshot));
        if (lookup.state() != AttributeState.FRESH) {
            return failure(lookup.state());
        }
        Map<String, Object> body =
                lookup.snapshot() == null ? Map.of() : lookup.snapshot().snapshot();
        ConditionOutcome outcome = evaluator.evaluate(condition, body);
        if (!outcome.matched()) {
            return new IamCheckResult(
                    false,
                    "attribute condition not satisfied: " + outcome.attribute(),
                    outcome.reasonCode(),
                    AttributeState.FRESH);
        }
        return new IamCheckResult(true, "condition satisfied", "ALLOWED", AttributeState.FRESH);
    }

    /**
     * 拉取主体属性快照。
     *
     * @param principal 主体
     * @param asOf 业务时点
     * @param recordedAsOf 系统时点；可空
     * @return 拉取结果
     */
    public AttributeLookup attributes(String principal, Instant asOf, Instant recordedAsOf) {
        return attributes.lookup(principal, asOf, recordedAsOf);
    }

    private Access load(
            String principal, Instant asOf, Instant recordedAsOf, Map<String, String> scope) {
        Set<String> roleCodes = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        List<Grant> grants = new ArrayList<>();
        collect(principal, asOf, recordedAsOf, scope, roleCodes, permissions, grants);
        for (IamProxy proxy : bindings.effectiveProxies(principal, asOf, recordedAsOf)) {
            if (!scopeMatches(proxy.scope(), scope)) {
                continue;
            }
            collect(proxy.principal(), asOf, recordedAsOf, scope, roleCodes, permissions, grants);
        }
        Map<String, List<Map<String, String>>> scopes = new LinkedHashMap<>();
        for (Grant grant : grants) {
            scopes.computeIfAbsent(grant.permission(), ignored -> new ArrayList<>())
                    .add(grant.scope());
        }
        return new Access(roleCodes, permissions, SodEvaluator.findings(sodRules, scopes));
    }

    private void collect(
            String principal,
            Instant asOf,
            Instant recordedAsOf,
            Map<String, String> scope,
            Set<String> roleCodes,
            Set<String> permissions,
            List<Grant> grants) {
        for (IamBinding binding : bindings.effectiveBindings(principal, asOf, recordedAsOf)) {
            if (!scopeMatches(binding.scope(), scope)) {
                continue;
            }
            Set<String> hierarchy = roles.findRoleIdsWithAncestors(Set.of(binding.role()));
            for (String roleId : hierarchy) {
                roles.findRole(roleId).map(IamRole::code).ifPresent(roleCodes::add);
            }
            for (String permission : roles.findPermissionCodes(hierarchy)) {
                permissions.add(permission);
                grants.add(new Grant(permission, binding.scope()));
            }
        }
    }

    private static boolean scopeMatches(
            Map<String, String> bindingScope, Map<String, String> requestedScope) {
        if (requestedScope == null || requestedScope.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : requestedScope.entrySet()) {
            if (!entry.getValue().equals(bindingScope.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static IamCheckResult failure(AttributeState state) {
        return switch (state) {
            case STALE ->
                    new IamCheckResult(
                            false,
                            "attribute snapshot is stale",
                            "ATTRIBUTE_STALE",
                            AttributeState.STALE);
            case MISSING ->
                    new IamCheckResult(
                            false,
                            "attribute snapshot is missing",
                            "ATTRIBUTE_MISSING",
                            AttributeState.MISSING);
            case SOURCE_UNAVAILABLE ->
                    new IamCheckResult(
                            false,
                            "attribute source is unavailable",
                            "ATTRIBUTE_SOURCE_UNAVAILABLE",
                            AttributeState.SOURCE_UNAVAILABLE);
            default ->
                    new IamCheckResult(
                            false, "attribute snapshot is not fresh", "ATTRIBUTE_MISSING", state);
        };
    }

    private static AttributeSnapshot snapshot(
            String principal, Instant asOf, Instant recordedAsOf, Map<String, Object> body) {
        return new AttributeSnapshot(
                principal,
                asOf,
                recordedAsOf == null ? asOf : recordedAsOf,
                null,
                "caller",
                null,
                body);
    }

    private record Grant(String permission, Map<String, String> scope) {}

    private record Access(Set<String> roles, Set<String> permissions, List<SodFinding> findings) {}
}
