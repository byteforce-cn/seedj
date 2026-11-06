package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * split package 门禁：同一个 Java 包只能属于一个 Maven 模块。
 *
 * <p>跨模块同名包会让 JPMS、OSGi、类路径扫描与架构规则全部失去确定性。当前违规记录在
 * {@code config/archunit/split-package-allowlist.txt}，每行 {@code package|moduleA,moduleB}；
 * T3.2 清空。</p>
 */
class SplitPackageTest {

    private static final String ALLOWLIST = "config/archunit/split-package-allowlist.txt";

    @Test
    @DisplayName("任何 Java 包只属于一个模块")
    void packagesBelongToExactlyOneModule() {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (String module : ModuleClassPath.MODULES) {
            for (JavaClass clazz : ModuleClassPath.classesOf(module)) {
                owners.computeIfAbsent(clazz.getPackageName(), key -> new TreeSet<>()).add(module);
            }
        }

        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);
        List<String> violations = new ArrayList<>();
        owners.forEach(
                (packageName, modules) -> {
                    if (modules.size() < 2) {
                        return;
                    }
                    String rendered = packageName + "|" + String.join(",", modules);
                    if (!allowed.contains(rendered)) {
                        violations.add(rendered);
                    }
                });
        violations.sort(null);

        assertThat(violations)
                .as(
                        "存在未登记的跨模块 split package；移动实现包，或在 %s 记录 "
                                + "`package|moduleA,moduleB`",
                        ALLOWLIST)
                .isEmpty();
    }
}
