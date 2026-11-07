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
 * <p>违规先记录在 {@code config/archunit/architecture-allowlist.txt}，每行
 * {@code rule|origin -> target}；T3 逐个任务清空对应条目。allowlist 有内容不是“通过”，
 * 而是“已知债务可见”。</p>
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
    @DisplayName("biz 契约不反向依赖 iam 实现")
    void bizDoesNotDependOnIam() {
        assertNoDependencies(
                "biz-does-not-depend-on-iam",
                ModuleClassPath.classesOf("seedj-biz-starter"),
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.starter"),
                dependency -> dependency.getPackageName().startsWith("cn.byteforce.seedj.iam")
                                || dependency.getPackageName().startsWith("cn.byteforce.seedj.starter.iam"));
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
    @DisplayName("通用命令入口只暴露双时间轴默认路径，不依赖事件溯源 CommandAgent")
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
                clazz -> clazz.getPackageName().startsWith("cn.byteforce.seedj.server")
                        || clazz.getPackageName().startsWith("cn.byteforce.seedj.demo"),
                dependency -> dependency.getName()
                        .equals("cn.byteforce.seedj.domain.TemporalStore"));
    }

    private static void assertNoDependencies(
            String rule,
            List<JavaClass> classes,
            Predicate<JavaClass> origin,
            Predicate<JavaClass> forbiddenTarget) {
        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);

        Set<String> violations = new java.util.TreeSet<>();
        for (JavaClass clazz : classes) {
            if (!origin.test(clazz)) {
                continue;
            }
            for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (!forbiddenTarget.test(target)) {
                    continue;
                }
                String violation = clazz.getName() + " -> " + target.getName();
                if (!allowed.contains(rule + "|" + violation)) {
                    violations.add(violation);
                }
            }
        }

        assertThat(violations)
                .as(
                        "规则 %s 存在未登记违规；修掉依赖，或在 %s 记录 "
                                + "`%s|<origin> -> <target>`",
                        rule,
                        ALLOWLIST,
                        rule)
                .isEmpty();
    }
}
