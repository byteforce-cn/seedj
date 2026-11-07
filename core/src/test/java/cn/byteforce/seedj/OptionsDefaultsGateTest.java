package cn.byteforce.seedj;

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
 * 形状门禁：业务与装配代码不得自己拼 {@code CommandAgent.Options}。
 *
 * <p>框架提供的审计 / 指标钩子只能经 {@code CommandAgents} 注入；直接 {@code Options.defaults()}
 * 会绕过它们，且不会有编译错误——所以用门禁守住。
 */
class OptionsDefaultsGateTest {

    private static final List<String> MODULES =
            List.of(
                    "seedj-infra-local",
                    "seedj-jdbc-postgresql",
                    "seedj-iam-starter",
                    "seedj-biz-starter",
                    "seedj-server/iam",
                    "seedj-server/hr",
                    "seedj-server/im");

    @Test
    @DisplayName("各模块 main sources 不直接调用 Options.defaults()")
    void mainSourcesDoNotBuildOptionsDirectly() {
        Path repoRoot = Path.of("..");
        List<String> offenders = new ArrayList<>();
        for (String module : MODULES) {
            Path main = repoRoot.resolve(module).resolve("src/main");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                offenders.addAll(
                        files.filter(path -> path.toString().endsWith(".java"))
                                .filter(OptionsDefaultsGateTest::usesDefaults)
                                .map(Path::toString)
                                .toList());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(offenders).as("main sources calling Options.defaults()").isEmpty();
    }

    private static boolean usesDefaults(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(
                    line ->
                            line.contains("Options.defaults(")
                                    || line.contains("Options.<") && line.contains(">defaults("));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
