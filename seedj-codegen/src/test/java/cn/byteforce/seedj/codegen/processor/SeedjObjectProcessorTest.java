package cn.byteforce.seedj.codegen.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 处理器端到端：javac 真正跑一轮，生成源码与 META-INF 资源。 */
class SeedjObjectProcessorTest {

    private static final String SOURCE =
            """
            package fixture;

            import cn.byteforce.seedj.codegen.MaskerName;
            import cn.byteforce.seedj.codegen.SeedjField;
            import cn.byteforce.seedj.codegen.SeedjObject;
            import cn.byteforce.seedj.domain.Privacy;
            import java.time.LocalDate;

            @SeedjObject(objectType = "employee", table = "hr_employee", view = true)
            public record EmployeeFields(
                    @SeedjField(required = true, filterable = true, sortable = true,
                            indexed = true) String employeeNo,
                    @SeedjField(required = true) String name,
                    @SeedjField(privacy = Privacy.SENSITIVE,
                            masker = MaskerName.LAST4) String phone,
                    @SeedjField(writeOnly = true) String idNumberHash,
                    @SeedjField LocalDate hireDate) {}
            """;

    @Test
    @DisplayName("编译 record 生成 schema 源码、OpenAPI 与 DDL 候选")
    void generatesSchemaAndResources(@TempDir Path temp) throws IOException {
        Path classes = Files.createDirectories(temp.resolve("classes"));
        Path generated = Files.createDirectories(temp.resolve("generated"));

        Result result = compile(temp, classes, generated, SOURCE, "fixture.EmployeeFields");
        assertThat(result.success()).as(String.join("\n", result.diagnostics())).isTrue();

        Path schema = generated.resolve("fixture/EmployeeFieldsSchema.java");
        assertThat(schema).isRegularFile();
        String source = Files.readString(schema);
        assertThat(source).contains("ObjectSchema.builder(\"employee\", \"hr_employee\")");
        assertThat(source).contains("employee_no");
        assertThat(source).contains("cn.byteforce.seedj.domain.Maskers.last4()");

        Path view = generated.resolve("fixture/EmployeeFieldsView.java");
        assertThat(view).isRegularFile();
        String viewSource = Files.readString(view);
        assertThat(viewSource).contains("public record EmployeeFieldsView(");
        assertThat(viewSource).contains("String employeeNo");
        assertThat(viewSource).doesNotContain("idNumberHash");

        Path openapi = classes.resolve("META-INF/seedj/openapi/employee.yaml");
        assertThat(openapi).isRegularFile();
        assertThat(Files.readString(openapi)).contains("x-privacy: SENSITIVE");

        Path create = classes.resolve("META-INF/seedj/ddl/hr_employee-create.sql");
        assertThat(create).isRegularFile();
        assertThat(Files.readString(create)).contains("CREATE TABLE hr_employee (");

        Path columns = classes.resolve("META-INF/seedj/ddl/hr_employee-columns.sql");
        assertThat(columns).isRegularFile();
        assertThat(Files.readString(columns)).contains("ADD COLUMN IF NOT EXISTS phone TEXT;");
    }

    @Test
    @DisplayName("非 record 或不可映射类型在编译期报错")
    void failsOnUnsupportedDeclaration(@TempDir Path temp) throws IOException {
        String classSource =
                """
                package fixture;

                import cn.byteforce.seedj.codegen.SeedjObject;

                @SeedjObject(objectType = "employee", table = "hr_employee")
                public class NotARecord {}
                """;
        Result notRecord =
                compile(
                        temp,
                        Files.createDirectories(temp.resolve("c1")),
                        Files.createDirectories(temp.resolve("g1")),
                        classSource,
                        "fixture.NotARecord");
        assertThat(notRecord.success()).isFalse();
        assertThat(notRecord.diagnostics())
                .anySatisfy(
                        message ->
                                assertThat(message)
                                        .contains("@SeedjObject can only annotate a record"));

        String listSource =
                """
                package fixture;

                import cn.byteforce.seedj.codegen.SeedjObject;
                import java.util.List;

                @SeedjObject(objectType = "employee", table = "hr_employee")
                public record BadFields(List<String> tags) {}
                """;
        Result unsupported =
                compile(
                        temp,
                        Files.createDirectories(temp.resolve("c2")),
                        Files.createDirectories(temp.resolve("g2")),
                        listSource,
                        "fixture.BadFields");
        assertThat(unsupported.success()).isFalse();
        assertThat(unsupported.diagnostics())
                .anySatisfy(message -> assertThat(message).contains("unsupported field type"));
    }

    @Test
    @DisplayName("注解中的值域约束透传到生成的 schema")
    void carriesFieldConstraints(@TempDir Path temp) throws IOException {
        String source =
                """
                package fixture;

                import cn.byteforce.seedj.codegen.SeedjField;
                import cn.byteforce.seedj.codegen.SeedjObject;
                import java.math.BigDecimal;

                @SeedjObject(objectType = "employee", table = "hr_employee")
                public record EmployeeFields(
                        @SeedjField(required = true, unique = true,
                                pattern = "^EMP-[0-9]{6,}$", minLength = 8,
                                maxLength = 32) String employeeNo,
                        @SeedjField(required = true,
                                enumValues = {"pending_onboarding", "active"})
                        String status,
                        @SeedjField(precision = 18, scale = 2) BigDecimal salary) {}
                """;
        Path classes = Files.createDirectories(temp.resolve("classes"));
        Path generated = Files.createDirectories(temp.resolve("generated"));

        Result result = compile(temp, classes, generated, source, "fixture.EmployeeFields");
        assertThat(result.success()).as(String.join("\n", result.diagnostics())).isTrue();

        String schema = Files.readString(generated.resolve("fixture/EmployeeFieldsSchema.java"));
        assertThat(schema).contains("\"^EMP-[0-9]{6,}$\", 8, 32, true, null, null");
        assertThat(schema).contains("java.util.Set.of(\"active\", \"pending_onboarding\")");
        assertThat(schema).contains("null, null, null, false, 18, 2");

        String ddl = Files.readString(classes.resolve("META-INF/seedj/ddl/hr_employee-create.sql"));
        assertThat(ddl).contains("employee_no TEXT NOT NULL");
        assertThat(ddl).contains("salary NUMERIC(18,2)");
        assertThat(ddl).contains("CREATE UNIQUE INDEX uq_hr_employee_employee_no");
    }

    @Test
    @DisplayName("DECIMAL 未声明 precision 在编译期报错")
    void rejectsDecimalWithoutPrecision(@TempDir Path temp) throws IOException {
        String source =
                """
                package fixture;

                import cn.byteforce.seedj.codegen.SeedjObject;
                import java.math.BigDecimal;

                @SeedjObject(objectType = "invoice", table = "fin_invoice")
                public record BadAmount(BigDecimal amount) {}
                """;
        Result result =
                compile(
                        temp,
                        Files.createDirectories(temp.resolve("classes")),
                        Files.createDirectories(temp.resolve("generated")),
                        source,
                        "fixture.BadAmount");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(message -> assertThat(message).contains("must declare precision"));
    }

    private static Result compile(
            Path temp, Path classes, Path generated, String source, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            files.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            JavaFileObject unit =
                    new SimpleJavaFileObject(
                            URI.create("string:///" + className.replace('.', '/') + ".java"),
                            JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            return source;
                        }
                    };
            List<String> options =
                    List.of(
                            "-proc:full",
                            "-classpath",
                            System.getProperty("java.class.path"),
                            "-d",
                            classes.toString(),
                            "-s",
                            generated.toString());
            var task = compiler.getTask(null, files, diagnostics, options, null, List.of(unit));
            task.setProcessors(List.of(new SeedjObjectProcessor()));
            boolean success = task.call();
            return new Result(
                    success,
                    diagnostics.getDiagnostics().stream()
                            .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                            .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                            .toList());
        } catch (IOException error) {
            return new Result(false, List.of(error.getMessage()));
        }
    }

    private record Result(boolean success, List<String> diagnostics) {}
}
