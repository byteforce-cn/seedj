package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文件大小门禁：主源码文件不得超过 600 行。
 *
 * <p>大文件通常意味着多个职责挤在一起，也让评审与上下文理解失效。allowlist 只允许
 * 自动生成代码或正在拆分中的文件，记录在 {@code config/archunit/file-size-allowlist.txt}，
 * 每行一个相对仓库根的路径；T3.5 清空。</p>
 */
class FileSizeGuardTest {

    private static final int MAX_LINES = 600;

    private static final String ALLOWLIST = "config/archunit/file-size-allowlist.txt";

    @Test
    @DisplayName("主源码文件不超过 600 行")
    void mainSourcesStaySmall() throws IOException {
        Path root = ModuleClassPath.root();
        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);
        List<String> violations = new ArrayList<>();

        for (String module : ModuleClassPath.MODULES) {
            Path sourceRoot = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    int lines = countLines(file);
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (lines > MAX_LINES && !allowed.contains(relative)) {
                        violations.add(relative + " (" + lines + " lines)");
                    }
                }
            }
        }
        violations.sort(null);

        assertThat(violations)
                .as("主源码超过 %d 行；拆分职责，或在 %s 登记并说明原因", MAX_LINES, ALLOWLIST)
                .isEmpty();
    }

    private static int countLines(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return (int) lines.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
