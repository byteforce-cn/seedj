package cn.byteforce.seedj.conformance;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 按 Maven 模块逐个导入 {@code target/classes}，而不是导入全局 classpath。
 *
 * <p>当前仓库存在跨模块 split package：全局导入会把 starter 与 core 的同名包混在一起， 让“core 是否依赖
 * Spring”这类规则得出错误结论。逐模块导入保证每条规则看到的边界与 实际制品一致。
 */
final class ModuleClassPath {

    /** 参与架构门禁的模块。seedj-it 只有测试代码，不参与。 */
    static final List<String> MODULES =
            List.of(
                    "core",
                    "seedj-infra-local",
                    "seedj-workflow-local",
                    "seedj-jdbc-postgresql",
                    "seedj-biz-starter",
                    "seedj-im-starter",
                    "seedj-iam-starter",
                    "seedj-server/hr",
                    "seedj-server/iam",
                    "seedj-server/im");

    private ModuleClassPath() {}

    static Path root() {
        return NamingGuardTest.repoRoot(Path.of(System.getProperty("user.dir")));
    }

    static Path classesDir(String module) {
        Path dir = root().resolve(module).resolve("target/classes");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "模块 " + module + " 尚未编译（" + dir + "）；先运行 ./mvnw -o -B -pl seedj-it -am test");
        }
        return dir;
    }

    static List<JavaClass> classesOf(String module) {
        var imported = new ClassFileImporter().importPath(classesDir(module));
        return new ArrayList<>(imported);
    }

    /** 合并全部模块，用于跨模块规则（包环、公共 API）。 */
    static List<JavaClass> allClasses() {
        List<JavaClass> all = new ArrayList<>();
        for (String module : MODULES) {
            all.addAll(classesOf(module));
        }
        return all;
    }

    /** {@code cn.byteforce.seedj.<top>...} → {@code cn.byteforce.seedj.<top>}；非 seedj 包返回 null。 */
    static String topLevelPackage(String packageName) {
        String prefix = "cn.byteforce.seedj.";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return prefix + (dot < 0 ? rest : rest.substring(0, dot));
    }

    static Set<String> readLines(String relativePath) {
        Path file = root().resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return Set.of();
        }
        try (Stream<String> lines = Files.lines(file)) {
            Set<String> result = new LinkedHashSet<>();
            lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(result::add);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
