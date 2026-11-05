package cn.byteforce.seedj.lock;

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
 * 形状门禁：锁是独立能力，不得依赖 cqrs / eventbus / outbox。
 *
 * <p>它被命令模板、outbox relay、workflow 调度、业务对账同时使用；一旦反向依赖
 * 其中任何一个，其余消费者就被迫拖上那个能力的传递依赖。
 */
class LockPackageIndependenceTest {

    private static final List<String> FORBIDDEN = List.of(
            "import cn.byteforce.seedj.cqrs",
            "import cn.byteforce.seedj.eventbus",
            "import cn.byteforce.seedj.outbox");

    @Test
    @DisplayName("lock 的 main sources 不依赖任何其它能力")
    void lockSourcesDoNotDependOnOtherCapabilities() throws IOException {
        Path root = Path.of("src/main/java/cn/byteforce/seedj/lock");
        assertThat(root).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(LockPackageIndependenceTest::importsAnotherCapability)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertThat(offenders).as("lock files importing another capability").isEmpty();
    }

    private static boolean importsAnotherCapability(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> {
                String stripped = line.strip();
                return FORBIDDEN.stream().anyMatch(stripped::startsWith);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
