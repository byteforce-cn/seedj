package cn.byteforce.seedj.starter.ai.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.PolicyRef;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置驱动的策略端口：钉版本、不猜、缺配置点名。
 *
 * <p>断言只看「引用里有什么」与「head 认识谁」——这两个形状是账本、token 与守卫共享的契约，改一个就会让
 * {@code policy_stale} 的语义漂移。
 */
class ConfiguredPolicyEvaluatorTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID DECISION = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID CONTRACT = UUID.fromString("018f2e3a-1234-7000-8000-00000000c001");
    private static final UUID CONVERSATION =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000e001");

    @Test
    @DisplayName("启用时把配置里的版本钉进策略引用，结论是 allow")
    void pins_configured_head_and_allows() {
        PolicyRef ref = new ConfiguredPolicyEvaluator(enabled()).evaluate(request());

        assertThat(ref.engine()).isEqualTo("local");
        assertThat(ref.bundle()).isEqualTo("ticket-policy");
        assertThat(ref.bundleVersion()).isEqualTo(3L);
        assertThat(ref.epoch()).isEqualTo(41L);
        assertThat(ref.verdict()).isEqualTo("allow");
        assertThat(ref.ruleIds()).isEmpty();
    }

    @Test
    @DisplayName("未启用时返回 none()：账本与 token 保持 T0 形态")
    void disabled_returns_none() {
        PolicyRef ref = new ConfiguredPolicyEvaluator(disabled()).evaluate(request());

        assertThat(ref).isEqualTo(PolicyRef.none());
    }

    @Test
    @DisplayName("启用但缺 bundle：构造期失败并点名配置键")
    void missing_bundle_names_the_key() {
        PolicyProperties properties = new PolicyProperties(true, "local", null, 3L, 41L, null);

        assertThatThrownBy(() -> new ConfiguredPolicyEvaluator(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seedj.ai.policy.bundle");
    }

    @Test
    @DisplayName("启用但缺 version：构造期失败并点名配置键")
    void missing_version_names_the_key() {
        PolicyProperties properties =
                new PolicyProperties(true, "local", "ticket-policy", null, null, null);

        assertThatThrownBy(() -> new ConfiguredPolicyEvaluator(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seedj.ai.policy.version");
    }

    @Test
    @DisplayName("head 源只认识配置里那一个 engine + bundle")
    void head_source_only_knows_configured_bundle() {
        PolicyHeadSource heads = new ConfiguredPolicyHeadSource(enabled());

        assertThat(heads.head("local", "ticket-policy"))
                .hasValueSatisfying(
                        head -> {
                            assertThat(head.version()).isEqualTo(3L);
                            assertThat(head.epoch()).isEqualTo(41L);
                        });
        assertThat(heads.head("local", "other-policy")).isEmpty();
        assertThat(heads.head("coord", "ticket-policy")).isEmpty();
    }

    @Test
    @DisplayName("未启用时 head 源为空：守卫没有任何可比对的 head")
    void disabled_head_source_is_empty() {
        PolicyHeadSource heads = new ConfiguredPolicyHeadSource(disabled());

        assertThat(heads.head("local", "ticket-policy")).isEmpty();
    }

    @Test
    @DisplayName("求值输入必须带命令类型与参数指纹：空白即拒绝")
    void request_requires_command_and_args_hash() {
        assertThatThrownBy(
                        () ->
                                new PolicyRequest(
                                        TENANT,
                                        DECISION,
                                        CONTRACT,
                                        CONVERSATION,
                                        1,
                                        DecisionKind.EXECUTE_TOOL,
                                        ActionClass.REVERSIBLE,
                                        " ",
                                        "sha256:abc",
                                        "gpt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandType");
    }

    private static PolicyProperties enabled() {
        return new PolicyProperties(
                true, "local", "ticket-policy", 3L, 41L, PolicyProperties.Verify.IRREVERSIBLE);
    }

    private static PolicyProperties disabled() {
        return new PolicyProperties(
                false, "local", "ticket-policy", 3L, 41L, PolicyProperties.Verify.IRREVERSIBLE);
    }

    private static PolicyRequest request() {
        return new PolicyRequest(
                TENANT,
                DECISION,
                CONTRACT,
                CONVERSATION,
                1,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.COMPENSABLE,
                "ticket.create",
                "sha256:abc",
                "gpt");
    }
}
