package cn.byteforce.seedj.conformance;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 许可交付门禁：仓库必须声明 Apache-2.0，且交付制品必须把许可文本带进 {@code META-INF}。
 *
 * <p>仓库根断言 {@code LICENSE}、{@code NOTICE} 存在且非空；打包断言以
 * {@code seedj-server/hr} 的制件为样本（根 POM 的 {@code copy-license-notice} 执行把它拷入
 * {@code META-INF}）。样本 jar 由 {@code package} 生成，单独跑测试时通常不存在，因此用
 * {@link Assumptions#assumeTrue} 跳过而不是伪造失败：干净树上的门禁不能依赖构建顺序。</p>
 */
class LicensePresenceTest {

    /** 必须随制品交付的许可文件；顺序即断言顺序。 */
    private static final List<String> REQUIRED_FILES = List.of("LICENSE", "NOTICE");

    private static final Path ROOT =
            NamingGuardTest.repoRoot(Path.of(System.getProperty("user.dir")));

    private static final Path HR_TARGET = ROOT.resolve("seedj-server/hr/target");

    @Test
    @DisplayName("仓库根声明 Apache-2.0 LICENSE 与 NOTICE")
    void repositoryRootCarriesLicenseAndNotice() throws IOException {
        for (String name : REQUIRED_FILES) {
            Path file = ROOT.resolve(name);
            assertThat(Files.isRegularFile(file))
                    .as("仓库根必须存在 %s", name)
                    .isTrue();
            assertThat(Files.readString(file).isBlank())
                    .as("%s 不得为空占位", name)
                    .isFalse();
        }

        assertThat(Files.readString(ROOT.resolve("LICENSE")))
                .contains("Apache License", "Version 2.0");
        assertThat(Files.readString(ROOT.resolve("NOTICE")))
                .contains("Byteforce Seedj");
    }

    @Test
    @DisplayName("制品 META-INF 内嵌 LICENSE 与 NOTICE")
    void packagedArtifactEmbedsLicenseAndNotice() throws IOException {
        Path artifact = hrArtifact();
        Assumptions.assumeTrue(artifact != null,
                "seedj-server/hr 尚未打包（目标目录无 jar）；跳过制品内嵌断言。先执行 mvn package 以获得完整证据。");

        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (String name : REQUIRED_FILES) {
                assertThat(jar.getEntry("META-INF/" + name))
                        .as("%s 必须包含 META-INF/%s", artifact.getFileName(), name)
                        .isNotNull();
            }
        }
    }

    /** 返回 hr 服务的主制品（优先非 sources/javadoc 的 jar），没有则返回 {@code null}。 */
    private static Path hrArtifact() throws IOException {
        if (!Files.isDirectory(HR_TARGET)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(HR_TARGET)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("-sources"))
                    .filter(path -> !path.getFileName().toString().contains("-javadoc"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }
}
