package cn.byteforce.seedj.iam.identity;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamRecoveryCode;
import cn.byteforce.seedj.iam.IamTotpState;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.iam.totp.Base32;
import cn.byteforce.seedj.iam.totp.TotpAlgorithm;
import cn.byteforce.seedj.iam.totp.TotpEnrollment;
import cn.byteforce.seedj.iam.totp.TotpGenerator;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** TOTP enrollment, verification, recovery codes, and disable. */
public class IamTotpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final IamTotpRepository totp;
    private final IamUserRepository users;
    private final IamSecretProtector protector;
    private final IamPasswordHasher hasher;
    private final IamProperties.Totp properties;
    private final SecurityAuditSink audit;
    private final Clock clock;
    private final IdGenerator<String> idGenerator;

    public IamTotpService(IamTotpRepository totp, IamUserRepository users,
                          IamSecretProtector protector, IamPasswordHasher hasher,
                          IamProperties.Totp properties, SecurityAuditSink audit, Clock clock,
                          IdGenerator<String> idGenerator) {
        this.totp = totp;
        this.users = users;
        this.protector = protector;
        this.hasher = hasher;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idGenerator = idGenerator;
    }

    public TotpEnrollment enroll(String userId) {
        IamUser user = requireUser(userId);
        IamTotpState existing = totp.findTotp(userId).orElse(null);
        if (existing != null && existing.confirmed()) {
            throw new IamException(IamErrorCode.TOTP_ALREADY_ENROLLED,
                    "TOTP is already enabled for this user");
        }
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        String encoded = Base32.encode(secret);
        IamTotpState state = new IamTotpState(userId, user.tenantId(), protector.keyName(),
                protector.protect(encoded, userId), false, -1, clock.instant(), null);
        totp.saveTotp(state);
        String uri = TotpGenerator.otpauthUri(properties.issuer(), user.userName(), encoded,
                properties.digits(), (int) properties.period().toSeconds(),
                TotpAlgorithm.SHA1);
        audit(user, "mfa.totp.enroll", "PENDING", "");
        return new TotpEnrollment(encoded, uri);
    }

    /** Confirms the pending enrollment and returns recovery codes exactly once. */
    public List<String> confirm(String userId, String code) {
        IamUser user = requireUser(userId);
        IamTotpState state = totp.findTotp(userId).orElseThrow(() -> new IamException(
                IamErrorCode.TOTP_NOT_ENROLLED, "TOTP enrollment has not started"));
        if (state.confirmed()) {
            throw new IamException(IamErrorCode.TOTP_ALREADY_ENROLLED,
                    "TOTP is already enabled for this user");
        }
        byte[] secret = Base32.decode(protector.reveal(state.secretCiphertext(), userId));
        TotpGenerator.Result result = TotpGenerator.verify(secret, code, clock.instant(),
                properties.window(), properties.digits(), (int) properties.period().toSeconds(),
                TotpAlgorithm.SHA1, state.lastUsedStep());
        if (!result.valid()) {
            audit(user, "mfa.totp.confirm", "FAILURE",
                    result.replayed() ? "replay" : "invalid code");
            throw new IamException(result.replayed() ? IamErrorCode.TOTP_REPLAY
                    : IamErrorCode.TOTP_INVALID,
                    result.replayed() ? "TOTP code already used" : "invalid TOTP code");
        }

        totp.saveTotp(state.confirmed(clock.instant()).withLastUsedStep(result.step()));
        List<String> rawCodes = new ArrayList<>();
        List<IamRecoveryCode> hashed = new ArrayList<>();
        for (int i = 0; i < properties.recoveryCodes(); i++) {
            String raw = recoveryCode();
            rawCodes.add(raw);
            hashed.add(new IamRecoveryCode(userId, hasher.encode(raw), false, clock.instant()));
        }
        totp.replaceRecoveryCodes(userId, hashed);
        audit(user, "mfa.totp.confirm", "SUCCESS", "");
        return List.copyOf(rawCodes);
    }

    public TotpStatus status(String userId) {
        IamTotpState state = totp.findTotp(userId).orElse(null);
        long remaining = totp.findRecoveryCodes(userId).stream()
                .filter(code -> !code.used()).count();
        return new TotpStatus(state != null && state.confirmed(), (int) remaining);
    }

    public boolean isEnabled(String userId) {
        return totp.findTotp(userId).map(IamTotpState::confirmed).orElse(false);
    }

    public void verify(String userId, String code) {
        IamUser user = requireUser(userId);
        IamTotpState state = totp.findTotp(userId).filter(IamTotpState::confirmed)
                .orElseThrow(() -> new IamException(IamErrorCode.TOTP_NOT_ENROLLED,
                        "TOTP is not enabled"));
        byte[] secret = Base32.decode(protector.reveal(state.secretCiphertext(), userId));
        TotpGenerator.Result result = TotpGenerator.verify(secret, code, clock.instant(),
                properties.window(), properties.digits(), (int) properties.period().toSeconds(),
                TotpAlgorithm.SHA1, state.lastUsedStep());
        if (!result.valid()) {
            audit(user, "mfa.totp.verify", "FAILURE", result.replayed() ? "replay" : "invalid");
            throw new IamException(result.replayed() ? IamErrorCode.TOTP_REPLAY
                    : IamErrorCode.TOTP_INVALID,
                    result.replayed() ? "TOTP code already used" : "invalid TOTP code");
        }
        if (!totp.advanceLastUsedStep(userId, result.step())) {
            audit(user, "mfa.totp.verify", "FAILURE", "replay");
            throw new IamException(IamErrorCode.TOTP_REPLAY, "TOTP code already used");
        }
        audit(user, "mfa.totp.verify", "SUCCESS", "");
    }

    /** Verifies and consumes a recovery code. */
    public boolean verifyRecoveryCode(String userId, String code) {
        IamUser user = requireUser(userId);
        boolean matched = false;
        String matchedHash = null;
        for (IamRecoveryCode candidate : totp.findRecoveryCodes(userId)) {
            if (candidate.used()) {
                continue;
            }
            boolean matches = hasher.matches(code == null ? "" : code, candidate.codeHash());
            if (matches && !matched) {
                matched = true;
                matchedHash = candidate.codeHash();
            }
        }
        if (!matched) {
            audit(user, "mfa.recovery.verify", "FAILURE", "invalid code");
            throw new IamException(IamErrorCode.RECOVERY_CODE_INVALID, "invalid recovery code");
        }
        totp.markRecoveryCodeUsed(userId, matchedHash);
        audit(user, "mfa.recovery.verify", "SUCCESS", "");
        return true;
    }

    public void disable(String userId) {
        IamUser user = requireUser(userId);
        totp.deleteTotp(userId);
        audit(user, "mfa.totp.disable", "SUCCESS", "");
    }

    private IamUser requireUser(String userId) {
        return users.findById(userId).orElseThrow(
                () -> new IamException(IamErrorCode.USER_NOT_FOUND, "user not found: " + userId));
    }

    private static String recoveryCode() {
        StringBuilder builder = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            builder.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return builder.toString();
    }

    private void audit(IamUser user, String action, String outcome, String reason) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(new SecurityAuditEvent(idGenerator.id(), user.tenantId(), user.id(), action, "user",
                    user.id(), outcome, reason, clock.instant(), Map.of()));
        } catch (RuntimeException ignored) {
        }
    }

    public record TotpStatus(boolean enabled, int recoveryCodesRemaining) {
    }
}
