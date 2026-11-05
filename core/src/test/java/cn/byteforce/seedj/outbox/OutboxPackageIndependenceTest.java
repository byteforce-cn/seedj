package cn.byteforce.seedj.outbox;

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
 * 形状门禁：outbox 契约包不得依赖 eventbus。
 *
 * <p>只看 import 行，避免 Javadoc 里的说明文字误报。
 */
class OutboxPackageIndependenceTest {

    private static final String EVENTBUS_IMPORT = "import cn.byteforce.seedj.eventbus";

    @Test
    @DisplayName("outbox 的 main sources 不 import eventbus")
    void outboxSourcesDoNotImportEventBus() throws IOException {
        Path root = Path.of("src/main/java/cn/byteforce/seedj/outbox");
        assertThat(root).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(OutboxPackageIndependenceTest::importsEventBus)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertThat(offenders).as("outbox files importing eventbus").isEmpty();
    }

    private static boolean importsEventBus(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.strip().startsWith(EVENTBUS_IMPORT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
