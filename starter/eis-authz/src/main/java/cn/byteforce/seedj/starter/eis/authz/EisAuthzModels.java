package cn.byteforce.seedj.starter.eis.authz;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * EIS 授权解析模型与存储 SPI（04 契约的持久化面）。
 *
 * <p>{@link EisAuthzStore} 是引擎与数据来源的解耦点：IMS 实现读取 `iam_*` 镜像表；
 * 测试与小规模部署可用 {@link InMemoryEisAuthzStore}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzModels {

    private EisAuthzModels() {
    }

    /**
     * 主体（person / service）。
     */
    public static final class EisAuthzPrincipal extends EisAuthzFact {

        private final UUID id;

        private final String type;

        private final String key;

        /**
         * 构造主体。
         *
         * @param id           主体 ID
         * @param type         类型（person 等）
         * @param key          业务键（如 username）
         * @param validFrom    业务生效时间
         * @param validTo      业务失效时间
         * @param recordedAt   系统记录时间
         * @param supersededAt 系统替代时间
         */
        public EisAuthzPrincipal(UUID id, String type, String key, Instant validFrom, Instant validTo,
                                 Instant recordedAt, Instant supersededAt) {
            super(validFrom, validTo, recordedAt, supersededAt);
            this.id = id;
            this.type = type;
            this.key = key;
        }

        /**
         * 获取主体 ID。
         *
         * @return 主体 ID
         */
        public UUID getId() {
            return id;
        }

        /**
         * 获取类型。
         *
         * @return 类型
         */
        public String getType() {
            return type;
        }

        /**
         * 获取业务键。
         *
         * @return 业务键；可空
         */
        public String getKey() {
            return key;
        }
    }

    /**
     * 角色（权限集合）。
     */
    public static final class EisAuthzRole extends EisAuthzFact {

        private final UUID id;

        private final String key;

        private final List<String> permissions;

        /**
         * 构造角色。
         *
         * @param id          角色 ID
         * @param key         角色键
         * @param permissions 权限串列表
         * @param validFrom   业务生效时间
         * @param validTo     业务失效时间
         * @param recordedAt  系统记录时间
         * @param supersededAt 系统替代时间
         */
        public EisAuthzRole(UUID id, String key, List<String> permissions, Instant validFrom, Instant validTo,
                            Instant recordedAt, Instant supersededAt) {
            super(validFrom, validTo, recordedAt, supersededAt);
            this.id = id;
            this.key = key;
            this.permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }

        /**
         * 获取角色 ID。
         *
         * @return 角色 ID
         */
        public UUID getId() {
            return id;
        }

        /**
         * 获取角色键。
         *
         * @return 角色键；可空
         */
        public String getKey() {
            return key;
        }

        /**
         * 获取权限串列表。
         *
         * @return 权限串列表
         */
        public List<String> getPermissions() {
            return permissions;
        }
    }

    /**
     * 绑定（主体 × 角色 × 作用域）。
     */
    public static final class EisAuthzBinding extends EisAuthzFact {

        private final UUID id;

        private final UUID principal;

        private final String roleRef;

        private final JsonNode scope;

        /**
         * 构造绑定。
         *
         * @param id           绑定 ID
         * @param principal    主体 ID
         * @param roleRef      角色引用（ID 字符串或 key）
         * @param scope        作用域（JSON 对象；可空）
         * @param validFrom    业务生效时间
         * @param validTo      业务失效时间
         * @param recordedAt   系统记录时间
         * @param supersededAt 系统替代时间
         */
        public EisAuthzBinding(UUID id, UUID principal, String roleRef, JsonNode scope, Instant validFrom,
                               Instant validTo, Instant recordedAt, Instant supersededAt) {
            super(validFrom, validTo, recordedAt, supersededAt);
            this.id = id;
            this.principal = principal;
            this.roleRef = roleRef;
            this.scope = scope;
        }

        /**
         * 获取绑定 ID。
         *
         * @return 绑定 ID
         */
        public UUID getId() {
            return id;
        }

        /**
         * 获取主体 ID。
         *
         * @return 主体 ID
         */
        public UUID getPrincipal() {
            return principal;
        }

        /**
         * 获取角色引用。
         *
         * @return 角色引用
         */
        public String getRoleRef() {
            return roleRef;
        }

        /**
         * 获取作用域。
         *
         * @return 作用域 JSON；可空
         */
        public JsonNode getScope() {
            return scope;
        }
    }

    /**
     * 代理（agent 在代理范围内行使被代理人的角色）。
     */
    public static final class EisAuthzProxy extends EisAuthzFact {

        private final UUID id;

        private final UUID principal;

        private final UUID agent;

        private final JsonNode scope;

        /**
         * 构造代理。
         *
         * @param id           代理记录 ID
         * @param principal    被代理人
         * @param agent        代理人
         * @param scope        代理范围（JSON 对象；可空）
         * @param validFrom    业务生效时间
         * @param validTo      业务失效时间
         * @param recordedAt   系统记录时间
         * @param supersededAt 系统替代时间
         */
        public EisAuthzProxy(UUID id, UUID principal, UUID agent, JsonNode scope, Instant validFrom,
                             Instant validTo, Instant recordedAt, Instant supersededAt) {
            super(validFrom, validTo, recordedAt, supersededAt);
            this.id = id;
            this.principal = principal;
            this.agent = agent;
            this.scope = scope;
        }

        /**
         * 获取代理记录 ID。
         *
         * @return 记录 ID
         */
        public UUID getId() {
            return id;
        }

        /**
         * 获取被代理人。
         *
         * @return 被代理人 ID
         */
        public UUID getPrincipal() {
            return principal;
        }

        /**
         * 获取代理人。
         *
         * @return 代理人 ID
         */
        public UUID getAgent() {
            return agent;
        }

        /**
         * 获取代理范围。
         *
         * @return 范围 JSON；可空
         */
        public JsonNode getScope() {
            return scope;
        }
    }

    /**
     * SoD 规则（04 契约 {@code sod.rules.yaml} 的运行时形态）。
     */
    public static final class EisAuthzSodRule {

        private final String id;

        private final String name;

        private final List<String> conflictingPermissions;

        private final String scope;

        private final String enforcement;

        /**
         * 构造 SoD 规则。
         *
         * @param id                     规则 ID（SOD-xxx）
         * @param name                   名称
         * @param conflictingPermissions 冲突权限
         * @param scope                  作用域键（same_department 等；可空）
         * @param enforcement            prevent / warn
         */
        public EisAuthzSodRule(String id, String name, List<String> conflictingPermissions, String scope,
                               String enforcement) {
            this.id = id;
            this.name = name;
            this.conflictingPermissions =
                    conflictingPermissions == null ? List.of() : List.copyOf(conflictingPermissions);
            this.scope = scope;
            this.enforcement = enforcement;
        }

        /**
         * 获取规则 ID。
         *
         * @return 规则 ID
         */
        public String getId() {
            return id;
        }

        /**
         * 获取名称。
         *
         * @return 名称
         */
        public String getName() {
            return name;
        }

        /**
         * 获取冲突权限。
         *
         * @return 冲突权限列表
         */
        public List<String> getConflictingPermissions() {
            return conflictingPermissions;
        }

        /**
         * 获取作用域键。
         *
         * @return 作用域键；可空
         */
        public String getScope() {
            return scope;
        }

        /**
         * 获取强制方式。
         *
         * @return prevent / warn
         */
        public String getEnforcement() {
            return enforcement;
        }
    }

    /**
     * 授权解析结果（{@code /resolve}）。
     */
    public static final class EisAuthzResolution {

        private final UUID principal;

        private final Instant asOf;

        private final List<String> roles;

        private final List<String> permissions;

        private final List<String> sodWarnings;

        /**
         * 构造解析结果。
         *
         * @param principal   主体 ID
         * @param asOf        业务时点
         * @param roles       角色键列表
         * @param permissions 权限串列表
         * @param sodWarnings warn 级 SoD 规则 ID 列表
         */
        public EisAuthzResolution(UUID principal, Instant asOf, List<String> roles, List<String> permissions,
                                  List<String> sodWarnings) {
            this.principal = principal;
            this.asOf = asOf;
            this.roles = roles == null ? List.of() : List.copyOf(roles);
            this.permissions = permissions == null ? List.of() : List.copyOf(permissions);
            this.sodWarnings = sodWarnings == null ? List.of() : List.copyOf(sodWarnings);
        }

        /**
         * 获取主体 ID。
         *
         * @return 主体 ID
         */
        public UUID getPrincipal() {
            return principal;
        }

        /**
         * 获取业务时点。
         *
         * @return 业务时点
         */
        public Instant getAsOf() {
            return asOf;
        }

        /**
         * 获取角色键列表。
         *
         * @return 角色键列表
         */
        public List<String> getRoles() {
            return roles;
        }

        /**
         * 获取权限串列表。
         *
         * @return 权限串列表
         */
        public List<String> getPermissions() {
            return permissions;
        }

        /**
         * 获取 SoD 告警。
         *
         * @return warn 级规则 ID 列表
         */
        public List<String> getSodWarnings() {
            return sodWarnings;
        }
    }

    /**
     * 权限判定结果（{@code /check}）。
     */
    public static final class EisAuthzDecision {

        private final boolean allowed;

        private final String reason;

        private final String reasonCode;

        private final List<String> warnings;

        private final EisAuthzAttributeState attributeState;

        /**
         * 构造判定结果（无属性状态）。
         *
         * @param allowed    是否允许
         * @param reason     人类可读理由
         * @param reasonCode ALLOWED / NO_PERMISSION / SOD-xxx
         * @param warnings   warn 级 SoD 规则 ID 列表
         */
        public EisAuthzDecision(boolean allowed, String reason, String reasonCode, List<String> warnings) {
            this(allowed, reason, reasonCode, warnings, null);
        }

        /**
         * 构造判定结果（含属性快照状态）。
         *
         * @param allowed        是否允许
         * @param reason         人类可读理由
         * @param reasonCode     ALLOWED / NO_PERMISSION / SOD-xxx / ATTRIBUTE_*
         * @param warnings       warn 级 SoD 规则 ID 列表
         * @param attributeState 属性快照状态（可空 = 本次判定不涉及属性）
         */
        public EisAuthzDecision(boolean allowed, String reason, String reasonCode, List<String> warnings,
                                EisAuthzAttributeState attributeState) {
            this.allowed = allowed;
            this.reason = reason;
            this.reasonCode = reasonCode;
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
            this.attributeState = attributeState;
        }

        /**
         * 是否允许。
         *
         * @return 允许为 {@code true}
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 获取理由。
         *
         * @return 理由
         */
        public String getReason() {
            return reason;
        }

        /**
         * 获取原因码。
         *
         * @return 原因码
         */
        public String getReasonCode() {
            return reasonCode;
        }

        /**
         * 获取 SoD 告警。
         *
         * @return warn 级规则 ID 列表
         */
        public List<String> getWarnings() {
            return warnings;
        }

        /**
         * 获取属性快照状态。
         *
         * @return fresh / stale / missing / source_unavailable / not_required；
         *         本次判定不涉及属性为 {@code null}
         */
        public EisAuthzAttributeState getAttributeState() {
            return attributeState;
        }
    }

    /**
     * 授权数据存储 SPI（实现按域：IMS 读取 iam_* 镜像表）。
     */
    public interface EisAuthzStore {

        /**
         * 按引用（ID 字符串或 key）查主体（当前版本）。
         *
         * @param ref 引用
         * @return 主体；不存在为空
         */
        java.util.Optional<EisAuthzPrincipal> findPrincipal(String ref);

        /**
         * 按引用（ID 字符串或 key）查角色的全部版本（含历史；引擎按 as_of 选命中版本）。
         *
         * @param ref 引用
         * @return 角色版本列表（可空）
         */
        List<EisAuthzRole> findRoles(String ref);

        /**
         * 查主体的全部绑定行（含历史；时间过滤由引擎完成）。
         *
         * @param principalId 主体 ID
         * @return 绑定列表
         */
        List<EisAuthzBinding> findBindings(UUID principalId);

        /**
         * 查代理人的全部代理行（含历史；时间过滤由引擎完成）。
         *
         * @param agentId 代理人 ID
         * @return 代理列表
         */
        List<EisAuthzProxy> findProxies(UUID agentId);

        /**
         * SoD 规则（04 契约 {@code sod.rules.yaml}）。
         *
         * @return 规则列表
         */
        List<EisAuthzSodRule> sodRules();
    }
}
