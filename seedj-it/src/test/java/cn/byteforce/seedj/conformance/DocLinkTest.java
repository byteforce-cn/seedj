package cn.byteforce.seedj.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档链接门禁：所有 Markdown 相对链接必须指向仓库内存在的文件或目录。
 *
 * <p>扫描 {@code README.md}、{@code docs/**&#47;*.md} 与任意模块的 {@code README.md}
 * （排除 {@code target}、{@code .git}），解析 {@code [text](path)} 与 {@code [ref]: path}
 * 两种写法。解析前先去掉 fenced code block 与 inline code，避免把文档里的语法示例当成真链接；
 * {@code http(s)://}、{@code mailto:} 与纯锚点（{@code #anchor}）不参与本地校验。</p>
 *
 * <p>本测试不联网：只做仓库内相对路径存在性检查。</p>
 */
class DocLinkTest {

    private static final Path ROOT = NamingGuardTest.repoRoot(Path.of(System.getProperty("user.dir")));

    /** Fenced code block：``` 或 ~~~ 开头/结尾，可带语言标记。 */
    private static final Pattern FENCED_BLOCK = Pattern.compile("^\\s*(```|~~~).*?^\\s*\\1\\s*$",
            Pattern.DOTALL | Pattern.MULTILINE);

    /** Inline code span：单/多反引号包裹。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`+[^`\\n]*`+");

    /** 行内链接 {@code [text](target "optional title")}。 */
    private static final Pattern INLINE_LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)]*)\\)");

    /** 引用式链接定义 {@code [ref]: target}。 */
    private static final Pattern REFERENCE_LINK = Pattern.compile("^\\s*\\[[^\\]]+]:\\s*(\\S+)",
            Pattern.MULTILINE);

    private static final Pattern EXTERNAL = Pattern.compile("^(https?:|mailto:|ftp:|#|//)");

    @Test
    @DisplayName("仓库内所有 Markdown 相对链接都指向存在的路径")
    void allDocumentationLinksResolve() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(DocLinkTest::isDocumentation)
                    .sorted()
                    .forEach(doc -> missing.addAll(missingLinks(doc)));
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("解析器忽略代码示例与外部链接，只报告真正缺失的相对路径")
    void parserIgnoresCodeExamplesAndExternalTargets(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("docs"));
        Files.writeString(temp.resolve("docs/exists.md"), "# exists\n");
        Path doc = temp.resolve("docs/index.md");
        Files.writeString(doc, String.join("\n",
                "# 索引",
                "",
                "[有效](exists.md)",
                "[带锚点](exists.md#section)",
                "[外部](https://example.com/missing.md)",
                "[邮件](mailto:dev@example.com)",
                "[锚点](#section)",
                "[缺失](missing.md)",
                "",
                "[ref]: exists.md",
                "[ref-missing]: also-missing.md",
                "",
                "```markdown",
                "[示例](fenced-example.md)",
                "```",
                "",
                "~~~",
                "[示例](tilde-example.md)",
                "~~~",
                "",
                "行内示例 `[示例](inline-example.md)` 不是链接。",
                ""));

        String prefix = doc.toAbsolutePath().normalize() + ": ";
        assertThat(missingLinks(doc)).containsExactly(
                prefix + "missing.md", prefix + "also-missing.md");
    }

    @Test
    @DisplayName("README 不再引用已退役的模块名")
    void readmeDoesNotUseRetiredModuleNames() throws IOException {
        String readme = Files.readString(ROOT.resolve("README.md"));
        assertThat(readme)
                .as("README 只能出现当前模块名；旧名会让人按错误坐标找代码")
                .doesNotContain("domain-jdbc-postgresql")
                .doesNotContain("seedj-outbox-starter")
                .doesNotContain("common/core");
    }

    @Test
    @DisplayName("docs/biz 的 403 示例使用契约错误与统一信封")
    void securityExamplesUseContractEnvelope() throws IOException {
        String doc = Files.readString(ROOT.resolve("docs/biz/README.md"));
        assertThat(doc)
                .contains("ContractException")
                .contains("FORBIDDEN")
                .contains("\"code\"")
                .contains("\"message\"")
                .contains("\"details\"");
    }

    /** 文档集合：根 README、docs 下全部 Markdown、以及各模块 README。 */
    private static boolean isDocumentation(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path fileName = absolute.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".md")) {
            return false;
        }
        // 按路径分段判断，而不是字符串 "/target/"：Windows 分隔符是 '\'，字符串匹配会永远落空。
        boolean inDocs = false;
        for (Path segment : absolute) {
            String name = segment.toString();
            if (name.equals("target") || name.equals(".git")) {
                return false;
            }
            if (name.equals("docs")) {
                inDocs = true;
            }
        }
        return fileName.toString().equals("README.md") || inDocs;
    }

    /** 返回 {@code <doc>: <target>} 形式的失效链接清单，按出现顺序去重。 */
    static List<String> missingLinks(Path doc) {
        String markdown;
        try {
            markdown = Files.readString(doc);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + doc, e);
        }
        String prefix = doc.toAbsolutePath().normalize() + ": ";
        Set<String> missing = new LinkedHashSet<>();
        for (String target : extractLinks(markdown)) {
            Path unresolved = doc.toAbsolutePath().getParent().resolve(target).normalize();
            if (!Files.exists(unresolved)) {
                missing.add(prefix + target);
            }
        }
        return new ArrayList<>(missing);
    }

    /** 提取用于校验的相对链接目标：去掉代码区、外部链接与纯锚点。 */
    static List<String> extractLinks(String markdown) {
        String stripped = FENCED_BLOCK.matcher(markdown).replaceAll("");
        stripped = INLINE_CODE.matcher(stripped).replaceAll("");

        List<String> targets = new ArrayList<>();
        Matcher inline = INLINE_LINK.matcher(stripped);
        while (inline.find()) {
            addIfRelative(targets, inline.group(1));
        }
        Matcher reference = REFERENCE_LINK.matcher(stripped);
        while (reference.find()) {
            addIfRelative(targets, reference.group(1));
        }
        return targets;
    }

    private static void addIfRelative(List<String> targets, String raw) {
        String target = raw.trim();
        if (target.startsWith("<") && target.endsWith(">")) {
            target = target.substring(1, target.length() - 1).trim();
        } else {
            // 去掉可选的 Markdown title：`path "title"`
            int space = target.indexOf(' ');
            if (space > 0) {
                target = target.substring(0, space);
            }
        }
        if (target.isEmpty() || EXTERNAL.matcher(target).find()) {
            return;
        }
        int anchor = target.indexOf('#');
        if (anchor >= 0) {
            target = target.substring(0, anchor);
        }
        if (!target.isEmpty()) {
            targets.add(target);
        }
    }
}
