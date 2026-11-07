package cn.byteforce.seedj.iam.spi;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamRecoveryCode;
import cn.byteforce.seedj.iam.IamTotpState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link IamTotpRepository} 契约测试套件：TOTP 状态、重放保护、恢复码与 MFA 挑战。 */
public abstract class IamTotpRepositoryContractTest {

    protected abstract IamTotpRepository repository();

    private static IamTotpState state(String userId) {
        return new IamTotpState(
                userId,
                "default",
                "k1",
                "cipher",
                false,
                -1,
                Instant.parse("2026-11-06T00:00:00Z"),
                null);
    }

    @Test
    void savesFindsAndDeletesTotpState() {
        IamTotpState state = state("u1");

        repository().saveTotp(state);

        assertThat(repository().findTotp("u1")).contains(state);
        assertThat(repository().findTotp("u2")).isEmpty();

        repository().deleteTotp("u1");

        assertThat(repository().findTotp("u1")).isEmpty();
    }

    @Test
    void advancingLastUsedStepRejectsReplays() {
        repository().saveTotp(state("u1"));

        assertThat(repository().advanceLastUsedStep("u1", 100)).isTrue();
        assertThat(repository().findTotp("u1").orElseThrow().lastUsedStep()).isEqualTo(100);

        assertThat(repository().advanceLastUsedStep("u1", 100)).isFalse();
        assertThat(repository().advanceLastUsedStep("u1", 99)).isFalse();
        assertThat(repository().advanceLastUsedStep("u1", 101)).isTrue();

        assertThat(repository().advanceLastUsedStep("u2", 5)).isFalse();
    }

    @Test
    void replacesAndFindsRecoveryCodes() {
        IamRecoveryCode first =
                new IamRecoveryCode("u1", "hash-1", false, Instant.parse("2026-11-06T00:00:00Z"));
        IamRecoveryCode second =
                new IamRecoveryCode("u1", "hash-2", false, Instant.parse("2026-11-06T00:00:00Z"));

        repository().replaceRecoveryCodes("u1", List.of(first, second));

        assertThat(repository().findRecoveryCodes("u1")).containsExactly(first, second);
        assertThat(repository().findRecoveryCodes("u2")).isEmpty();

        repository().replaceRecoveryCodes("u1", List.of(second));

        assertThat(repository().findRecoveryCodes("u1")).containsExactly(second);
    }

    @Test
    void marksOnlyMatchingRecoveryCodeUsed() {
        repository()
                .replaceRecoveryCodes(
                        "u1",
                        List.of(
                                new IamRecoveryCode(
                                        "u1",
                                        "hash-1",
                                        false,
                                        Instant.parse("2026-11-06T00:00:00Z")),
                                new IamRecoveryCode(
                                        "u1",
                                        "hash-2",
                                        false,
                                        Instant.parse("2026-11-06T00:00:00Z"))));

        repository().markRecoveryCodeUsed("u1", "hash-1");
        repository().markRecoveryCodeUsed("unknown", "hash-1");

        assertThat(repository().findRecoveryCodes("u1"))
                .extracting(IamRecoveryCode::used)
                .containsExactly(true, false);
    }

    @Test
    void deletingTotpStateAlsoDropsRecoveryCodes() {
        repository().saveTotp(state("u1"));
        repository()
                .replaceRecoveryCodes(
                        "u1",
                        List.of(
                                new IamRecoveryCode(
                                        "u1",
                                        "hash-1",
                                        false,
                                        Instant.parse("2026-11-06T00:00:00Z"))));

        repository().deleteTotp("u1");

        assertThat(repository().findTotp("u1")).isEmpty();
        assertThat(repository().findRecoveryCodes("u1")).isEmpty();
    }

    @Test
    void savesAndUpdatesChallenges() {
        IamMfaChallenge challenge =
                new IamMfaChallenge(
                        "c1",
                        "u1",
                        "web",
                        "device-1",
                        "token-hash",
                        Instant.parse("2026-11-06T00:05:00Z"),
                        false,
                        0,
                        5);

        repository().saveChallenge(challenge);

        assertThat(repository().findChallengeByTokenHash("token-hash")).contains(challenge);

        IamMfaChallenge attempted = challenge.withAttempt();
        repository().updateChallenge(attempted);

        assertThat(repository().findChallengeByTokenHash("token-hash"))
                .get()
                .extracting(IamMfaChallenge::attempts)
                .isEqualTo(1);
    }
}
