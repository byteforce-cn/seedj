package cn.byteforce.seedj.infra.local.iam;

import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamRecoveryCode;
import cn.byteforce.seedj.iam.IamTotpState;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory TOTP/MFA store. */
public class InMemoryIamTotpRepository implements IamTotpRepository {

    private final Map<String, IamTotpState> totp = new ConcurrentHashMap<>();
    private final Map<String, List<IamRecoveryCode>> recoveryCodes = new ConcurrentHashMap<>();
    private final Map<String, IamMfaChallenge> challenges = new ConcurrentHashMap<>();

    @Override
    public Optional<IamTotpState> findTotp(String userId) {
        return Optional.ofNullable(totp.get(userId));
    }

    @Override
    public void saveTotp(IamTotpState state) {
        totp.put(state.userId(), state);
    }

    @Override
    public void deleteTotp(String userId) {
        totp.remove(userId);
        recoveryCodes.remove(userId);
    }

    @Override
    public synchronized boolean advanceLastUsedStep(String userId, long step) {
        IamTotpState state = totp.get(userId);
        if (state == null || step <= state.lastUsedStep()) {
            return false;
        }
        totp.put(userId, state.withLastUsedStep(step));
        return true;
    }

    @Override
    public synchronized void replaceRecoveryCodes(String userId, List<IamRecoveryCode> codes) {
        recoveryCodes.put(userId, new ArrayList<>(codes));
    }

    @Override
    public List<IamRecoveryCode> findRecoveryCodes(String userId) {
        return List.copyOf(recoveryCodes.getOrDefault(userId, List.of()));
    }

    @Override
    public synchronized void markRecoveryCodeUsed(String userId, String codeHash) {
        List<IamRecoveryCode> codes = recoveryCodes.get(userId);
        if (codes == null) {
            return;
        }
        for (int i = 0; i < codes.size(); i++) {
            if (codes.get(i).codeHash().equals(codeHash)) {
                codes.set(i, codes.get(i).markUsed());
            }
        }
    }

    @Override
    public void saveChallenge(IamMfaChallenge challenge) {
        challenges.put(challenge.tokenHash(), challenge);
    }

    @Override
    public Optional<IamMfaChallenge> findChallengeByTokenHash(String tokenHash) {
        return Optional.ofNullable(challenges.get(tokenHash));
    }

    @Override
    public void updateChallenge(IamMfaChallenge challenge) {
        challenges.put(challenge.tokenHash(), challenge);
    }
}
