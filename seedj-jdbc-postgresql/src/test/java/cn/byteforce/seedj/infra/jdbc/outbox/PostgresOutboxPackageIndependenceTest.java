package cn.byteforce.seedj.infra.jdbc.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 形状门禁：PostgreSQL outbox 适配器不得依赖 eventbus。 */
class PostgresOutboxPackageIndependenceTest {

    private static final String EVENTBUS_IMPORT = "import cn.byteforce.seedj.eventbus";

    @Test
    @DisplayName("outbox JDBC 适配器不 import eventbus")
    void jdbcOutboxDoesNotImportEventBus() throws IOException {
        Path root = Path.of("src/main/java/cn/byteforce/seedj/infra/jdbc/outbox");
        assertThat(root).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders =
                    files.filter(path -> path.toString().endsWith(".java"))
                            .filter(PostgresOutboxPackageIndependenceTest::importsEventBus)
                            .map(Path::toString)
                            .sorted()
                            .toList();
        }

        assertThat(offenders).as("outbox JDBC files importing eventbus").isEmpty();
    }

    private static boolean importsEventBus(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.strip().startsWith(EVENTBUS_IMPORT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
