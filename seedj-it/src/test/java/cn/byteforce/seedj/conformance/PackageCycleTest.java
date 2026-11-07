package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 包环门禁：{@code cn.byteforce.seedj} 下的包之间不得存在循环依赖（parent↔spi 白名单除外）。
 *
 * <p>用强连通分量（Tarjan）而不是 ArchUnit 的 slices 报告：SCC 是确定性的，可以把一个环 作为一行 allowlist
 * 记录、逐个偿还，而不是靠匹配异常消息。allowlist 每行是环内包名排序后 以逗号连接，文件为 {@code
 * config/archunit/package-cycle-allowlist.txt}。
 */
class PackageCycleTest {

    private static final String ALLOWLIST = "config/archunit/package-cycle-allowlist.txt";

    @Test
    @DisplayName("顶层包之间无未登记环")
    void seedjPackagesAreAcyclic() {
        List<JavaClass> classes = ModuleClassPath.allClasses();
        Map<String, Set<String>> graph = new HashMap<>();
        for (JavaClass clazz : classes) {
            String from = seedjPackage(clazz.getPackageName());
            if (from == null) {
                continue;
            }
            graph.computeIfAbsent(from, key -> new TreeSet<>());
            for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                String to = seedjPackage(dependency.getTargetClass().getPackageName());
                if (to == null || to.equals(from)) {
                    continue;
                }
                graph.get(from).add(to);
            }
        }

        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);
        List<String> violations = new ArrayList<>();
        for (List<String> component : stronglyConnectedComponents(graph)) {
            if (component.size() < 2) {
                continue;
            }
            String rendered = String.join(",", component);
            if (!allowed.contains(rendered)) {
                violations.add(rendered);
            }
        }
        violations.sort(null);

        assertThat(violations).as("存在未登记的包级环；拆分或反转依赖，或在 %s 记录环内包名（逗号分隔）", ALLOWLIST).isEmpty();
    }

    private static String seedjPackage(String packageName) {
        return packageName.startsWith("cn.byteforce.seedj.") ? packageName : null;
    }

    /** Tarjan SCC，返回每个分量内包名排序后的列表；整体顺序按首元素排序以保证稳定。 */
    private static List<List<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<List<String>> components = new ArrayList<>();
        int[] counter = {0};

        for (String node : new TreeSet<>(graph.keySet())) {
            if (!index.containsKey(node)) {
                strongConnect(node, graph, index, lowLink, stack, onStack, components, counter);
            }
        }
        components.sort((left, right) -> left.get(0).compareTo(right.get(0)));
        return components;
    }

    private static void strongConnect(
            String node,
            Map<String, Set<String>> graph,
            Map<String, Integer> index,
            Map<String, Integer> lowLink,
            Deque<String> stack,
            Set<String> onStack,
            List<List<String>> components,
            int[] counter) {
        index.put(node, counter[0]);
        lowLink.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);

        for (String next : graph.getOrDefault(node, Set.of())) {
            if (!index.containsKey(next)) {
                strongConnect(next, graph, index, lowLink, stack, onStack, components, counter);
                lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(next)));
            } else if (onStack.contains(next)) {
                lowLink.put(node, Math.min(lowLink.get(node), index.get(next)));
            }
        }

        if (lowLink.get(node).equals(index.get(node))) {
            List<String> component = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            component.sort(null);
            components.add(component);
        }
    }
}
