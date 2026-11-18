package cn.byteforce.seedj.starter.ai.token;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.starter.ai.AiProperties;
import com.nimbusds.jose.JWSObject;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JWKS 公钥访问器：把发布的 JWK 参数还原成可验签的 {@link PublicKey}。 */
class Ed25519JwksTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID DECISION_ID = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID LEDGER_INTENT_ID =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000d001");
    private static final UUID TARGET = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");
    private static final String COMMAND_TYPE = "billing.refund";
    private static final String AUDIENCE = "seedj-server-ai";
    private static final String KID = "agent-decision-test";
    private static final Instant IAT = Instant.parse("2026-11-17T09:41:00Z");

    @Test
    @DisplayName("JWKS 还原出的公钥能验通过 issuer 签出的 token")
    void publicKey_can_verify_issued_token() throws Exception {
        DecisionTokenIssuer issuer = issuer();

        JWSObject token = JWSObject.parse(issuer.sign(claims()));

        assertThat(token.verify(new JcaEd25519Verifier(issuer.jwks().publicKey()))).isTrue();
    }

    private static DecisionTokenIssuer issuer() {
        byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) {
            seed[index] = (byte) (index + 1);
        }
        return new DecisionTokenIssuer(
                new AiProperties.Decision(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(seed),
                        KID,
                        Duration.ofSeconds(300),
                        AUDIENCE));
    }

    private static DecisionTokenClaims claims() {
        Map<String, Object> payload = Map.of("amount", "128.00");
        List<UUID> targets = List.of(TARGET);
        CommandRef command = new CommandRef(COMMAND_TYPE, targets, 7L);
        return new DecisionTokenClaims(
                1,
                DECISION_ID,
                LEDGER_INTENT_ID,
                command,
                ArgsHash.of(new CommandFingerprint(COMMAND_TYPE, payload, targets, 7L)),
                ACTOR,
                ON_BEHALF_OF,
                TENANT.toString(),
                ActionClass.REVERSIBLE,
                PolicyRef.none(),
                null,
                IAT,
                IAT.plusSeconds(300),
                AUDIENCE);
    }
}
