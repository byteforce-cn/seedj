package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.server.hr.HrServer;
import cn.byteforce.seedj.conformance.EisSchemaReader.EisObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 从 eis 权威契约反向校验 HR 活样例：字段集合、required、enum、pattern、format、
 * writeOnly 与权限集合必须一致，不接受子集断言。
 *
 * <p>{@code EIS_ROOT}（或 {@code -Deis.root}）指向 eis 工作副本。CI 必须提供；
 * 本地未提供时跳过并打印提示，避免把“没有规范仓库”误当成通过。</p>
 */
@SpringBootTest(classes = HrServer.class, properties = "spring.profiles.active=local")
class EisContractAlignmentTest {

    @Autowired
    private CommandCatalog catalog;

    @Autowired
    private ObjectModuleRegistry modules;

    @Test
    @DisplayName("Employee 字段集合、required、enum、pattern、format、writeOnly 与 eis 一致")
    void employeeSchemaMatchesEis() throws Exception {
        ObjectSchema actual = modules.require("employee").schema();
        EisObject expected = EisSchemaReader.readObject(
                eisRoot().resolve("extensions/hr/objects/employee.schema.json"));

        assertThat(actual.fieldNames())
                .containsExactlyInAnyOrderElementsOf(expected.properties().keySet());
        assertThat(actual.requiredFields())
                .containsExactlyInAnyOrderElementsOf(expected.required());

        assertThat(actual.requireField("employee_no").constraints().pattern())
                .isEqualTo("^EMP-[0-9]{6,}$");
        assertThat(actual.requireField("employee_no").constraints().unique()).isTrue();
        assertThat(actual.requireField("name").constraints().minLength()).isEqualTo(1);
        assertThat(actual.requireField("status").constraints().enumValues())
                .containsExactlyInAnyOrder("pending_onboarding", "active", "terminated",
                        "cancelled");
        assertThat(actual.requireField("employment_type").constraints().enumValues())
                .containsExactlyInAnyOrder("full_time", "part_time", "intern", "contract");
        assertThat(actual.requireField("work_location").constraints().minLength()).isEqualTo(1);
        assertThat(actual.requireField("id_type").constraints().enumValues())
                .containsExactlyInAnyOrder("national_id", "passport");
        assertThat(actual.requireField("org_unit_id").type()).isEqualTo(FieldType.UUID);
        assertThat(actual.requireField("hire_date").type()).isEqualTo(FieldType.DATE);
        assertThat(actual.requireField("terminated_at").type()).isEqualTo(FieldType.TIMESTAMP);
        assertThat(actual.requireField("id_number_hash").writeOnly()).isTrue();
        assertThat(actual.requireField("id_number_hash").constraints().pattern())
                .isEqualTo("^[0-9a-f]{64}$");

        assertFormatMatches(actual, expected, "hire_date");
        assertFormatMatches(actual, expected, "org_unit_id");
        assertFormatMatches(actual, expected, "terminated_at");
    }

    @Test
    @DisplayName("命令与读权限都必须在 eis HR manifest 中声明")
    void commandPermissionsExistInEisManifest() throws Exception {
        Set<String> declared = EisSchemaReader.readPermissionIds(
                eisRoot().resolve("extensions/hr/manifest.yaml"));

        for (CommandDescriptor descriptor : catalog.descriptors()) {
            assertThat(declared)
                    .as("command %s permission %s", descriptor.type(), descriptor.permission())
                    .contains(descriptor.permission());
        }
        assertThat(declared).contains(modules.require("employee").readPermission());
    }

    private static void assertFormatMatches(ObjectSchema actual, EisObject expected, String field) {
        Map<String, Object> declaration = expected.field(field);
        String format = String.valueOf(declaration.get("format"));
        FieldType expectedType = switch (format) {
            case "date" -> FieldType.DATE;
            case "date-time" -> FieldType.TIMESTAMP;
            case "uuid" -> FieldType.UUID;
            default -> null;
        };
        assertThat(expectedType).as("unsupported eis format for %s: %s", field, format)
                .isNotNull();
        assertThat(actual.requireField(field).type()).isEqualTo(expectedType);
    }

    private static Path eisRoot() {
        String raw = System.getenv("EIS_ROOT");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("eis.root");
        }
        if (raw == null || raw.isBlank()) {
            String message = "EIS_ROOT (or -Deis.root) must point to the eis worktree";
            if ("true".equalsIgnoreCase(System.getenv("CI"))) {
                fail(message + "; CI must not skip contract alignment");
            }
            System.out.println("SKIP EisContractAlignmentTest: " + message);
            assumeTrue(false, message);
        }
        Path resolved = resolve(Paths.get(raw));
        assertThat(Files.isDirectory(resolved))
                .as("eisRoot %s is not a directory", resolved)
                .isTrue();
        return resolved;
    }

    private static Path resolve(Path raw) {
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        List<Path> bases = List.of(Paths.get("").toAbsolutePath(),
                Paths.get("").toAbsolutePath().getParent());
        for (Path base : bases) {
            Path candidate = base.resolve(raw).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return raw.toAbsolutePath().normalize();
    }
}
