package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamRecoveryCode;
import cn.byteforce.seedj.iam.IamTotpState;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** PostgreSQL TOTP/recovery/challenge store. Replay protection is one atomic UPDATE. */
public class JdbcIamTotpRepository implements IamTotpRepository {

    private static final RowMapper<IamTotpState> TOTP_MAPPER = JdbcIamTotpRepository::mapTotp;
    private static final RowMapper<IamRecoveryCode> RECOVERY_MAPPER =
            (rs, rowNum) ->
                    new IamRecoveryCode(
                            rs.getString("user_id"),
                            rs.getString("code_hash"),
                            rs.getBoolean("used"),
                            instant(rs, "created_at"));
    private static final RowMapper<IamMfaChallenge> CHALLENGE_MAPPER =
            (rs, rowNum) ->
                    new IamMfaChallenge(
                            rs.getString("id"),
                            rs.getString("user_id"),
                            rs.getString("client_id"),
                            rs.getString("device_id"),
                            rs.getString("token_hash"),
                            instant(rs, "expires_at"),
                            rs.getBoolean("consumed"),
                            rs.getInt("attempts"),
                            rs.getInt("max_attempts"));

    private final JdbcTemplate jdbc;

    public JdbcIamTotpRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IamTotpState> findTotp(String userId) {
        return jdbc.query("SELECT * FROM iam_totp WHERE user_id = ?", TOTP_MAPPER, userId).stream()
                .findFirst();
    }

    @Override
    public void saveTotp(IamTotpState state) {
        jdbc.update(
                """
                INSERT INTO iam_totp
                    (user_id, tenant_id, key_name, secret_ciphertext, confirmed,
                     last_used_step, created_at, confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET tenant_id = EXCLUDED.tenant_id, key_name = EXCLUDED.key_name,
                    secret_ciphertext = EXCLUDED.secret_ciphertext,
                    confirmed = EXCLUDED.confirmed, last_used_step = EXCLUDED.last_used_step,
                    confirmed_at = EXCLUDED.confirmed_at
                """,
                state.userId(),
                state.tenantId(),
                state.keyName(),
                state.secretCiphertext(),
                state.confirmed(),
                state.lastUsedStep(),
                timestamp(state.createdAt()),
                timestamp(state.confirmedAt()));
    }

    @Override
    public void deleteTotp(String userId) {
        jdbc.update("DELETE FROM iam_totp WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM iam_recovery_code WHERE user_id = ?", userId);
    }

    @Override
    public boolean advanceLastUsedStep(String userId, long step) {
        return jdbc.update(
                        """
                UPDATE iam_totp SET last_used_step = ?
                WHERE user_id = ? AND last_used_step < ?
                """,
                        step,
                        userId,
                        step)
                == 1;
    }

    @Override
    public void replaceRecoveryCodes(String userId, List<IamRecoveryCode> codes) {
        jdbc.update("DELETE FROM iam_recovery_code WHERE user_id = ?", userId);
        for (IamRecoveryCode code : codes) {
            jdbc.update(
                    """
                    INSERT INTO iam_recovery_code (user_id, code_hash, used, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    code.userId(),
                    code.codeHash(),
                    code.used(),
                    timestamp(code.createdAt()));
        }
    }

    @Override
    public List<IamRecoveryCode> findRecoveryCodes(String userId) {
        return jdbc.query(
                """
                SELECT * FROM iam_recovery_code WHERE user_id = ? AND used = FALSE
                ORDER BY created_at
                """,
                RECOVERY_MAPPER,
                userId);
    }

    @Override
    public void markRecoveryCodeUsed(String userId, String codeHash) {
        jdbc.update(
                """
                UPDATE iam_recovery_code SET used = TRUE
                WHERE user_id = ? AND code_hash = ? AND used = FALSE
                """,
                userId,
                codeHash);
    }

    @Override
    public void saveChallenge(IamMfaChallenge challenge) {
        jdbc.update(
                """
                INSERT INTO iam_mfa_challenge
                    (id, user_id, client_id, device_id, token_hash, expires_at,
                     consumed, attempts, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                SET consumed = EXCLUDED.consumed, attempts = EXCLUDED.attempts
                """,
                challenge.id(),
                challenge.userId(),
                challenge.clientId(),
                challenge.deviceId(),
                challenge.tokenHash(),
                timestamp(challenge.expiresAt()),
                challenge.consumed(),
                challenge.attempts(),
                challenge.maxAttempts());
    }

    @Override
    public Optional<IamMfaChallenge> findChallengeByTokenHash(String tokenHash) {
        return jdbc
                .query(
                        "SELECT * FROM iam_mfa_challenge WHERE token_hash = ?",
                        CHALLENGE_MAPPER,
                        tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public void updateChallenge(IamMfaChallenge challenge) {
        jdbc.update(
                """
                UPDATE iam_mfa_challenge
                SET consumed = ?, attempts = ?
                WHERE id = ?
                """,
                challenge.consumed(),
                challenge.attempts(),
                challenge.id());
    }

    private static IamTotpState mapTotp(ResultSet rs, int rowNum) throws SQLException {
        return new IamTotpState(
                rs.getString("user_id"),
                rs.getString("tenant_id"),
                rs.getString("key_name"),
                rs.getString("secret_ciphertext"),
                rs.getBoolean("confirmed"),
                rs.getLong("last_used_step"),
                instant(rs, "created_at"),
                instant(rs, "confirmed_at"));
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
