package cn.byteforce.seedj.starter.iam.identity;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.id.IdGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;

/** Creates and consumes opaque, single-use native-auth MFA challenges. */
public class IamMfaChallengeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IamTotpRepository repository;
    private final IamProperties.NativeAuth properties;
    private final Clock clock;
    private final IdGenerator<String> idGenerator;

    public IamMfaChallengeService(IamTotpRepository repository,
                                  IamProperties.NativeAuth properties, Clock clock,
                                  IdGenerator<String> idGenerator) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idGenerator = idGenerator;
    }

    public String create(String userId, String clientId, String deviceId) {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        IamMfaChallenge challenge = new IamMfaChallenge(idGenerator.id(), userId,
                clientId, deviceId, hash(raw), clock.instant().plus(properties.mfaTokenTtl()),
                false, 0, properties.maxAttempts());
        repository.saveChallenge(challenge);
        return raw;
    }

    public IamMfaChallenge require(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IamException(IamErrorCode.MFA_CHALLENGE_INVALID, "mfa_token is required");
        }
        IamMfaChallenge challenge = repository.findChallengeByTokenHash(hash(rawToken))
                .orElseThrow(() -> new IamException(IamErrorCode.MFA_CHALLENGE_INVALID,
                        "invalid mfa_token"));
        if (challenge.consumed()) {
            throw new IamException(IamErrorCode.MFA_CHALLENGE_INVALID, "mfa_token already used");
        }
        if (challenge.expired(clock.instant())) {
            throw new IamException(IamErrorCode.MFA_CHALLENGE_EXPIRED, "mfa_token expired");
        }
        if (challenge.locked()) {
            throw new IamException(IamErrorCode.MFA_CHALLENGE_LOCKED, "mfa_token locked");
        }
        return challenge;
    }

    public void consume(IamMfaChallenge challenge) {
        repository.updateChallenge(challenge.markConsumed());
    }

    public void failure(IamMfaChallenge challenge) {
        repository.updateChallenge(challenge.withAttempt());
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
