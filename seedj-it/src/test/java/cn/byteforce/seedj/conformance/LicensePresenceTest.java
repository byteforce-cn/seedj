package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 许可交付门禁：仓库必须声明 Apache-2.0，且交付制品必须把许可文本带进 {@code META-INF}。
 *
 * <p>仓库根断言 {@code LICENSE}、{@code NOTICE} 存在且非空；打包断言以 {@code seedj-server/hr} 的制件为样本（根 POM 的 {@code
 * copy-license-notice} 执行把它拷入 {@code META-INF}）。样本 jar 由 {@code package} 生成，单独跑测试时通常不存在，因此用 {@link
 * Assumptions#assumeTrue} 跳过而不是伪造失败：干净树上的门禁不能依赖构建顺序。
 *
 * <p>比 {@code LICENSE}/{@code NOTICE} 更旧的 jar 是引入许可之前的旧产物，同样跳过：它无法
 * 证明当前交付会携带许可，断言它只会把“忘了重新打包”误报成许可交付缺陷。只有新于许可文本的 制品才作为证据被硬断言。
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
            assertThat(Files.isRegularFile(file)).as("仓库根必须存在 %s", name).isTrue();
            assertThat(Files.readString(file).isBlank()).as("%s 不得为空占位", name).isFalse();
        }

        assertThat(Files.readString(ROOT.resolve("LICENSE")))
                .contains("Apache License", "Version 2.0");
        assertThat(Files.readString(ROOT.resolve("NOTICE"))).contains("Byteforce Seedj");
    }

    @Test
    @DisplayName("制品 META-INF 内嵌 LICENSE 与 NOTICE")
    void packagedArtifactEmbedsLicenseAndNotice() throws IOException {
        List<Path> artifacts = freshHrArtifacts();
        Assumptions.assumeTrue(!artifacts.isEmpty(), skipReason());

        for (Path artifact : artifacts) {
            try (JarFile jar = new JarFile(artifact.toFile())) {
                for (String name : REQUIRED_FILES) {
                    assertThat(jar.getEntry("META-INF/" + name))
                            .as("%s 必须包含 META-INF/%s", artifact.getFileName(), name)
                            .isNotNull();
                }
            }
        }
    }

    /** 只有不早于 LICENSE/NOTICE 的制品才是当前交付的有效证据。 */
    private static List<Path> freshHrArtifacts() throws IOException {
        FileTime licenseTime = newestLicenseMtime();
        List<Path> fresh = new ArrayList<>();
        for (Path artifact : hrArtifacts()) {
            if (Files.getLastModifiedTime(artifact).compareTo(licenseTime) >= 0) {
                fresh.add(artifact);
            }
        }
        return fresh;
    }

    /** 跳过原因要能区分“从未打包”和“制品早于许可文本”，否则绿灯没有信息量。 */
    private static String skipReason() throws IOException {
        if (!Files.isDirectory(HR_TARGET)) {
            return "seedj-server/hr/target 不存在（尚未打包）；跳过制品内嵌断言。";
        }
        if (!hrArtifacts().isEmpty()) {
            return "seedj-server/hr 的 jar 早于 LICENSE/NOTICE（stale 产物）；跳过过期制品断言，"
                    + "执行 ./mvnw -pl seedj-server/hr -am package 后重跑以获得证据。";
        }
        return "seedj-server/hr/target 无产物 jar（测试阶段不打包）；跳过制品内嵌断言。";
    }

    private static FileTime newestLicenseMtime() throws IOException {
        FileTime newest = FileTime.fromMillis(0);
        for (String name : REQUIRED_FILES) {
            FileTime time = Files.getLastModifiedTime(ROOT.resolve(name));
            if (time.compareTo(newest) > 0) {
                newest = time;
            }
        }
        return newest;
    }

    /** 返回 hr 服务的交付制品（排除 exec/original/sources/javadoc 附属性 jar）。 */
    private static List<Path> hrArtifacts() throws IOException {
        if (!Files.isDirectory(HR_TARGET)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(HR_TARGET)) {
            return paths.filter(Files::isRegularFile)
                    .filter(LicensePresenceTest::isDeliverableArtifact)
                    .sorted()
                    .toList();
        }
    }

    private static boolean isDeliverableArtifact(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jar")
                && !name.contains("-sources")
                && !name.contains("-javadoc")
                && !name.contains("-exec")
                && !name.startsWith("original-");
    }
}
