package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deletes all SAS authorizations for a principal (refresh tokens become unusable). */
public class JdbcIamTokenRevoker implements IamTokenRevoker {

    private final JdbcTemplate jdbc;

    public JdbcIamTokenRevoker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void revokeAllForUser(String tenantId, String principalName) {
        jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", principalName);
    }
}
