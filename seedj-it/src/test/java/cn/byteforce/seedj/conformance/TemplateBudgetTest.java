package cn.byteforce.seedj.conformance;

import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.assertWithin;
import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.javaFiles;
import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.lineCount;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HR 样板预算：文件数、行数、{@code @Bean} 与框架类型数不得超过基线。
 *
 * <p>这四项是「业务开发人员每天要读多少东西」的直接代理指标。基线只在指标下降时收紧；
 * 超过基线必须先在代码里降低，而不是改基线。</p>
 */
class TemplateBudgetTest {

    private static final Path ROOT = ModuleClassPath.root();

    private static final List<Path> HR_MAIN =
            javaFiles(ROOT, "seedj-server/hr/src/main/java");

    @Test
    @DisplayName("HR 主源码文件数不超过基线")
    void fileCount() {
        assertWithin("hr.mainFiles", "hr.mainFiles", HR_MAIN.size());
    }

    @Test
    @DisplayName("HR 主源码行数不超过基线")
    void lineBudget() {
        assertWithin("hr.mainLines", "hr.mainLines", (int) lineCount(HR_MAIN));
    }

    @Test
    @DisplayName("HR 装配 @Bean 数不超过基线")
    void beanCount() {
        assertWithin("hr.beans", "hr.beans",
                (int) CognitiveLoadBaseline.textOccurrences(HR_MAIN, "@Bean"));
    }

    @Test
    @DisplayName("HR 引用的框架类型数不超过基线（全局去重）")
    void frameworkTypes() {
        java.util.Set<String> distinct = new java.util.TreeSet<>();
        for (Path file : HR_MAIN) {
            try {
                java.nio.file.Files.readAllLines(file).stream()
                        .filter(line -> line.startsWith("import cn.byteforce.seedj."))
                        .forEach(distinct::add);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }
        assertWithin("hr.frameworkTypes", "hr.frameworkTypes", distinct.size());
    }
}
