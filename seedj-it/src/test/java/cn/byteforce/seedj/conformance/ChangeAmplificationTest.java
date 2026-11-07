package cn.byteforce.seedj.conformance;

import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.assertWithin;
import static cn.byteforce.seedj.conformance.CognitiveLoadBaseline.javaFiles;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 变更放大门禁：加一个字段只能改「一个声明点 + 一条审阅过的迁移」。
 *
 * <p>代码生成之后，schema 只由 {@code @SeedjObject} record 声明；手写 {@code ObjectSchema.builder}
 * 与运行期 DDL 生成都不允许回到业务代码里。</p>
 */
class ChangeAmplificationTest {

    private static final Path ROOT = ModuleClassPath.root();

    private static final List<Path> HR_MAIN =
            javaFiles(ROOT, "seedj-server/hr/src/main/java");

    @Test
    @DisplayName("HR schema 声明点只有一个 @SeedjObject record")
    void singleSchemaDeclaration() {
        int declarations = (int) HR_MAIN.stream()
                .filter(file -> contains(file, "@SeedjObject"))
                .count();
        assertWithin("hr.schemaDeclarationSites", "hr.schemaDeclarationSites", declarations);
        assertThat(declarations).as("HR must declare its schema exactly once").isEqualTo(1);
    }

    @Test
    @DisplayName("业务主源码不得手写 ObjectSchema.builder")
    void noHandWrittenSchema() {
        assertThat(CognitiveLoadBaseline.textOccurrences(HR_MAIN, "ObjectSchema.builder("))
                .as("schema 字段只能来自 @SeedjObject record，不能再手写 builder")
                .isZero();
    }

    @Test
    @DisplayName("迁移是签入的 literal SQL，不在启动时由 schema 生成")
    void migrationsAreLiteralSql() {
        List<Path> resources;
        Path resourcesRoot = ROOT.resolve("seedj-server/hr/src/main/resources");
        try (var files = Files.walk(resourcesRoot)) {
            resources = files.filter(path -> path.toString().endsWith(".sql")).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        assertThat(resources).as("HR 的迁移必须是签入的 literal SQL").hasSize(2);
        List<String> sql = new java.util.ArrayList<>();
        for (Path resource : resources) {
            try {
                sql.add(Files.readString(resource));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        assertThat(sql).anySatisfy(script -> assertThat(script)
                .contains("CREATE TABLE hr_employee"));
        assertThat(sql).noneSatisfy(script -> assertThat(script)
                .contains("Generated candidate"));
        assertThat(CognitiveLoadBaseline.textOccurrences(HR_MAIN, "TemporalDdl.createTable"))
                .as("启动时生成 DDL 会让已有库 checksum 冲突；V1 必须签入")
                .isZero();
    }

    private static boolean contains(Path file, String needle) {
        try {
            return Files.readAllLines(file).stream().anyMatch(line -> line.contains(needle));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
