package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 包内聚门禁：单个包的主源码文件数不得超过 25。
 *
 * <p>文件大小门禁只能约束“一个类写太多”，抓不住“一个包什么都有”。包内聚规则把 F8 的
 * 单包堆积（IM starter 根包、coord 单包、协议体目录）变成可执行断言；拆分或登记到 {@code
 * config/archunit/package-cohesion-allowlist.txt}，每行一个相对仓库根的目录。
 */
class PackageCohesionTest {

    private static final int MAX_FILES = 25;

    private static final String ALLOWLIST = "config/archunit/package-cohesion-allowlist.txt";

    @Test
    @DisplayName("单包主源码文件不超过 25")
    void packagesStayCohesive() {
        Path root = ModuleClassPath.root();
        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);
        List<String> violations = new ArrayList<>();

        for (String module : ModuleClassPath.MODULES) {
            Path sourceRoot = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> directories = Files.walk(sourceRoot)) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    long files;
                    try (Stream<Path> children = Files.list(directory)) {
                        files =
                                children.filter(path -> path.toString().endsWith(".java")).count();
                    }
                    if (files <= MAX_FILES) {
                        continue;
                    }
                    String relative = root.relativize(directory).toString().replace('\\', '/');
                    if (!allowed.contains(relative)) {
                        violations.add(relative + " (" + files + " files)");
                    }
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        violations.sort(null);

        assertThat(violations)
                .as("单包超过 %d 个主源码文件；按关注点拆包，或在 %s 登记并说明原因", MAX_FILES, ALLOWLIST)
                .isEmpty();
    }

    @Test
    @DisplayName("allowlist 条目仍对应真实目录")
    void allowlistEntriesExist() {
        Path root = ModuleClassPath.root();
        Set<String> allowed = ModuleClassPath.readLines(ALLOWLIST);
        Set<String> missing = new TreeSet<>();
        for (String entry : allowed) {
            if (!Files.isDirectory(root.resolve(entry))) {
                missing.add(entry);
            }
        }
        assertThat(missing).as("%s 中的目录已不存在，删掉对应条目", ALLOWLIST).isEmpty();
    }
}
