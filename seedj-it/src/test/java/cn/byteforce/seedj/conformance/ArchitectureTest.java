package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构边界门禁：把 spec D7 的依赖方向变成可执行断言。
 *
 * <p>违规先记录在 {@code config/archunit/architecture-allowlist.txt}，每行 {@code rule|origin -> target}；T3
 * 逐个任务清空对应条目。allowlist 有内容不是“通过”， 而是“已知债务可见”。
 */
class ArchitectureTest {

    private static final String ALLOWLIST = "config/archunit/architecture-allowlist.txt";

    @Test
    @DisplayName("core 不依赖 Spring / JDBC / javax.sql")
    void coreIsFrameworkFree() {
        assertNoDependencies(
                "core-is-framework-free",
                ModuleClassPath.classesOf("core"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.core"),
                dependency ->
                        dependency.getPackageName().startsWith("org.springframework")
                                || dependency.getPackageName().startsWith("java.sql")
                                || dependency.getPackageName().startsWith("javax.sql"));
    }

    @Test
    @DisplayName("core.ai 不依赖 Spring / JDBC")
    void aiContractsAreFrameworkFree() {
        assertNoDependencies(
                "ai-contracts-framework-free",
                ModuleClassPath.classesOf("seedj-ai-contract"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.ai"),
                dependency ->
                        dependency.getPackageName().startsWith("org.springframework")
                                || dependency.getPackageName().startsWith("java.sql")
                                || dependency.getPackageName().startsWith("javax.sql"));
    }

    @Test
    @DisplayName("biz 契约不反向依赖 ai 实现")
    void bizDoesNotDependOnAi() {
        assertNoDependencies(
                "biz-does-not-depend-on-ai",
                ModuleClassPath.classesOf("seedj-biz-starter"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.starter"),
                dependency ->
                        dependency.getPackageName().startsWith("cn.byteforce.seedj.starter.ai"));
    }

    @Test
    @DisplayName("IAM 纯逻辑引擎不依赖 Spring / JDBC")
    void iamEngineIsFrameworkFree() {
        assertNoDependencies(
                "iam-engine-is-framework-free",
                ModuleClassPath.classesOf("seedj-iam-engine"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.iam.engine"),
                dependency ->
                        dependency.getPackageName().startsWith("org.springframework")
                                || dependency.getPackageName().startsWith("java.sql")
                                || dependency.getPackageName().startsWith("javax.sql"));
    }

    @Test
    @DisplayName("biz 契约不反向依赖 iam 实现")
    void bizDoesNotDependOnIam() {
        assertNoDependencies(
                "biz-does-not-depend-on-iam",
                ModuleClassPath.classesOf("seedj-biz-starter"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.starter"),
                dependency ->
                        dependency.getPackageName().startsWith("cn.byteforce.seedj.iam")
                                || dependency
                                        .getPackageName()
                                        .startsWith("cn.byteforce.seedj.starter.iam"));
    }

    @Test
    @DisplayName("coord 实现不依赖 JDBC / starter / server")
    void coordDoesNotDependOnBusinessStores() {
        assertNoDependencies(
                "coord-does-not-depend-on-business-stores",
                ModuleClassPath.classesOf("seedj-infra-coord"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.infra.coord"),
                dependency -> {
                    String pkg = dependency.getPackageName();
                    return pkg.startsWith("cn.byteforce.seedj.infra.jdbc")
                            || pkg.startsWith("cn.byteforce.seedj.starter")
                            || pkg.startsWith("cn.byteforce.seedj.server");
                });
    }

    @Test
    @DisplayName("workflow coord 适配器不依赖 JDBC / local / starter / server")
    void workflowCoordDoesNotDependOnBusinessStores() {
        assertNoDependencies(
                "workflow-coord-does-not-depend-on-business-stores",
                ModuleClassPath.classesOf("seedj-workflow-coord"),
                clazz ->
                        clazz.getPackageName()
                                .startsWith("cn.byteforce.seedj.infra.coord.workflow"),
                dependency -> {
                    String pkg = dependency.getPackageName();
                    return pkg.startsWith("cn.byteforce.seedj.infra.jdbc")
                            || pkg.startsWith("cn.byteforce.seedj.infra.local")
                            || pkg.startsWith("cn.byteforce.seedj.starter")
                            || pkg.startsWith("cn.byteforce.seedj.server");
                });
    }

    @Test
    @DisplayName("starter 不依赖具体适配器（换存储的 seam 必须真实存在）")
    void startersDoNotDependOnAdapters() {
        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);
        Set<String> violations = new java.util.TreeSet<>();
        for (String module :
                List.of(
                        "seedj-biz-starter",
                        "seedj-iam-starter",
                        "seedj-im-starter",
                        "seedj-ai-starter")) {
            List<JavaClass> origins =
                    ModuleClassPath.classesOf(module).stream()
                            .filter(
                                    clazz ->
                                            clazz.getPackageName()
                                                    .startsWith("cn.byteforce.seedj.starter"))
                            .toList();
            violations.addAll(
                    violations(
                            origins,
                            dependency ->
                                    dependency
                                            .getPackageName()
                                            .startsWith("cn.byteforce.seedj.infra")));
        }
        violations.removeIf(
                violation -> allowed.contains("starter-does-not-depend-on-adapters|" + violation));
        assertThat(violations)
                .as(
                        "规则 starter-does-not-depend-on-adapters 存在未登记违规；修掉依赖，或在 %s 记录 "
                                + "`starter-does-not-depend-on-adapters|<origin> -> <target>`",
                        ALLOWLIST)
                .isEmpty();
    }

    @Test
    @DisplayName("本地内存实现不依赖 JDBC 实现")
    void infraLocalDoesNotDependOnJdbc() {
        assertNoDependencies(
                "infra-local-does-not-depend-on-jdbc",
                ModuleClassPath.classesOf("seedj-infra-local"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj"),
                dependency ->
                        dependency.getPackageName().startsWith("cn.byteforce.seedj")
                                && dependency.getPackageName().contains(".jdbc"));
    }

    @Test
    @DisplayName("通用命令入口不直接依赖事件溯源实现；两条路径都经 CommandCatalog")
    void genericCommandEntrypointDoesNotUseEventSourcedAgent() {
        assertNoDependencies(
                "generic-command-entrypoint-uses-temporal-path",
                ModuleClassPath.classesOf("seedj-biz-starter"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.starter.cqrs.web"),
                dependency -> dependency.getSimpleName().equals("CommandAgent"));
    }

    @Test
    @DisplayName("业务包不得直接依赖 TemporalStore（读只经 ReadGateway）")
    void businessCodeDoesNotUseTemporalStore() {
        assertNoDependencies(
                "business-code-uses-read-gateway",
                ModuleClassPath.allClasses(),
                clazz ->
                        clazz.getPackageName().startsWith("cn.byteforce.seedj.server")
                                || clazz.getPackageName().startsWith("cn.byteforce.seedj.demo"),
                dependency ->
                        dependency.getName().equals("cn.byteforce.seedj.domain.TemporalStore"));
    }

    private static void assertNoDependencies(
            String rule,
            List<JavaClass> classes,
            Predicate<JavaClass> origin,
            Predicate<JavaClass> forbiddenTarget) {
        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);

        int matched = 0;
        Set<String> violations = new java.util.TreeSet<>();
        for (JavaClass clazz : classes) {
            if (!origin.test(clazz)) {
                continue;
            }
            matched++;
            for (String violation : violations(clazz, forbiddenTarget)) {
                if (!allowed.contains(rule + "|" + violation)) {
                    violations.add(violation);
                }
            }
        }

        assertThat(matched).as("规则 %s 的 origin 谓词未命中任何类（规则空跑，不能算通过）", rule).isPositive();
        assertThat(violations)
                .as(
                        "规则 %s 存在未登记违规；修掉依赖，或在 %s 记录 " + "`%s|<origin> -> <target>`",
                        rule, ALLOWLIST, rule)
                .isEmpty();
    }

    /** 返回一组类里命中的违规，格式 {@code origin -> target}。 */
    private static Set<String> violations(
            List<JavaClass> classes, Predicate<JavaClass> forbiddenTarget) {
        Set<String> violations = new java.util.TreeSet<>();
        for (JavaClass clazz : classes) {
            violations.addAll(violations(clazz, forbiddenTarget));
        }
        return violations;
    }

    private static Set<String> violations(JavaClass clazz, Predicate<JavaClass> forbiddenTarget) {
        Set<String> violations = new java.util.TreeSet<>();
        for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
            JavaClass target = dependency.getTargetClass();
            if (forbiddenTarget.test(target)) {
                violations.add(clazz.getName() + " -> " + target.getName());
            }
        }
        return violations;
    }
}
