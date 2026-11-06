package cn.byteforce.seedj.conformance;

import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.assertWithin;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 概念预算：写路径、schema 与 public API 面的数量不得增长。
 *
 * <p>认知负担主要来自「同一个问题有几套答案」。写路径只允许两套（双时间轴默认 +
 * 事件溯源 advanced），schema 只允许一套，public 类型总量只能下降。</p>
 */
class ConceptBudgetTest {

    private static final Path ROOT = ModuleClassPath.root();

    @Test
    @DisplayName("写路径运行时保持两套：CommandRuntime + CommandAgent")
    void writeRuntimes() {
        int count = 0;
        for (String module : ModuleClassPath.MODULES) {
            for (JavaClass clazz : ModuleClassPath.classesOf(module)) {
                if (clazz.getName().equals("cn.byteforce.seedj.cqrs.CommandRuntime")
                        || clazz.isAssignableTo("cn.byteforce.seedj.cqrs.CommandAgent")) {
                    count++;
                }
            }
        }
        assertWithin("write path runtimes", "concepts.writeRuntimes", count);
    }

    @Test
    @DisplayName("schema 类型只有 ObjectSchema 一套")
    void schemaTypes() {
        Set<String> names = new TreeSet<>();
        for (String module : ModuleClassPath.MODULES) {
            for (JavaClass clazz : ModuleClassPath.classesOf(module)) {
                if (clazz.getSimpleName().equals("ObjectSchema")) {
                    names.add(clazz.getName());
                }
            }
        }
        assertThat(names).containsExactly("cn.byteforce.seedj.domain.ObjectSchema");
        assertWithin("schema types", "concepts.schemaTypes", names.size());
    }

    @Test
    @DisplayName("public API 类型总量不超过基线")
    void publicApiCap() {
        Path allowlist = ROOT.resolve("config/public-api.txt");
        long types;
        try {
            types = Files.readAllLines(allowlist).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .count();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        assertWithin("api.publicTypes", "api.publicTypes", (int) types);
    }
}
