package cn.byteforce.seedj.starter.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.totp.Base32;
import cn.byteforce.seedj.iam.totp.TotpAlgorithm;
import cn.byteforce.seedj.iam.totp.TotpGenerator;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.AesGcmIamSecretProtector;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTotpRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class IamTotpServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamTotpRepository totp = new InMemoryIamTotpRepository();
    private final IamPasswordHasher hasher =
            new IamPasswordHasherAdapter(
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AesGcmIamSecretProtector protector =
            new AesGcmIamSecretProtector(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "test");
    private final IamProperties.Totp properties =
            new IamProperties.Totp(true, "Seedj", 6, Duration.ofSeconds(30), 1, 3, null);
    private final Instant start = Instant.ofEpochSecond(1_700_000_000L);
    private final Clock clock = Clock.fixed(start, ZoneOffset.UTC);
    private final java.util.concurrent.atomic.AtomicLong seq =
            new java.util.concurrent.atomic.AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamTotpService service =
            new IamTotpService(
                    totp,
                    users,
                    protector,
                    hasher,
                    properties,
                    new InMemoryIamAuditSink(),
                    clock,
                    idGenerator);
    private final IamUser alice =
            users.create(
                    IamUser.create(
                            "u-alice", "default", "alice", "Alice", "alice@example.com", true),
                    hasher.encode("alice-password"));

    @Test
    void enrollConfirmVerifyAndRejectReplay() {
        var enrollment = service.enroll(alice.id());
        String code =
                TotpGenerator.generate(
                        Base32.decode(enrollment.secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);

        var recoveryCodes = service.confirm(alice.id(), code);
        assertThat(recoveryCodes).hasSize(3);
        assertThat(service.status(alice.id()).enabled()).isTrue();
        assertThat(service.status(alice.id()).recoveryCodesRemaining()).isEqualTo(3);

        assertThatThrownBy(() -> service.verify(alice.id(), code))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void acceptsNextWindowAndConsumesRecoveryCodeOnce() {
        var enrollment = service.enroll(alice.id());
        String first =
                TotpGenerator.generate(
                        Base32.decode(enrollment.secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        var recoveryCodes = service.confirm(alice.id(), first);

        Clock next = Clock.fixed(start.plusSeconds(30), ZoneOffset.UTC);
        IamTotpService later =
                new IamTotpService(
                        totp,
                        users,
                        protector,
                        hasher,
                        properties,
                        new InMemoryIamAuditSink(),
                        next,
                        idGenerator);
        String second =
                TotpGenerator.generate(
                        Base32.decode(enrollment.secret()),
                        next.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        later.verify(alice.id(), second);

        assertThat(later.verifyRecoveryCode(alice.id(), recoveryCodes.get(0))).isTrue();
        assertThatThrownBy(() -> later.verifyRecoveryCode(alice.id(), recoveryCodes.get(0)))
                .isInstanceOf(IamException.class);
    }

    @Test
    void disableRemovesEnrollment() {
        var enrollment = service.enroll(alice.id());
        String code =
                TotpGenerator.generate(
                        Base32.decode(enrollment.secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        service.confirm(alice.id(), code);

        service.disable(alice.id());

        assertThat(service.status(alice.id()).enabled()).isFalse();
    }

    @Test
    void enrollTwiceIsRejectedOnceConfirmed() {
        String code =
                TotpGenerator.generate(
                        Base32.decode(service.enroll(alice.id()).secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        service.confirm(alice.id(), code);

        assertThatThrownBy(() -> service.enroll(alice.id()))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("already enabled")
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(cn.byteforce.seedj.iam.IamErrorCode.TOTP_ALREADY_ENROLLED);
    }

    @Test
    void confirmTwiceIsRejectedAfterTheFirstSuccess() {
        String code =
                TotpGenerator.generate(
                        Base32.decode(service.enroll(alice.id()).secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        service.confirm(alice.id(), code);

        assertThatThrownBy(() -> service.confirm(alice.id(), code))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("already enabled")
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(cn.byteforce.seedj.iam.IamErrorCode.TOTP_ALREADY_ENROLLED);
    }

    @Test
    void confirmRequiresAnEnrollmentAndAValidCode() {
        assertThatThrownBy(() -> service.confirm(alice.id(), "123456"))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("has not started");

        service.enroll(alice.id());
        assertThatThrownBy(() -> service.confirm(alice.id(), "000000"))
                .isInstanceOf(IamException.class)
                .hasMessage("invalid TOTP code")
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(cn.byteforce.seedj.iam.IamErrorCode.TOTP_INVALID);
        assertThat(service.isEnabled(alice.id())).isFalse();
        assertThat(service.status(alice.id()).enabled()).isFalse();
    }

    @Test
    void verifyRequiresConfirmedEnrollmentAndRejectsInvalidCodes() {
        assertThatThrownBy(() -> service.verify(alice.id(), "123456"))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("not enabled");

        String code =
                TotpGenerator.generate(
                        Base32.decode(service.enroll(alice.id()).secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        service.confirm(alice.id(), code);

        assertThatThrownBy(() -> service.verify(alice.id(), "000000"))
                .isInstanceOf(IamException.class)
                .hasMessage("invalid TOTP code")
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(cn.byteforce.seedj.iam.IamErrorCode.TOTP_INVALID);
    }

    @Test
    void unknownUsersAreRejectedByEveryOperation() {
        for (Runnable operation :
                new Runnable[] {
                    () -> service.enroll("ghost"),
                    () -> service.confirm("ghost", "123456"),
                    () -> service.verify("ghost", "123456"),
                    () -> service.verifyRecoveryCode("ghost", "code"),
                    () -> service.disable("ghost")
                }) {
            assertThatThrownBy(operation::run)
                    .isInstanceOf(IamException.class)
                    .hasMessage("user not found: ghost")
                    .extracting(exception -> ((IamException) exception).getCode())
                    .isEqualTo(cn.byteforce.seedj.iam.IamErrorCode.USER_NOT_FOUND);
        }
    }

    @Test
    void toleratesMissingOrFailingAuditSinkAndNullClock() {
        IamTotpService withoutAudit =
                new IamTotpService(
                        totp, users, protector, hasher, properties, null, null, idGenerator);
        cn.byteforce.seedj.core.security.SecurityAuditSink failing =
                event -> {
                    throw new IllegalStateException("audit down");
                };
        IamTotpService withFailingAudit =
                new IamTotpService(
                        totp, users, protector, hasher, properties, failing, clock, idGenerator);

        var enrollment = withoutAudit.enroll(alice.id());
        String code =
                TotpGenerator.generate(
                        Base32.decode(enrollment.secret()),
                        Instant.now(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);

        assertThat(withoutAudit.confirm(alice.id(), code)).hasSize(3);
        assertThat(withoutAudit.status(alice.id()).enabled()).isTrue();

        // A failing audit sink must not turn disable into an error.
        withFailingAudit.disable(alice.id());

        assertThat(withoutAudit.status(alice.id()).enabled()).isFalse();
    }

    @Test
    void statusCountsOnlyUnusedRecoveryCodes() {
        String code =
                TotpGenerator.generate(
                        Base32.decode(service.enroll(alice.id()).secret()),
                        clock.instant(),
                        6,
                        30,
                        TotpAlgorithm.SHA1);
        var codes = service.confirm(alice.id(), code);

        assertThat(service.status(alice.id()).recoveryCodesRemaining()).isEqualTo(3);
        service.verifyRecoveryCode(alice.id(), codes.get(0));
        assertThat(service.status(alice.id()).recoveryCodesRemaining()).isEqualTo(2);
        assertThatThrownBy(() -> service.verifyRecoveryCode(alice.id(), "INVALID-CODE"))
                .isInstanceOf(IamException.class)
                .hasMessage("invalid recovery code")
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(cn.byteforce.seedj.iam.IamErrorCode.RECOVERY_CODE_INVALID);
        assertThatThrownBy(() -> service.verifyRecoveryCode(alice.id(), null))
                .isInstanceOf(IamException.class)
                .hasMessage("invalid recovery code");
    }
}
