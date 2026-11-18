package cn.byteforce.seedj.it.ai;

import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.AGENT_ID;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.AUDIENCE;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.TENANT;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AiErrorCode;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 直连绕过测试：<b>没有合法 DecisionToken 就写不进数据库</b>（spec §7.3 的 fail-closed 校验）。
 *
 * <p>四条用例对应四种绕过尝试：不带凭据、改参数、换目标、用过期凭据。每条都同时断言两件事——HTTP 层面的状态码与错误码， 以及<b>业务库与审计链没有新增行</b>：只断言 4xx
 * 会漏掉「拒绝了但已经写了一部分」这类事故，计数基线才是真正的证据。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Testcontainers
@SpringBootTest(
        classes = PilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"pilot", "test"})
class AiBypassIT extends AbstractPilotIT {

    private static final UUID TARGET = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");

    private static final UUID OTHER_TARGET =
            UUID.fromString("018f2e3a-0000-7000-8000-000000007732");

    @Container static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registerDatasource(registry, POSTGRES);
    }

    @Test
    @DisplayName("不带决策 token 的写命令：401 + AgentDecision 质询，业务库与审计链无新增")
    void command_without_token_is_rejected() {
        String title = "no-token-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("title", title, "status", "open");
        int commands = commandRecordCount();
        int audits = auditEntryCount();

        ResponseEntity<String> response =
                submit("ticket.create", payload, List.of(), null, UUID.randomUUID(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(challenge(response)).isEqualTo(AgentGuardFilter.CHALLENGE_SCHEME);
        assertThat(commandRecordCount()).isEqualTo(commands);
        assertThat(auditEntryCount()).isEqualTo(audits);
    }

    @Test
    @DisplayName("换掉载荷一个字段：403 args_hash_mismatch，无新增行")
    void tampered_payload_is_rejected() {
        UUID marker = UUID.randomUUID();
        Map<String, Object> payload = Map.of("title", "tampered-" + marker, "status", "open");
        Decision decision = allowExecute("ticket.create", payload, List.of(), null);
        String tamperedTitle = "tampered-other-" + marker;
        Map<String, Object> tampered = Map.of("title", tamperedTitle, "status", "open");
        int commands = commandRecordCount();
        int audits = auditEntryCount();

        ResponseEntity<String> response =
                submit(
                        "ticket.create",
                        tampered,
                        List.of(),
                        null,
                        decision.decisionId(),
                        decision.token());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo(AiErrorCode.ARGS_HASH_MISMATCH.wire());
        assertThat(commandRecordCount()).isEqualTo(commands);
        assertThat(auditEntryCount()).isEqualTo(audits);
    }

    @Test
    @DisplayName("换掉 targets：403 targets_mismatch，无新增行")
    void swapped_target_is_rejected() {
        String title = "targets-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("title", title, "status", "open");
        Decision decision = allowExecute("ticket.create", payload, List.of(TARGET), null);
        int commands = commandRecordCount();
        int audits = auditEntryCount();

        ResponseEntity<String> response =
                submit(
                        "ticket.create",
                        payload,
                        List.of(OTHER_TARGET),
                        null,
                        decision.decisionId(),
                        decision.token());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo(AiErrorCode.TARGETS_MISMATCH.wire());
        assertThat(commandRecordCount()).isEqualTo(commands);
        assertThat(auditEntryCount()).isEqualTo(audits);
    }

    @Test
    @DisplayName("过期 token：403 token_expired，无新增行")
    void expired_token_is_rejected() {
        String title = "expired-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("title", title, "status", "open");
        UUID decisionId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
        CommandFingerprint fingerprint =
                new CommandFingerprint("ticket.create", payload, List.of(), null);
        DecisionTokenClaims claims =
                new DecisionTokenClaims(
                        DecisionTokenClaims.VERSION,
                        decisionId,
                        UUID.randomUUID(),
                        new CommandRef("ticket.create", List.of(), null),
                        ArgsHash.of(fingerprint),
                        AGENT_ID,
                        USER_ID,
                        TENANT.toString(),
                        ActionClass.COMPENSABLE,
                        PolicyRef.none(),
                        null,
                        issuedAt,
                        issuedAt.plus(Duration.ofMinutes(5)),
                        AUDIENCE);
        String expired = issuer.sign(claims);
        int commands = commandRecordCount();
        int audits = auditEntryCount();

        ResponseEntity<String> response =
                submit("ticket.create", payload, List.of(), null, decisionId, expired);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo(AiErrorCode.TOKEN_EXPIRED.wire());
        assertThat(commandRecordCount()).isEqualTo(commands);
        assertThat(auditEntryCount()).isEqualTo(audits);
    }
}
