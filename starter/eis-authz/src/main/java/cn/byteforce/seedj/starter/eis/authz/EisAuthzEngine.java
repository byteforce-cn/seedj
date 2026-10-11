package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzBinding;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzPrincipal;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzProxy;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzRole;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzSodRule;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * EIS 授权解析引擎：<b>判定唯一权威</b>（coord Policy 不接入判定路径——评估点 13）。
 *
 * <p>语义与参考实现 {@code eis/tools/authz-resolver} 逐项对齐：</p>
 * <ul>
 *   <li><b>双时间轴</b>：{@code as_of} 业务轴 + 可选 {@code recorded_as_of} 系统轴，半开区间；</li>
 *   <li><b>代理展开</b>：agent 经代理记录行使被代理人角色（代理范围键逐项约束绑定作用域）；</li>
 *   <li><b>作用域过滤</b>：{@code /resolve} 的 scope 过滤——绑定作用域含该键且值不同 → 排除；全局授权不排除；</li>
 *   <li><b>SoD</b>：{@code prevent} → {@code /check} 拒绝（reason_code=SOD-xxx）；
 *       {@code warn} → 放行并在 {@code sod_warnings}/{@code warnings} 上报；
 *       作用域冲突判定按规则 scope 键（两权限绑定作用域该键相等、或双方均缺省）；</li>
 *   <li><b>原因码</b>：{@code ALLOWED} / {@code NO_PERMISSION} / SoD 规则 ID。</li>
 *   <li><b>属性条件（ADR-0004）</b>：条件显式声明主岗（{@code primary_position.*}）
 *       或任一岗位（{@code positions[*].*}）；缺失（{@code ATTRIBUTE_MISSING}）/
 *       过期（{@code ATTRIBUTE_STALE}）/ 属主不可达（{@code ATTRIBUTE_SOURCE_UNAVAILABLE}）/
 *       条件不匹配（{@code ATTRIBUTE_MISMATCH}）一律 fail-closed；无条件的判定行为不变。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzEngine implements EisAuthzOperations {

    private static final Map<String, String> SCOPE_KEYS = Map.of(
            "same_department", "department",
            "same_organization", "organization",
            "same_warehouse", "warehouse");

    private final EisAuthzStore store;

    private final EisAuthzAttributeSource attributeSource;

    /**
     * 构造引擎（无属性源；属性条件将 fail-closed 为 ATTRIBUTE_SOURCE_UNAVAILABLE）。
     *
     * @param store 授权数据存储
     */
    public EisAuthzEngine(EisAuthzStore store) {
        this(store, null);
    }

    /**
     * 构造引擎（含属性源）。
     *
     * @param store         授权数据存储
     * @param attributeSource 属性源（可空）
     */
    public EisAuthzEngine(EisAuthzStore store, EisAuthzAttributeSource attributeSource) {
        this.store = store;
        this.attributeSource = attributeSource;
    }

    @Override
    public EisAuthzResolution resolve(String principalRef, Instant asOf, Instant recordedAsOf,
                                      JsonNode scopeFilter) {
        EisAuthzPrincipal principal = principal(principalRef);
        List<String> roles = new ArrayList<>();
        List<String> permissions = new ArrayList<>();
        for (Grant grant : grants(principal.getId(), asOf, recordedAsOf)) {
            if (!scopeMatches(grant.scope, scopeFilter)) {
                continue;
            }
            String key = grant.role.getKey() != null ? grant.role.getKey() : grant.role.getId().toString();
            if (!roles.contains(key)) {
                roles.add(key);
            }
            for (String permission : grant.role.getPermissions()) {
                if (!permissions.contains(permission)) {
                    permissions.add(permission);
                }
            }
        }
        return new EisAuthzResolution(principal.getId(), asOf, roles, permissions,
                sodConflicts(principal.getId(), asOf, recordedAsOf, "warn"));
    }

    @Override
    public EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf) {
        EisAuthzPrincipal principal = principal(principalRef);
        List<Held> held = new ArrayList<>();
        for (Grant grant : grants(principal.getId(), asOf, recordedAsOf)) {
            for (String granted : grant.role.getPermissions()) {
                held.add(new Held(granted, grant.scope));
            }
        }
        boolean holdsPermission = false;
        for (Held item : held) {
            if (item.permission.equals(permission)) {
                holdsPermission = true;
                break;
            }
        }
        if (!holdsPermission) {
            return new EisAuthzDecision(false, "主体在 " + asOf + " 无权限 " + permission, "NO_PERMISSION",
                    List.of());
        }
        for (EisAuthzSodRule rule : store.sodRules()) {
            if (!rule.getConflictingPermissions().contains(permission)) {
                continue;
            }
            List<String> others = new ArrayList<>();
            for (Held item : held) {
                if (rule.getConflictingPermissions().contains(item.permission)
                        && !item.permission.equals(permission) && !others.contains(item.permission)) {
                    others.add(item.permission);
                }
            }
            if (others.isEmpty()) {
                continue;
            }
            String scopeKey = scopeKeyOf(rule);
            if (conflictsInScope(permission, others, held, scopeKey)
                    && "prevent".equals(rule.getEnforcement())) {
                return new EisAuthzDecision(false,
                        "违反职责分离 " + rule.getId() + "（" + rule.getName() + "）", rule.getId(), List.of());
            }
        }
        return new EisAuthzDecision(true, "允许", "ALLOWED",
                sodConflicts(principal.getId(), asOf, recordedAsOf, "warn"));
    }

    // ------------------------------------------------------------------
    // 授权展开
    // ------------------------------------------------------------------

    /**
     * 展开主体的有效授权集合（直接 + 代理），返回 role×scope。
     */
    private List<Grant> grants(java.util.UUID principalId, Instant asOf, Instant recordedAsOf) {
        List<Grant> out = new ArrayList<>();
        for (EisAuthzBinding binding : store.findBindings(principalId)) {
            if (!binding.inRange(asOf, recordedAsOf)) {
                continue;
            }
            roleOf(binding, asOf, recordedAsOf).ifPresent(role -> out.add(new Grant(role, binding.getScope())));
        }
        for (EisAuthzProxy proxy : store.findProxies(principalId)) {
            if (!proxy.inRange(asOf, recordedAsOf)) {
                continue;
            }
            for (EisAuthzBinding binding : store.findBindings(proxy.getPrincipal())) {
                if (!binding.inRange(asOf, recordedAsOf)) {
                    continue;
                }
                if (!proxyScopeSatisfied(proxy.getScope(), binding.getScope())) {
                    continue;
                }
                roleOf(binding, asOf, recordedAsOf)
                        .ifPresent(role -> out.add(new Grant(role, binding.getScope())));
            }
        }
        return out;
    }

    /**
     * 解析绑定的角色（在时点命中窗口内的版本）。
     */
    private Optional<EisAuthzRole> roleOf(EisAuthzBinding binding, Instant asOf, Instant recordedAsOf) {
        if (binding.getRoleRef() == null) {
            return Optional.empty();
        }
        for (EisAuthzRole role : store.findRoles(binding.getRoleRef())) {
            if (role.inRange(asOf, recordedAsOf)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /**
     * 代理范围约束：代理范围的每个键都须与绑定作用域一致（缺键视为不一致）。
     */
    private static boolean proxyScopeSatisfied(JsonNode proxyScope, JsonNode bindingScope) {
        if (proxyScope == null || !proxyScope.isObject()) {
            return true;
        }
        Iterator<String> names = proxyScope.propertyNames().iterator();
        while (names.hasNext()) {
            String key = names.next();
            JsonNode expected = proxyScope.get(key);
            JsonNode actual = scopeValue(bindingScope, key);
            if (actual == null || !actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 作用域过滤（{@code /resolve}）：绑定作用域含过滤键且值不同 → 不匹配；全局授权（空作用域）不受过滤。
     */
    private static boolean scopeMatches(JsonNode bindingScope, JsonNode filter) {
        if (filter == null || !filter.isObject() || filter.isEmpty()) {
            return true;
        }
        if (bindingScope == null || !bindingScope.isObject() || bindingScope.isEmpty()) {
            return true;
        }
        Iterator<String> names = filter.propertyNames().iterator();
        while (names.hasNext()) {
            String key = names.next();
            JsonNode actual = bindingScope.get(key);
            if (actual != null && !actual.equals(filter.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 作用域内冲突：请求权限与冲突权限的绑定作用域在规则键上相等（或双方均缺省）。
     */
    private static boolean conflictsInScope(String permission, List<String> others, List<Held> held,
                                            String scopeKey) {
        for (String other : others) {
            for (Held first : held) {
                if (!first.permission.equals(permission)) {
                    continue;
                }
                for (Held second : held) {
                    if (!second.permission.equals(other)) {
                        continue;
                    }
                    if (scopeKey == null) {
                        return true;
                    }
                    JsonNode va = scopeValue(first.scope, scopeKey);
                    JsonNode vb = scopeValue(second.scope, scopeKey);
                    if (va != null && va.equals(vb)) {
                        return true;
                    }
                    if (va == null && vb == null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 枚举指定强制方式下命中的 SoD 规则（warn 上报）。
     */
    private List<String> sodConflicts(java.util.UUID principalId, Instant asOf, Instant recordedAsOf,
                                      String mode) {
        List<Held> held = new ArrayList<>();
        for (Grant grant : grants(principalId, asOf, recordedAsOf)) {
            for (String permission : grant.role.getPermissions()) {
                held.add(new Held(permission, grant.scope));
            }
        }
        List<String> conflicts = new ArrayList<>();
        for (EisAuthzSodRule rule : store.sodRules()) {
            if (!mode.equals(rule.getEnforcement())) {
                continue;
            }
            List<String> present = new ArrayList<>();
            for (String permission : rule.getConflictingPermissions()) {
                for (Held item : held) {
                    if (item.permission.equals(permission) && !present.contains(permission)) {
                        present.add(permission);
                    }
                }
            }
            if (present.size() < 2) {
                continue;
            }
            String scopeKey = scopeKeyOf(rule);
            if (conflictsInScope(present.get(0), present.subList(1, present.size()), held, scopeKey)) {
                conflicts.add(rule.getId());
            }
        }
        return conflicts;
    }

    /**
     * 规则作用域键查表（null 安全；{@code Map.of} 不允许 null 键查询）。
     */
    private static String scopeKeyOf(EisAuthzSodRule rule) {
        return rule.getScope() == null ? null : SCOPE_KEYS.get(rule.getScope());
    }

    // ------------------------------------------------------------------
    // 属性条件（ADR-0004 / ATR-001..007）
    // ------------------------------------------------------------------

    @Override
    public EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf,
                                  java.util.List<EisAuthzAttributeCondition> conditions,
                                  EisAuthzAttributeSnapshot providedSnapshot) {
        EisAuthzDecision base = check(principalRef, permission, asOf, recordedAsOf);
        boolean conditioned = conditions != null && !conditions.isEmpty();
        if (!base.isAllowed()) {
            return conditioned ? withState(base, EisAuthzAttributeState.NOT_REQUIRED) : base;
        }
        if (!conditioned) {
            return providedSnapshot == null ? base : withState(base, EisAuthzAttributeState.NOT_REQUIRED);
        }
        EisAuthzAttributeSnapshot snapshot = providedSnapshot;
        if (snapshot == null) {
            if (attributeSource == null) {
                return attributeDenied(EisAuthzAttributeState.SOURCE_UNAVAILABLE, "ATTRIBUTE_SOURCE_UNAVAILABLE",
                        "属性源未配置：无法为依赖属性的条件拉取快照", base.getWarnings());
            }
            try {
                snapshot = attributeSource.snapshot(principalRef, asOf, recordedAsOf);
            } catch (EisAuthzAttributeSourceException ex) {
                EisAuthzAttributeState state = "ATTRIBUTE_MISSING".equals(ex.getReasonCode())
                        ? EisAuthzAttributeState.MISSING : EisAuthzAttributeState.SOURCE_UNAVAILABLE;
                return attributeDenied(state, ex.getReasonCode(), ex.getMessage(), base.getWarnings());
            } catch (RuntimeException ex) {
                return attributeDenied(EisAuthzAttributeState.SOURCE_UNAVAILABLE, "ATTRIBUTE_SOURCE_UNAVAILABLE",
                        "属性源不可用: " + ex.getMessage(), base.getWarnings());
            }
        }
        EisAuthzAttributeState state = snapshot.getState();
        if (state == EisAuthzAttributeState.STALE) {
            return attributeDenied(state, "ATTRIBUTE_STALE",
                    "属性快照已过期（as_of=" + asOf + "）", base.getWarnings());
        }
        if (state == EisAuthzAttributeState.MISSING) {
            return attributeDenied(state, "ATTRIBUTE_MISSING", "主体在 as_of 无属性快照", base.getWarnings());
        }
        if (state == EisAuthzAttributeState.SOURCE_UNAVAILABLE) {
            return attributeDenied(state, "ATTRIBUTE_SOURCE_UNAVAILABLE", "属性属主不可达", base.getWarnings());
        }
        if (state == null || state == EisAuthzAttributeState.NOT_REQUIRED) {
            // 无状态/未声明不构成可求值证据 → fail-closed
            return attributeDenied(EisAuthzAttributeState.MISSING, "ATTRIBUTE_MISSING",
                    "属性快照缺少可用状态", base.getWarnings());
        }
        for (EisAuthzAttributeCondition condition : conditions) {
            if (!snapshot.getAttributes().satisfies(condition)) {
                // ATR-007：只回传未满足的条件键，不回传属性值
                return attributeDenied(EisAuthzAttributeState.FRESH, "ATTRIBUTE_MISMATCH",
                        "属性条件不满足: " + condition.getKey(), base.getWarnings());
            }
        }
        return new EisAuthzDecision(true, base.getReason(), base.getReasonCode(), base.getWarnings(), state);
    }

    private static EisAuthzDecision withState(EisAuthzDecision decision, EisAuthzAttributeState state) {
        return new EisAuthzDecision(decision.isAllowed(), decision.getReason(), decision.getReasonCode(),
                decision.getWarnings(), state);
    }

    private static EisAuthzDecision attributeDenied(EisAuthzAttributeState state, String reasonCode, String reason,
                                                    java.util.List<String> warnings) {
        return new EisAuthzDecision(false, reason, reasonCode, warnings, state);
    }

    private EisAuthzPrincipal principal(String ref) {
        return store.findPrincipal(ref).orElseThrow(() -> new EisAuthzUnknownPrincipalException(ref));
    }

    private static JsonNode scopeValue(JsonNode scope, String key) {
        if (scope == null || !scope.isObject()) {
            return null;
        }
        return scope.get(key);
    }

    /**
     * 展开后的授权（role × scope）。
     */
    private static final class Grant {

        private final EisAuthzRole role;

        private final JsonNode scope;

        private Grant(EisAuthzRole role, JsonNode scope) {
            this.role = role;
            this.scope = scope;
        }
    }

    /**
     * 已持有权限（permission × scope）。
     */
    private static final class Held {

        private final String permission;

        private final JsonNode scope;

        private Held(String permission, JsonNode scope) {
            this.permission = permission;
            this.scope = scope;
        }
    }

    /**
     * 诊断辅助（测试与运维）：规则作用域键映射。
     *
     * @return 不可变映射
     */
    public static Map<String, String> scopeKeys() {
        return new LinkedHashMap<>(SCOPE_KEYS);
    }
}
