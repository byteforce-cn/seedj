package cn.byteforce.seedj.iam.identity;

import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.memory.AesGcmIamSecretProtector;
import cn.byteforce.seedj.iam.memory.InMemoryIamAuditSink;
import cn.byteforce.seedj.iam.memory.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.iam.memory.InMemoryIamTotpRepository;
import cn.byteforce.seedj.iam.memory.InMemoryIamUserRepository;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.totp.Base32;
import cn.byteforce.seedj.iam.totp.TotpAlgorithm;
import cn.byteforce.seedj.iam.totp.TotpGenerator;
import cn.byteforce.seedj.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamTotpServiceTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamTotpRepository totp = new InMemoryIamTotpRepository();
    private final IamPasswordHasher hasher = new IamPasswordHasherAdapter(
            PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final AesGcmIamSecretProtector protector =
            new AesGcmIamSecretProtector("0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.UTF_8), "test");
    private final IamProperties.Totp properties = new IamProperties.Totp(true, "Seedj", 6,
            Duration.ofSeconds(30), 1, 3, null);
    private final Instant start = Instant.ofEpochSecond(1_700_000_000L);
    private final Clock clock = Clock.fixed(start, ZoneOffset.UTC);
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "id-" + seq.incrementAndGet();
    private final IamTotpService service = new IamTotpService(totp, users, protector, hasher,
            properties, new InMemoryIamAuditSink(), clock, idGenerator);
    private final IamUser alice = users.create(IamUser.create("u-alice", "default", "alice", "Alice",
            "alice@example.com", true), hasher.encode("alice-password"));

    @Test
    void enrollConfirmVerifyAndRejectReplay() {
        var enrollment = service.enroll(alice.id());
        String code = TotpGenerator.generate(Base32.decode(enrollment.secret()), clock.instant(),
                6, 30, TotpAlgorithm.SHA1);

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
        String first = TotpGenerator.generate(Base32.decode(enrollment.secret()), clock.instant(),
                6, 30, TotpAlgorithm.SHA1);
        var recoveryCodes = service.confirm(alice.id(), first);

        Clock next = Clock.fixed(start.plusSeconds(30), ZoneOffset.UTC);
        IamTotpService later = new IamTotpService(totp, users, protector, hasher, properties,
                new InMemoryIamAuditSink(), next, idGenerator);
        String second = TotpGenerator.generate(Base32.decode(enrollment.secret()), next.instant(),
                6, 30, TotpAlgorithm.SHA1);
        later.verify(alice.id(), second);

        assertThat(later.verifyRecoveryCode(alice.id(), recoveryCodes.get(0))).isTrue();
        assertThatThrownBy(() -> later.verifyRecoveryCode(alice.id(), recoveryCodes.get(0)))
                .isInstanceOf(IamException.class);
    }

    @Test
    void disableRemovesEnrollment() {
        var enrollment = service.enroll(alice.id());
        String code = TotpGenerator.generate(Base32.decode(enrollment.secret()), clock.instant(),
                6, 30, TotpAlgorithm.SHA1);
        service.confirm(alice.id(), code);

        service.disable(alice.id());

        assertThat(service.status(alice.id()).enabled()).isFalse();
    }
}
