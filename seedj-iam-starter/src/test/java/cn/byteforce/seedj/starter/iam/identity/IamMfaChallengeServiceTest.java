package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTotpRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamMfaChallengeServiceTest {

    private final InMemoryIamTotpRepository repository = new InMemoryIamTotpRepository();
    private final IamProperties.NativeAuth properties =
            new IamProperties.NativeAuth(true, true, Duration.ofMinutes(5), 2);
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final AtomicLong seq = new AtomicLong();
    private final IamMfaChallengeService service = new IamMfaChallengeService(repository,
            properties, Clock.fixed(start, ZoneOffset.UTC), () -> "challenge-" + seq.incrementAndGet());

    @Test
    void createsSingleUseChallengeWithHashedToken() {
        String token = service.create("u-1", "native-app", "device-1");

        IamMfaChallenge challenge = service.require(token);
        assertThat(challenge.userId()).isEqualTo("u-1");
        assertThat(challenge.clientId()).isEqualTo("native-app");
        assertThat(challenge.deviceId()).isEqualTo("device-1");
        assertThat(challenge.tokenHash()).isEqualTo(IamMfaChallengeService.hash(token));
        assertThat(challenge.tokenHash()).isNotEqualTo(token);
        assertThat(challenge.expiresAt()).isEqualTo(start.plus(Duration.ofMinutes(5)));
        assertThat(challenge.maxAttempts()).isEqualTo(2);

        service.consume(challenge);
        assertThatThrownBy(() -> service.require(token))
                .isInstanceOf(IamException.class)
                .hasMessage("mfa_token already used");
    }

    @Test
    void rejectsMissingUnknownExpiredAndLockedChallenges() {
        assertError(null, IamErrorCode.MFA_CHALLENGE_INVALID, "mfa_token is required");
        assertError("  ", IamErrorCode.MFA_CHALLENGE_INVALID, "mfa_token is required");
        assertError("unknown-token", IamErrorCode.MFA_CHALLENGE_INVALID, "invalid mfa_token");

        String expired = service.create("u-2", "native-app", null);
        IamMfaChallengeService later = new IamMfaChallengeService(repository, properties,
                Clock.fixed(start.plus(Duration.ofMinutes(6)), ZoneOffset.UTC), () -> "challenge-x");
        assertError(later, expired, IamErrorCode.MFA_CHALLENGE_EXPIRED, "mfa_token expired");

        String locked = service.create("u-3", "native-app", null);
        IamMfaChallenge challenge = service.require(locked);
        service.failure(challenge);
        service.failure(service.require(locked));
        assertError(service, locked, IamErrorCode.MFA_CHALLENGE_LOCKED, "mfa_token locked");
    }

    @Test
    void defaultsClockWhenNull() {
        IamMfaChallengeService systemClock = new IamMfaChallengeService(repository, properties,
                null, () -> "challenge-y");

        String token = systemClock.create("u-4", "native-app", null);

        assertThat(systemClock.require(token).expiresAt()).isAfter(Instant.now());
    }

    @Test
    void hashIsStableAndHexEncoded() {
        assertThat(IamMfaChallengeService.hash("token")).hasSize(64)
                .isEqualTo(IamMfaChallengeService.hash("token"))
                .isNotEqualTo(IamMfaChallengeService.hash("other"));
    }

    private void assertError(String token, IamErrorCode code, String message) {
        assertError(service, token, code, message);
    }

    private void assertError(IamMfaChallengeService target, String token, IamErrorCode code,
                             String message) {
        assertThatThrownBy(() -> target.require(token))
                .isInstanceOf(IamException.class)
                .hasMessage(message)
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(code);
    }
}
