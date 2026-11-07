package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamRecoveryCode;
import cn.byteforce.seedj.iam.IamTotpState;
import java.util.List;
import java.util.Optional;

/** TOTP enrollment, one-time recovery codes, and native MFA challenges. */
public interface IamTotpRepository {

    Optional<IamTotpState> findTotp(String userId);

    void saveTotp(IamTotpState state);

    void deleteTotp(String userId);

    /**
     * Atomically advances the last used step.
     *
     * @return {@code true} when the step was accepted, {@code false} for a replay
     */
    boolean advanceLastUsedStep(String userId, long step);

    void replaceRecoveryCodes(String userId, List<IamRecoveryCode> codes);

    List<IamRecoveryCode> findRecoveryCodes(String userId);

    void markRecoveryCodeUsed(String userId, String codeHash);

    void saveChallenge(IamMfaChallenge challenge);

    Optional<IamMfaChallenge> findChallengeByTokenHash(String tokenHash);

    void updateChallenge(IamMfaChallenge challenge);
}
