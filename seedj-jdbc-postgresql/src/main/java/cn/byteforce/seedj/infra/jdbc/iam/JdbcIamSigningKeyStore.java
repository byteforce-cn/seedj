package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.IamSigningKey;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Persistent RSA JWK store. Private JWKs are protected by {@link IamSecretProtector};
 * retired keys keep only public material so old tokens still verify.
 */
public class JdbcIamSigningKeyStore implements IamSigningKeyStore {

    private final JdbcTemplate jdbc;
    private final IamSecretProtector protector;
    private final TransactionTemplate transactions;
    private final IdGenerator<String> idGenerator;
    private final RowMapper<IamSigningKey> mapper = (rs, rowNum) -> new IamSigningKey(
            rs.getString("kid"),
            rs.getString("algorithm"),
            rs.getString("public_jwk"),
            protect(rs.getString("private_jwk"), rs.getString("kid")),
            rs.getBoolean("active"),
            instant(rs, "created_at"),
            instant(rs, "retired_at"));

    public JdbcIamSigningKeyStore(JdbcTemplate jdbc, DataSource dataSource,
                                  IamSecretProtector protector,
                                  IdGenerator<String> idGenerator) {
        this.jdbc = jdbc;
        this.protector = protector;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.idGenerator = idGenerator;
    }

    @Override
    public IamSigningKey activeKey() {
        List<IamSigningKey> active = jdbc.query(
                "SELECT * FROM iam_jwk WHERE active = TRUE ORDER BY created_at DESC LIMIT 1",
                mapper);
        if (!active.isEmpty()) {
            return active.get(0);
        }
        return createActive();
    }

    @Override
    public List<IamSigningKey> keys() {
        return jdbc.query("SELECT * FROM iam_jwk ORDER BY active DESC, created_at DESC", mapper);
    }

    @Override
    public IamSigningKey rotate() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE iam_jwk SET active = FALSE, retired_at = now(), private_jwk = NULL "
                    + "WHERE active = TRUE");
            insert(generate());
        });
        return activeKey();
    }

    private IamSigningKey createActive() {
        IamSigningKey created = generate();
        insert(created);
        return created;
    }

    private void insert(IamSigningKey key) {
        jdbc.update("""
                INSERT INTO iam_jwk
                    (kid, algorithm, public_jwk, private_jwk, active, created_at)
                VALUES (?, ?, ?, ?, TRUE, now())
                """, key.kid(), key.algorithm(), key.publicJwk(),
                protector.protect(key.privateJwk(), key.kid()));
    }

    private String protect(String privateJwk, String kid) {
        if (privateJwk == null || privateJwk.isBlank()) {
            return "";
        }
        return protector.reveal(privateJwk, kid);
    }

    private IamSigningKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            String kid = idGenerator.id();
            return new IamSigningKey(kid, "RS256", publicJwk(kid, publicKey),
                    privateJwk(kid, publicKey, privateKey), true, Instant.now(), null);
        } catch (Exception e) {
            throw new IllegalStateException("unable to generate RSA signing key", e);
        }
    }

    private static String publicJwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + b64(key.getModulus()) + "\",\"e\":\""
                + b64(key.getPublicExponent()) + "\"}";
    }

    private static String privateJwk(String kid, RSAPublicKey publicKey, RSAPrivateCrtKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + b64(publicKey.getModulus()) + "\","
                + "\"e\":\"" + b64(publicKey.getPublicExponent()) + "\","
                + "\"d\":\"" + b64(key.getPrivateExponent()) + "\","
                + "\"p\":\"" + b64(key.getPrimeP()) + "\","
                + "\"q\":\"" + b64(key.getPrimeQ()) + "\","
                + "\"dp\":\"" + b64(key.getPrimeExponentP()) + "\","
                + "\"dq\":\"" + b64(key.getPrimeExponentQ()) + "\","
                + "\"qi\":\"" + b64(key.getCrtCoefficient()) + "\"}";
    }

    private static String b64(BigInteger value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(value));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
