package cn.byteforce.seedj.conformance;

import cn.byteforce.seedj.starter.cqrs.web.CommandController;
import cn.byteforce.seedj.starter.cqrs.web.CommandRequest;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.starter.domain.web.ObjectQueryController;
import cn.byteforce.seedj.starter.domain.web.ObjectQueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手写 OpenAPI 工件与控制器/线格式的对照。
 *
 * <p>规范工具不在日常测试路径里；这里用反射与序列化断言保证「代码与
 * {@code config/conformance/api/} 的冻结契约同形」。</p>
 */
class ApiAlignmentTest {

    private static final Path ROOT = NamingGuardTest.repoRoot(
            Path.of(System.getProperty("user.dir")));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("命令 OpenAPI 工件包含冻结路径与响应")
    void commandSpecContainsFrozenPaths() throws Exception {
        String spec = read("config/conformance/api/command.openapi.yaml");

        assertThat(spec).contains("openapi: 3.1.0");
        assertThat(spec).contains("/commands/{commandType}");
        assertThat(spec).contains("/commands/{id}");
        assertThat(spec).contains("idempotency_key");
        assertThat(spec).contains("'202':");
        assertThat(spec).contains("'400':");
        assertThat(spec).contains("'409':");
        assertThat(spec).contains("locator");
        assertThat(spec).contains("CommandStatus");
    }

    @Test
    @DisplayName("查询 OpenAPI 工件包含时点参数与 Retry-After")
    void querySpecContainsTemporalParameters() throws Exception {
        String spec = read("config/conformance/api/query.openapi.yaml");

        assertThat(spec).contains("/objects/{objectType}/{id}");
        assertThat(spec).contains("/objects/{objectType}:");
        assertThat(spec).contains("ObjectPage");
        assertThat(spec).contains("as_of");
        assertThat(spec).contains("recorded_as_of");
        assertThat(spec).contains("min_version");
        assertThat(spec).contains("Retry-After");
    }

    @Test
    @DisplayName("列表控制器路径与分页/排序参数同工件一致")
    void listControllerMatchesSpec() throws Exception {
        String spec = read("config/conformance/api/query.openapi.yaml");
        Method list = ObjectQueryController.class.getMethod("list", String.class, Map.class);
        GetMapping mapping = list.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/objects/{objectType}");
        for (String parameter : List.of("page", "size", "sort", "direction", "as_of",
                "recorded_as_of")) {
            assertThat(spec).contains("name: " + parameter);
        }
    }

    @Test
    @DisplayName("命令控制器路径与方法同工件一致")
    void commandControllerMatchesSpec() throws Exception {
        Method submit = CommandController.class.getMethod("submit", String.class,
                CommandRequest.class);
        PostMapping post = submit.getAnnotation(PostMapping.class);
        assertThat(post).isNotNull();
        assertThat(post.value()).containsExactly("/commands/{commandType}");

        Method status = CommandController.class.getMethod("status", UUID.class);
        GetMapping get = status.getAnnotation(GetMapping.class);
        assertThat(get).isNotNull();
        assertThat(get.value()).containsExactly("/commands/{id}");
    }

    @Test
    @DisplayName("对象查询控制器路径与参数名同工件一致")
    void objectQueryControllerMatchesSpec() throws Exception {
        Method find = ObjectQueryController.class.getMethod("find", String.class, UUID.class,
                String.class, String.class, Long.class);
        GetMapping mapping = find.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/objects/{objectType}/{id}");

        Parameter[] parameters = find.getParameters();
        assertThat(requestParam(parameters[2])).isEqualTo("as_of");
        assertThat(requestParam(parameters[3])).isEqualTo("recorded_as_of");
        assertThat(requestParam(parameters[4])).isEqualTo("min_version");
    }

    @Test
    @DisplayName("请求/响应线格式使用 snake_case")
    void wireFormatUsesSnakeCase() {
        String command = JSON.writeValueAsString(new CommandRequest("probe.update", Map.of(),
                "k-1", List.of()));
        assertThat(command).contains("\"idempotency_key\"").doesNotContain("idempotencyKey");

        ObjectVersion version = new ObjectVersion(
                UUID.fromString("018f2e3a-1234-7000-8000-000000000001"), 1,
                Instant.parse("2026-10-08T09:00:00Z"), null,
                Instant.parse("2026-10-08T09:00:00Z"), null,
                UUID.fromString("018f2e3a-0000-7000-8000-000000000001"),
                Instant.parse("2026-10-08T09:00:00Z"), null, null);
        TemporalRow row = new TemporalRow("probe", version, Map.of("name", "Alice"));
        String object = JSON.writeValueAsString(ObjectQueryResponse.from(row));

        assertThat(object).contains("\"object_type\"").contains("\"valid_from\"")
                .contains("\"recorded_at\"").doesNotContain("objectType");
    }

    private static String requestParam(Parameter parameter) {
        RequestParam annotation = parameter.getAnnotation(RequestParam.class);
        assertThat(annotation).isNotNull();
        return annotation.value();
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative));
    }
}
