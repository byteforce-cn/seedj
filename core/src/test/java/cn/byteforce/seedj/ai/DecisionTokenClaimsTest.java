package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 决策 token claim 的构造口径：版本固定为 1，审批快照与动作分类必须同向。
 *
 * <p>这里是“签不出坏 token”的最后一道构造期校验：守卫的错误码表里没有“审批未通过”， 因此未通过的审批必须在签发前就被拒绝，而不是留给守卫。
 */
class DecisionTokenClaimsTest {

    private static final UUID DECISION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INTENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ON_BEHALF_OF = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TENANT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ARGS_HASH = "sha256:" + "a".repeat(64);

    /** claim 的构造脚手架：默认值全部合法，测试只改动被测组件。 */
    private static final class Fields {
        private int v = DecisionTokenClaims.VERSION;
        private UUID decisionId = DECISION;
        private UUID ledgerIntentId = INTENT;
        private CommandRef command = new CommandRef("ticket.create", List.of(TENANT), 7L);
        private String argsHash = ARGS_HASH;
        private UUID actor = ACTOR;
        private UUID onBehalfOf = ON_BEHALF_OF;
        private String tenant = TENANT.toString();
        private ActionClass actionClass = ActionClass.REVERSIBLE;
        private PolicyRef policy = PolicyRef.none();
        private ApprovalSnapshot approval;
        private Instant issuedAt = ISSUED_AT;
        private Instant expiresAt = ISSUED_AT.plusSeconds(60);
        private String audience = "seedj-server";

        private DecisionTokenClaims build() {
            return new DecisionTokenClaims(
                    v,
                    decisionId,
                    ledgerIntentId,
                    command,
                    argsHash,
                    actor,
                    onBehalfOf,
                    tenant,
                    actionClass,
                    policy,
                    approval,
                    issuedAt,
                    expiresAt,
                    audience);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    @DisplayName("claim 版本不是 1 时拒绝")
    void unsupported_claim_version_is_rejected(int version) {
        Fields fields = new Fields();
        fields.v = version;

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported claim version: " + version);
    }

    @Test
    @DisplayName("需要审批的动作分类缺少审批快照时拒绝")
    void approval_requiring_action_requires_snapshot() {
        Fields fields = new Fields();
        fields.actionClass = ActionClass.IRREVERSIBLE_EXTERNAL;

        assertThatThrownBy(fields::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approval");
    }

    @ParameterizedTest
    @EnumSource(
            value = ApprovalStatus.class,
            names = {"PENDING", "REJECTED", "EXPIRED", "UNKNOWN"})
    @DisplayName("需要审批的动作分类只接受已批准的审批结论")
    void non_approved_status_is_rejected_for_approval_requiring_action(ApprovalStatus status) {
        Fields fields = new Fields();
        fields.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
        fields.approval = approval(status);

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved")
                .hasMessageContaining(status.wire());
    }

    @Test
    @DisplayName("不需要审批的动作分类不得携带审批快照")
    void non_approval_action_rejects_snapshot() {
        Fields fields = new Fields();
        fields.actionClass = ActionClass.COMPENSABLE;
        fields.approval = approval(ApprovalStatus.APPROVED);

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approval");
    }

    @Test
    @DisplayName("不可逆动作携带已批准快照时签发成功")
    void irreversible_action_with_approved_snapshot_is_accepted() {
        Fields fields = new Fields();
        fields.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
        fields.approval = approval(ApprovalStatus.APPROVED);

        DecisionTokenClaims claims = fields.build();

        assertThat(claims.v()).isEqualTo(DecisionTokenClaims.VERSION);
        assertThat(claims.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"argsHash", "tenant", "audience"})
    @DisplayName("必填文本 claim 为空白时按 claim 名拒绝")
    void blank_text_claim_is_rejected(String claim) {
        Fields fields = new Fields();
        switch (claim) {
            case "argsHash" -> fields.argsHash = " ";
            case "tenant" -> fields.tenant = "";
            case "audience" -> fields.audience = " ";
            default -> throw new IllegalArgumentException("unknown claim: " + claim);
        }

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(claim);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "decisionId",
                "ledgerIntentId",
                "command",
                "actor",
                "onBehalfOf",
                "actionClass",
                "issuedAt",
                "expiresAt"
            })
    @DisplayName("必填 claim 缺失时按 claim 名拒绝")
    void mandatory_claim_is_rejected(String claim) {
        Fields fields = new Fields();
        switch (claim) {
            case "decisionId" -> fields.decisionId = null;
            case "ledgerIntentId" -> fields.ledgerIntentId = null;
            case "command" -> fields.command = null;
            case "actor" -> fields.actor = null;
            case "onBehalfOf" -> fields.onBehalfOf = null;
            case "actionClass" -> fields.actionClass = null;
            case "issuedAt" -> fields.issuedAt = null;
            case "expiresAt" -> fields.expiresAt = null;
            default -> throw new IllegalArgumentException("unknown claim: " + claim);
        }

        assertThatThrownBy(fields::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(claim);
    }

    private static ApprovalSnapshot approval(ApprovalStatus status) {
        boolean approved = status == ApprovalStatus.APPROVED;
        return new ApprovalSnapshot(
                "fixture-instance",
                "fixture-task",
                status,
                ISSUED_AT,
                approved ? ACTOR : null,
                approved ? ISSUED_AT : null,
                ARGS_HASH,
                3L);
    }
}
