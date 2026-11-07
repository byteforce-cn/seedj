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
import org.junit.jupiter.api.io.TempDir;

/**
 * 公开文档边界门禁：公开树不得承载或引用过程文档，ADR 编号不得裸露。
 *
 * <p>公开发布后，过程文档（设计、计划、评估、验收记录、ADR 正文）只存在于私有文档仓。
 * 本测试扫描产品源码、测试源码、构建脚本、POM、README 与 {@code docs/}，出现过程路径、
 * 裸 ADR 编号或无 URL 的文档指路即失败。</p>
 *
 * <p>例外：{@code config/conformance/schema/} 与 {@code config/conformance/baseline/} 是规范
 * 的逐字节副本（由 {@code check-spec-alignment.sh} 校验），其中的 ADR 字样不可改写，因此
 * 豁免裸编号规则。</p>
 */
class DocsBoundaryTest {

    private static final Path ROOT = NamingGuardTest.repoRoot(
            Path.of(System.getProperty("user.dir")));

    private static final List<String> FORBIDDEN_DIRS = List.of(
            "docs/adr",
            "docs/superpowers",
            "docs/assessment",
            "docs/conformance/runs",
            "config/adr",
            ".superpowers");

    /** 私有仓的过程路径；{@code 00-freeze/adr/} 是规范仓的公开路径，不在此列。 */
    private static final Pattern FORBIDDEN_PATHS =
            Pattern.compile("adr/ADR-|superpowers/|assessment/|conformance/runs/");

    private static final Pattern BARE_ADR = Pattern.compile("(?<!EIS )ADR-\\d{3,4}");

    private static final Pattern DOC_POINTER = Pattern.compile("独立文档仓|文档仓");

    private static final List<String> ADR_COPY_DIRS = List.of(
            "config/conformance/schema/",
            "config/conformance/baseline/");

    private static final String SELF =
            "seedj-it/src/test/java/cn/byteforce/seedj/conformance/DocsBoundaryTest.java";

    @Test
    @DisplayName("仓库不存在过程文档目录")
    void forbiddenDirectoriesStayDeleted() {
        for (String dir : FORBIDDEN_DIRS) {
            assertThat(ROOT.resolve(dir))
                    .as("过程文档不得进入公开树: %s", dir)
                    .doesNotExist();
        }
    }

    @Test
    @DisplayName("公开树不引用过程文档路径")
    void noProcessPathReferences() {
        assertThat(hits("process path"))
                .as("过程文档路径不得出现在公开树")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR 编号必须带 EIS 前缀")
    void adrNumbersAreQualified() {
        assertThat(hits("bare adr"))
                .as("裸 ADR 编号在公开树里无法解析；规范决策写 EIS ADR-NNNN")
                .isEmpty();
    }

    @Test
    @DisplayName("文档指路必须可解析")
    void noDanglingDocPointers() {
        assertThat(hits("doc pointer"))
                .as("指路必须是仓内相对链接或 https URL，不能只写\"文档仓\"")
                .isEmpty();
    }

    @Test
    @DisplayName("规则能抓住植入的违规")
    void rulesCatchPlantedViolations(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("config"));
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(tmp.resolve("config/bare-adr.md"), "见 ADR-0012\n");
        Files.writeString(tmp.resolve("config/pointer.md"), "见独立文档仓\n");
        Files.writeString(tmp.resolve("docs/process.md"), "详见 adr/ADR-0001-x.md\n");
        Files.writeString(tmp.resolve("config/eis.md"),
                "EIS ADR-0004（00-freeze/adr/0004-authz-attributes-ownership.md）\n");

        List<Hit> hits = scan(tmp);

        assertThat(hits).extracting(Hit::kind)
                .contains("bare adr", "doc pointer", "process path");
        assertThat(hits)
                .as("EIS 公开路径与 EIS 前缀不应被误报")
                .noneMatch(hit -> hit.location().contains("eis.md"));
    }

    private static List<Hit> hits(String kind) {
        return scan(ROOT).stream().filter(hit -> hit.kind().equals(kind)).toList();
    }

    private static List<Hit> scan(Path root) {
        List<Hit> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isScanned(root, path))
                    .forEach(path -> scanFile(root, path, hits));
        } catch (IOException e) {
            throw new IllegalStateException("扫描失败: " + root, e);
        }
        return hits;
    }

    private static boolean isScanned(Path root, Path path) {
        if (path.startsWith(root.resolve(".git"))
                || path.startsWith(root.resolve("target"))
                || path.startsWith(root.resolve("node_modules"))) {
            return false;
        }
        String rel = root.relativize(path).toString().replace('\\', '/');
        if (rel.equals(SELF) || rel.contains("/.vitepress/") || rel.startsWith(".vitepress/")) {
            return false;
        }
        if (rel.startsWith("docs/")) {
            return rel.endsWith(".md");
        }
        return rel.startsWith("config/")
                || rel.startsWith(".github/")
                || rel.contains("/src/main/")
                || rel.contains("/src/test/")
                || rel.endsWith("pom.xml")
                || rel.endsWith("README.md");
    }

    private static void scanFile(Path root, Path path, List<Hit> hits) {
        String rel = root.relativize(path).toString().replace('\\', '/');
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String where = rel + ":" + (i + 1);
                if (FORBIDDEN_PATHS.matcher(line).find()) {
                    hits.add(new Hit("process path", where, line.trim()));
                }
                if (!isAdrCopy(rel) && BARE_ADR.matcher(line).find()) {
                    hits.add(new Hit("bare adr", where, line.trim()));
                }
                if (DOC_POINTER.matcher(line).find()) {
                    hits.add(new Hit("doc pointer", where, line.trim()));
                }
            }
        } catch (MalformedInputException e) {
            // 二进制资源（证书、keystore 等）不参与文本扫描
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }

    private static boolean isAdrCopy(String rel) {
        return ADR_COPY_DIRS.stream().anyMatch(rel::startsWith);
    }

    private record Hit(String kind, String location, String text) {
    }
}
