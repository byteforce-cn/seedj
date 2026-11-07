package cn.byteforce.seedj.starter.iam.abac;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamPolicyRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ABAC 管理面：策略 CRUD 与条件 AST 校验。 */
class IamPolicyAdminControllerTest {

    private final InMemoryIamPolicyRepository policies = new InMemoryIamPolicyRepository();

    private IamPolicyAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new IamPolicyAdminController(policies, IamProperties.defaults(),
                TestIdGenerators.sequential());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("create 写入策略并回显 id/条件 AST")
    void createStoresPolicyAndEchoesCondition() {
        Map<String, Object> created = controller.create(body("allow-active", Map.of(
                "name", "allow-active",
                "effect", "allow",
                "priority", 7,
                "actions", List.of("read", "list"),
                "resources", List.of("employee"),
                "condition", compareCondition("RESOURCE", "status", "EQ", "ACTIVE"),
                "enabled", "true")));

        assertThat(created.get("id")).isEqualTo("test-1");
        assertThat(created.get("name")).isEqualTo("allow-active");
        assertThat(created.get("effect")).isEqualTo("ALLOW");
        assertThat(created.get("priority")).isEqualTo(7);
        assertThat(created.get("enabled")).isEqualTo(true);
        assertThat(created.get("version")).isEqualTo(0L);
        assertThat((List<Object>) created.get("actions")).containsExactly("read", "list");
        assertThat((List<Object>) created.get("resources")).containsExactly("employee");
        assertThat(created.get("condition").toString()).contains("\"compare\"")
                .contains("ACTIVE");

        IamPolicy stored = policies.findById("test-1").orElseThrow();
        assertThat(stored.condition()).isInstanceOf(PolicyCondition.Compare.class);
    }

    @Test
    @DisplayName("create 缺省字段：name=policy、effect=DENY、condition=always")
    void createFillsDefaults() {
        Map<String, Object> created = controller.create(Map.of(
                "actions", List.of("read"),
                "resources", List.of("*")));

        assertThat(created.get("name")).isEqualTo("policy");
        assertThat(created.get("effect")).isEqualTo("DENY");
        assertThat(created.get("priority")).isEqualTo(0);
        assertThat(created.get("enabled")).isEqualTo(true);
        assertThat(created.get("condition").toString()).contains("always");
        assertThat(policies.findById("test-1").orElseThrow().condition())
                .isEqualTo(PolicyCondition.Always.INSTANCE);
    }

    @Test
    @DisplayName("create 缺 name 之外的非法输入分别报 POLICY_INVALID")
    void createRejectsInvalidBodies() {
        assertThatThrownBy(() -> controller.create(Map.of("name", "  ")))
                .isInstanceOfSatisfying(IamException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(IamErrorCode.POLICY_INVALID);
                    assertThat(exception.getMessage()).contains("policy name is required");
                });

        assertThatThrownBy(() -> controller.create(Map.of("name", "no-actions")))
                .isInstanceOfSatisfying(IamException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(IamErrorCode.POLICY_INVALID);
                    assertThat(exception.getMessage()).contains("invalid policy");
                });

        assertThatThrownBy(() -> controller.create(body("bad-condition", Map.of(
                "name", "bad-condition",
                "actions", List.of("read"),
                "resources", List.of("employee"),
                "condition", Map.of("type", "explode")))))
                .isInstanceOfSatisfying(IamException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(IamErrorCode.POLICY_INVALID);
                    assertThat(exception.getMessage()).contains("invalid policy condition");
                });
    }

    @Test
    @DisplayName("create 未知 effect 直接抛 IllegalArgumentException")
    void createRejectsUnknownEffect() {
        assertThatThrownBy(() -> controller.create(body("bad-effect", Map.of(
                "name", "bad-effect",
                "effect", "sometimes",
                "actions", List.of("read"),
                "resources", List.of("employee")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOMETIMES");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("update 覆盖给出的字段并保留 id")
    void updateKeepsIdentityAndUnspecifiedFields() {
        controller.create(body("allow-active", Map.of(
                "name", "allow-active",
                "effect", "ALLOW",
                "actions", List.of("read"),
                "resources", List.of("employee"))));

        Map<String, Object> updated = controller.update("test-1", Map.of(
                "priority", 42,
                "enabled", "false",
                "actions", List.of("read"),
                "resources", List.of("employee")));

        assertThat(updated.get("id")).isEqualTo("test-1");
        assertThat(updated.get("name")).isEqualTo("allow-active");
        assertThat(updated.get("effect")).isEqualTo("ALLOW");
        assertThat(updated.get("priority")).isEqualTo(42);
        assertThat(updated.get("enabled")).isEqualTo(false);
        assertThat((List<Object>) updated.get("actions")).containsExactly("read");
        assertThat(policies.findById("test-1").orElseThrow().enabled()).isFalse();
    }

    @Test
    @DisplayName("update 未知策略报 POLICY_NOT_FOUND")
    void updateUnknownPolicyFails() {
        assertThatThrownBy(() -> controller.update("nope", Map.of("name", "x")))
                .isInstanceOfSatisfying(IamException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(IamErrorCode.POLICY_NOT_FOUND));
    }

    @Test
    @DisplayName("get 未知策略报 POLICY_NOT_FOUND，已存在则映射")
    void getReturnsMappedPolicy() {
        controller.create(body("allow-active", Map.of(
                "name", "allow-active",
                "actions", List.of("read"),
                "resources", List.of("employee"))));

        assertThat(controller.get("test-1").get("name")).isEqualTo("allow-active");
        assertThatThrownBy(() -> controller.get("missing"))
                .isInstanceOfSatisfying(IamException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(IamErrorCode.POLICY_NOT_FOUND);
                    assertThat(exception.getMessage()).contains("missing");
                });
    }

    @Test
    @DisplayName("list 只返回当前租户的策略")
    void listReturnsTenantPolicies() {
        controller.create(body("allow-active", Map.of(
                "name", "allow-active",
                "actions", List.of("read"),
                "resources", List.of("employee"))));
        policies.save(IamPolicy.allow("other-tenant", "acme", "foreign", java.util.Set.of("read"),
                java.util.Set.of("employee"), PolicyCondition.Always.INSTANCE));

        assertThat(controller.list()).extracting(value -> value.get("id"))
                .containsExactly("test-1");
    }

    @Test
    @DisplayName("delete 返回 deleted 标识并移除策略")
    void deleteRemovesPolicy() {
        controller.create(body("allow-active", Map.of(
                "name", "allow-active",
                "actions", List.of("read"),
                "resources", List.of("employee"))));

        assertThat(controller.delete("test-1")).containsEntry("deleted", "test-1");
        assertThat(policies.findById("test-1")).isEmpty();
    }

    private static Map<String, Object> body(String name, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.putIfAbsent("name", name);
        return body;
    }

    private static Map<String, Object> compareCondition(String leftSource, String leftPath,
                                                        String operator, String rightValue) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("type", "compare");
        condition.put("left", Map.of("source", leftSource, "path", leftPath));
        condition.put("operator", operator);
        condition.put("right", Map.of("source", "LITERAL", "path", "", "value", rightValue));
        return condition;
    }
}
