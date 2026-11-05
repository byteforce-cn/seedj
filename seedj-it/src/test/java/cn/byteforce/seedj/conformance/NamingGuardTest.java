package cn.byteforce.seedj.conformance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 产品代码命名门禁：规范名只能以三种线格式出现（错误码、Schema URI、DDL 域类型）。
 *
 * <p>扫描所有模块的 {@code src/main/java}、{@code src/main/resources} 与 {@code pom.xml}，
 * 去掉三条例外后不得再出现规范名。文档（{@code docs/}）与构建产物不参与扫描。</p>
 */
class NamingGuardTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

    private static final Pattern ALLOWED = Pattern.compile(
            "EIS-\\d{3}-\\d{3}|eis://[^\\s\"']+|eis_(id|version|timestamp)");

    private static final Pattern FORBIDDEN_STANDALONE = Pattern.compile("(?i)(^|[^a-z])eis([^a-z]|$)");

    private static final Pattern FORBIDDEN_CAMEL = Pattern.compile("[a-z]Eis");

    @Test
    void productArtifactsDoNotNameTheSpec() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(NamingGuardTest::isProductArtifact)
                    .forEach(path -> scan(path, hits));
        }
        assertThat(hits).isEmpty();
    }

    private static boolean isProductArtifact(Path path) {
        String p = path.toAbsolutePath().toString();
        if (p.contains("/target/") || p.contains("/.git/") || p.contains("/docs/")
                || p.contains("/.superpowers/")) {
            return false;
        }
        return p.endsWith("/pom.xml")
                || p.contains("/src/main/java/")
                || p.contains("/src/main/resources/");
    }

    private static void scan(Path path, List<String> hits) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String stripped = ALLOWED.matcher(lines.get(i)).replaceAll("");
                if (FORBIDDEN_STANDALONE.matcher(stripped).find()
                        || FORBIDDEN_CAMEL.matcher(stripped).find()) {
                    hits.add(path + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        } catch (java.nio.charset.MalformedInputException e) {
            // 二进制资源（证书、keystore）不参与命名扫描
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }
}
