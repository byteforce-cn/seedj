package cn.byteforce.seedj.file;

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
 * 形状门禁：文件是独立能力，不得依赖 cqrs / eventbus / outbox / im。
 *
 * <p>业务侧（附件、导出、导入）都用它；一旦反向依赖某个上层能力，其余调用方就被迫拖上那个能力的 传递依赖。
 */
class FilePackageIndependenceTest {

    private static final List<String> FORBIDDEN =
            List.of(
                    "import cn.byteforce.seedj.cqrs",
                    "import cn.byteforce.seedj.eventbus",
                    "import cn.byteforce.seedj.outbox",
                    "import cn.byteforce.seedj.im");

    @Test
    @DisplayName("file 的 main sources 不依赖任何其它能力")
    void fileSourcesDoNotDependOnOtherCapabilities() throws IOException {
        Path root = Path.of("src/main/java/cn/byteforce/seedj/file");
        assertThat(root).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders =
                    files.filter(path -> path.toString().endsWith(".java"))
                            .filter(FilePackageIndependenceTest::importsAnotherCapability)
                            .map(Path::toString)
                            .sorted()
                            .toList();
        }

        assertThat(offenders).as("file files importing another capability").isEmpty();
    }

    private static boolean importsAnotherCapability(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(
                    line -> {
                        String stripped = line.strip();
                        return FORBIDDEN.stream().anyMatch(stripped::startsWith);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
