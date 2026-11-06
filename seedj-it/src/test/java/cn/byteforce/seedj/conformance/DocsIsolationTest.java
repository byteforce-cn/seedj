package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 源码独立性门禁：源码树不得再引用 {@code docs/} 目录或 superpowers 工作目录。
 *
 * <p>开发过程文档集中在独立文档仓维护，机器读取的契约在 {@code config/conformance/}。
 * 本测试扫描产品源码、测试源码、构建脚本、POM 与 README，出现 {@code docs/} 相对路径
 * 或 {@code .superpowers} 即失败，防止文档依赖重新渗回源码树。</p>
 */
class DocsIsolationTest {

    private static final Path ROOT = NamingGuardTest.repoRoot(
            Path.of(System.getProperty("user.dir")));

    private static final Pattern FORBIDDEN = Pattern.compile("docs/|\\.superpowers");

    private static final String SELF =
            "seedj-it/src/test/java/cn/byteforce/seedj/conformance/DocsIsolationTest.java";

    @Test
    @DisplayName("源码树不引用 docs/ 或 .superpowers")
    void sourceTreeDoesNotReferenceDocs() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(DocsIsolationTest::isScanned)
                    .forEach(path -> scan(path, hits));
        }
        assertThat(hits)
                .as("文档依赖不得回到源码树；过程文档放独立文档仓，机器契约放 config/")
                .isEmpty();
    }

    @Test
    @DisplayName("仓库根不存在 docs/ 与 .superpowers 目录")
    void obsoleteDirectoriesStayDeleted() {
        assertThat(ROOT.resolve("docs"))
                .as("过程文档必须留在独立文档仓")
                .doesNotExist();
        assertThat(ROOT.resolve(".superpowers"))
                .as("superpowers 工作目录不得进入仓库")
                .doesNotExist();
    }

    private static boolean isScanned(Path path) {
        String p = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (p.contains("/target/") || p.contains("/.git/") || p.endsWith("/" + SELF)) {
            return false;
        }
        if (p.endsWith("/pom.xml") || p.endsWith("/README.md")) {
            return true;
        }
        return p.contains("/src/main/") || p.contains("/src/test/")
                || p.contains("/config/") || p.contains("/.github/");
    }

    private static void scan(Path path, List<String> hits) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    hits.add(ROOT.relativize(path) + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        } catch (MalformedInputException e) {
            // 二进制资源（证书、keystore 等）不参与文本扫描
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }
}
