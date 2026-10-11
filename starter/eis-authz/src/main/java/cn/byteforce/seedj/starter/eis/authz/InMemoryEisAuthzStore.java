package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzBinding;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzPrincipal;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzProxy;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzRole;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzSodRule;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 内存授权数据存储：测试与小规模部署的便利实现（主数据源始终是域服务存储）。
 *
 * <p>解析语义与 JDBC 实现一致（ID 或 key 引用、全时间行返回、引擎侧时间过滤）；
 * 缺省携带 04 契约的三条 SoD 规则。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryEisAuthzStore implements EisAuthzStore {

    private final List<EisAuthzPrincipal> principals;

    private final List<EisAuthzRole> roles;

    private final List<EisAuthzBinding> bindings;

    private final List<EisAuthzProxy> proxies;

    private final List<EisAuthzSodRule> sodRules;

    private InMemoryEisAuthzStore(Builder builder) {
        this.principals = List.copyOf(builder.principals);
        this.roles = List.copyOf(builder.roles);
        this.bindings = List.copyOf(builder.bindings);
        this.proxies = List.copyOf(builder.proxies);
        this.sodRules = builder.sodRules == null ? EisAuthzSodRules.contractDefaults() : List.copyOf(builder.sodRules);
    }

    /**
     * 创建构造器。
     *
     * @return 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<EisAuthzPrincipal> findPrincipal(String ref) {
        return principals.stream()
                .filter(principal -> principal.getId().toString().equals(ref)
                        || (principal.getKey() != null && principal.getKey().equals(ref)))
                .findFirst();
    }

    @Override
    public List<EisAuthzRole> findRoles(String ref) {
        List<EisAuthzRole> result = new ArrayList<>();
        for (EisAuthzRole role : roles) {
            if (role.getId().toString().equals(ref) || (role.getKey() != null && role.getKey().equals(ref))) {
                result.add(role);
            }
        }
        return result;
    }

    @Override
    public List<EisAuthzBinding> findBindings(UUID principalId) {
        List<EisAuthzBinding> result = new ArrayList<>();
        for (EisAuthzBinding binding : bindings) {
            if (binding.getPrincipal().equals(principalId)) {
                result.add(binding);
            }
        }
        return result;
    }

    @Override
    public List<EisAuthzProxy> findProxies(UUID agentId) {
        List<EisAuthzProxy> result = new ArrayList<>();
        for (EisAuthzProxy proxy : proxies) {
            if (proxy.getAgent().equals(agentId)) {
                result.add(proxy);
            }
        }
        return result;
    }

    @Override
    public List<EisAuthzSodRule> sodRules() {
        return sodRules;
    }

    /**
     * 存储构造器。
     */
    public static final class Builder {

        private final List<EisAuthzPrincipal> principals = new ArrayList<>();

        private final List<EisAuthzRole> roles = new ArrayList<>();

        private final List<EisAuthzBinding> bindings = new ArrayList<>();

        private final List<EisAuthzProxy> proxies = new ArrayList<>();

        private List<EisAuthzSodRule> sodRules;

        /**
         * 追加主体。
         *
         * @param principal 主体
         * @return 本构造器
         */
        public Builder principal(EisAuthzPrincipal principal) {
            principals.add(principal);
            return this;
        }

        /**
         * 追加角色。
         *
         * @param role 角色
         * @return 本构造器
         */
        public Builder role(EisAuthzRole role) {
            roles.add(role);
            return this;
        }

        /**
         * 追加绑定。
         *
         * @param binding 绑定
         * @return 本构造器
         */
        public Builder binding(EisAuthzBinding binding) {
            bindings.add(binding);
            return this;
        }

        /**
         * 追加代理。
         *
         * @param proxy 代理
         * @return 本构造器
         */
        public Builder proxy(EisAuthzProxy proxy) {
            proxies.add(proxy);
            return this;
        }

        /**
         * 覆盖 SoD 规则（缺省为契约三条）。
         *
         * @param rules 规则列表
         * @return 本构造器
         */
        public Builder sodRules(List<EisAuthzSodRule> rules) {
            this.sodRules = rules;
            return this;
        }

        /**
         * 构建。
         *
         * @return 存储
         */
        public InMemoryEisAuthzStore build() {
            return new InMemoryEisAuthzStore(this);
        }
    }

    /**
     * 便捷索引：ID → 名称（诊断用）。
     *
     * @return 映射
     */
    public Map<String, String> describe() {
        Map<String, String> map = new LinkedHashMap<>();
        for (EisAuthzPrincipal principal : principals) {
            map.put("principal:" + principal.getId(), String.valueOf(principal.getKey()));
        }
        for (EisAuthzRole role : roles) {
            map.put("role:" + role.getId(), String.valueOf(role.getKey()));
        }
        return map;
    }
}
