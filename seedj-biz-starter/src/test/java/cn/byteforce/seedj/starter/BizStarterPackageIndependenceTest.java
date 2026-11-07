package cn.byteforce.seedj.starter;

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
 * 形状门禁：业务 starter 不得依赖 IAM。
 *
 * <p>这条约束是"业务服务不携带授权服务器"的唯一保证。一旦有人在 biz 里 import 了 {@code
 * cn.byteforce.seedj.iam}，聚合模块（starter）之外的业务服务 就会被迫把 SAS / SCIM / TOTP / JWK 打进 classpath。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class BizStarterPackageIndependenceTest {

    private static final String IAM_IMPORT = "import cn.byteforce.seedj.iam";

    @Test
    @DisplayName("biz starter 的 main sources 不 import iam")
    void starterDoesNotImportIam() throws IOException {
        Path root = Path.of("src/main");
        assertThat(root).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders =
                    files.filter(path -> path.toString().endsWith(".java"))
                            .filter(BizStarterPackageIndependenceTest::importsIam)
                            .map(Path::toString)
                            .sorted()
                            .toList();
        }

        assertThat(offenders).as("biz starter files importing iam").isEmpty();
    }

    private static boolean importsIam(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(line -> line.strip().startsWith(IAM_IMPORT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
