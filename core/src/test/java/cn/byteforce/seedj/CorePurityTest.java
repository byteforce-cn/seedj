package cn.byteforce.seedj;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 形状门禁：core 只能依赖契约本身，不得出现监控框架或 Spring。
 *
 * <p>Jackson（`tools.jackson.*`）是唯一允许的第三方线格式依赖，且必须是 provided；
 * 见 {@code docs/adr/ADR-0010-core-provided-jackson.md}。Jackson 2 不得回流。</p>
 */
class CorePurityTest {

    private static final List<String> FORBIDDEN = List.of(
            "import io.micrometer",
            "import io.opentelemetry",
            "import org.springframework",
            "import com.fasterxml.jackson");

    @Test
    @DisplayName("core 的 main sources 不含监控框架 / Spring / Jackson 2")
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

    @Test
    @DisplayName("Jackson 是 provided 依赖")
    void jacksonIsProvided() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        int index = pom.indexOf("<artifactId>jackson-databind</artifactId>");
        assertThat(index).as("core/pom.xml 必须声明 jackson-databind").isGreaterThan(0);
        String block = pom.substring(Math.max(0, index - 200),
                Math.min(pom.length(), index + 300));
        assertThat(block)
                .as("Jackson 是线格式依赖，必须 provided，由运行期模块提供")
                .contains("<groupId>tools.jackson.core</groupId>")
                .contains("<scope>provided</scope>");
    }

    private static boolean importsForbidden(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> FORBIDDEN.stream().anyMatch(line::startsWith));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
