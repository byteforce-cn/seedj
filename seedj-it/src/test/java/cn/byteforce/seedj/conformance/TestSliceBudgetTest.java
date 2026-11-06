package cn.byteforce.seedj.conformance;

import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.intValue;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 测试切片预算：不启动 Spring 的规则测试占比不得下降。
 *
 * <p>规则测试秒级完成、不依赖容器；{@code @SpringBootTest} 只用于 HTTP/安全/数据库契约。
 * 基线按「plain / (plain + spring)」比较，避免测试总数增长时指标被稀释。</p>
 */
class TestSliceBudgetTest {

    private static final Path ROOT = ModuleClassPath.root();

    @Test
    @DisplayName("不启 Spring 的测试占比不低于基线")
    void plainTestRatio() {
        List<Path> testFiles = new ArrayList<>();
        testFiles.addAll(CognitiveLoadBaseline.javaFiles(ROOT, "seedj-server/hr/src/test/java"));
        testFiles.addAll(CognitiveLoadBaseline.javaFiles(ROOT, "seedj-it/src/test/java"));
        testFiles.addAll(CognitiveLoadBaseline.javaFiles(ROOT, "core/src/test/java"));
        testFiles.addAll(
                CognitiveLoadBaseline.javaFiles(ROOT, "seedj-jdbc-postgresql/src/test/java"));

        int spring = 0;
        for (Path file : testFiles) {
            if (contains(file, "@SpringBootTest")) {
                spring++;
            }
        }
        int plain = testFiles.size() - spring;
        int springBaseline = intValue("tests.springTestClasses");
        int plainBaseline = intValue("tests.plainTestClasses");

        assertThat(plain * (springBaseline + plainBaseline))
                .as("plain/spring 测试比 %d/%d 低于基线 %d/%d；补充不启 Spring 的规则测试",
                        plain, spring, plainBaseline, springBaseline)
                .isGreaterThanOrEqualTo(plainBaseline * (plain + spring));
        if (plain > plainBaseline) {
            System.out.printf("cognitive-load: plain tests improved %d -> %d; tighten %s%n",
                    plainBaseline, plain, "tests.plainTestClasses");
        }
    }

    private static boolean contains(Path file, String needle) {
        try {
            return Files.readAllLines(file).stream().anyMatch(line -> line.contains(needle));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
