package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模块自述门禁：每个模块至少有一个 {@code package-info.java}，说明职责与依赖方向。
 *
 * <p>模块边界不能只靠 POM 与 ArchUnit 表达；新加入的人需要在包级别看到“这个模块负责什么、 允许依赖谁、禁止依赖谁”。缺少自述与自述缺少关键词都算失败。
 */
class PackageInfoTest {

    private static final List<String> KEYWORDS = List.of("职责", "依赖方向");

    @Test
    @DisplayName("每个模块都有 package-info，且包含职责与依赖方向")
    void everyModuleDeclaresItsContract() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : ModuleClassPath.MODULES) {
            Path sourceRoot = ModuleClassPath.root().resolve(module).resolve("src/main/java");
            List<Path> packageInfos;
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                packageInfos =
                        files.filter(Files::isRegularFile)
                                .filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .equals("package-info.java"))
                                .toList();
            }
            if (packageInfos.isEmpty()) {
                violations.add(module + ": 缺少 package-info.java");
                continue;
            }
            boolean documented = packageInfos.stream().anyMatch(PackageInfoTest::documentsBoundary);
            if (!documented) {
                violations.add(module + ": package-info 未同时包含关键词 " + KEYWORDS);
            }
        }
        assertThat(violations).as("模块自述缺失；每个模块至少一个 package-info 说明职责与依赖方向").isEmpty();
    }

    private static boolean documentsBoundary(Path path) {
        try {
            String text = Files.readString(path);
            return KEYWORDS.stream().allMatch(text::contains);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
