package cn.byteforce.seedj.starter.ai.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ExternalEffect;
import cn.byteforce.seedj.ai.ExternalEffectState;
import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.infra.local.ai.InMemoryExternalEffectStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 两段协议：已确认不重调、失败可重试、U7 审计尽力而为。 */
class ExternalEffectServiceTest {

    private static final UUID DECISION = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private final InMemoryExternalEffectStore store = new InMemoryExternalEffectStore();

    @Test
    @DisplayName("已确认后重试不再调用外部")
    void confirmedEffectIsNotInvokedAgain() {
        AtomicInteger calls = new AtomicInteger();
        ExternalEffectService service = service(null);
        ExternalEffectInvoker invoker =
                call -> {
                    calls.incrementAndGet();
                    return new ExternalEffectResult("ext-1");
                };

        assertThat(service.execute(call(), invoker).externalRef()).isEqualTo("ext-1");
        assertThat(service.execute(call(), invoker).externalRef()).isEqualTo("ext-1");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("invoker 收到 decision_id 与 actor")
    void invokerReceivesDecisionIdAndActor() {
        AtomicReference<ExternalEffectCall> seen = new AtomicReference<>();
        ExternalEffectService service = service(null);

        service.execute(
                call(),
                call -> {
                    seen.set(call);
                    return new ExternalEffectResult("ext-2");
                });

        assertThat(seen.get().decisionId()).isEqualTo(DECISION);
        assertThat(seen.get().actor()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("外部失败：落 FAILED 并原样抛出")
    void failureRecordsFailedAndRethrows() {
        ExternalEffectService service = service(null);
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(
                        () ->
                                service.execute(
                                        call(),
                                        c -> {
                                            throw boom;
                                        }))
                .isSameAs(boom);

        ExternalEffect effect = store.find(DECISION).orElseThrow();
        assertThat(effect.state()).isEqualTo(ExternalEffectState.FAILED);
        assertThat(effect.errorCode()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("U7 审计带 decision_id 与 actor")
    void auditCarriesDecisionAndActor() {
        AtomicReference<AuditAppendRequest<String>> captured = new AtomicReference<>();
        AuditAppender<String> audit = captured::set;
        ExternalEffectService service = service(audit);

        service.execute(call(), c -> new ExternalEffectResult("ext-3"));

        assertThat(captured.get().getAction()).isEqualTo("agent.external_effect");
        assertThat(captured.get().getActor()).isEqualTo(ACTOR.toString());
        assertThat(captured.get().getPayload().get("decision_id").asString())
                .isEqualTo(DECISION.toString());
        assertThat(captured.get().getPayload().get("state").asString()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("审计失败不回滚已确认效果")
    void auditFailureDoesNotRollbackEffect() {
        AuditAppender<String> failing =
                request -> {
                    throw new IllegalStateException("audit down");
                };
        ExternalEffectService service = service(failing);

        assertThat(service.execute(call(), c -> new ExternalEffectResult("ext-4")).externalRef())
                .isEqualTo("ext-4");
        assertThat(store.find(DECISION).orElseThrow().state())
                .isEqualTo(ExternalEffectState.CONFIRMED);
    }

    private ExternalEffectService service(AuditAppender<String> audit) {
        Clock clock = () -> NOW;
        return new ExternalEffectService(store, audit, clock);
    }

    private static ExternalEffectCall call() {
        return new ExternalEffectCall(
                DECISION, TENANT, ACTOR, "billing.refund", "sha256:a", Map.of("amount", 100));
    }
}
