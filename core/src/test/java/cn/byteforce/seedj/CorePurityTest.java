package cn.byteforce.seedj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 形状门禁：core 只能依赖契约本身，不得出现监控框架或 Spring。
 */
class CorePurityTest {

    private static final List<String> FORBIDDEN = List.of(
            "import io.micrometer",
            "import io.opentelemetry",
            "import org.springframework");

    @Test
    @DisplayName("core 的 main sources 不含监控框架 / Spring 依赖")
    void coreStaysFrameworkFree() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(CorePurityTest::importsForbidden)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertThat(offenders).as("core files importing observability frameworks").isEmpty();
    }

    private static boolean importsForbidden(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> FORBIDDEN.stream().anyMatch(line::startsWith));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
