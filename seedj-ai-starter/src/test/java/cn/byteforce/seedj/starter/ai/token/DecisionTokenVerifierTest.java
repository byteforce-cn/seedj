package cn.byteforce.seedj.starter.ai.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.AiErrorCode;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.GuardVerdict;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.policy.PolicyHead;
import cn.byteforce.seedj.starter.ai.policy.PolicyHeadSource;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * DecisionToken 的签发、JWKS 与执行侧校验矩阵（spec §7.1–§7.3）。
 *
 * <p>拒绝用例逐个断言 <b>具体</b> 错误码：校验顺序是契约的一部分，先身份再命令再哈希——顺序错了会返回错误的码， 运维与调用方按码做处置。断言里不打印 token
 * 全文（日志与排障只允许 {@code kid} 与 {@code decision_id} 前 8 位）。
 *
 * <p>密钥来源只有一条：测试内固定的 32 字节 seed。用例通过 {@link DecisionTokenPayload}（同包）与一个测试侧的 {@link JWSSigner}
 * 构造「签名有效但违反协议」的 token（例如 TTL 超 900s、未知 kid），这是 issuer 刻意拒绝签发的 形态，只能绕过 issuer 造。
 */
class DecisionTokenVerifierTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID OTHER_ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e02");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID TENANT_ID = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("018f2e3a-0000-7000-8000-0000000b0008");
    private static final UUID DECISION_ID = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID LEDGER_INTENT_ID =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000d001");
    private static final UUID TARGET_1 = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");
    private static final UUID TARGET_2 = UUID.fromString("018f2e3a-0000-7000-8000-000000007732");

    private static final String COMMAND_TYPE = "billing.refund";
    private static final Long EXPECTED_VERSION = 7L;
    private static final List<UUID> TARGETS = List.of(TARGET_1, TARGET_2);
    private static final Map<String, Object> PAYLOAD =
            Map.of("amount", "128.00", "currency", "CNY");
    private static final CommandRef COMMAND =
            new CommandRef(COMMAND_TYPE, TARGETS, EXPECTED_VERSION);
    private static final String DEFAULT_ARGS_HASH =
            ArgsHash.of(new CommandFingerprint(COMMAND_TYPE, PAYLOAD, TARGETS, EXPECTED_VERSION));

    private static final String KID = "agent-decision-test";
    private static final String AUDIENCE = "seedj-server-ai";
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final Instant NOW = Instant.parse("2026-11-17T09:41:00Z");
    private static final Instant IAT = NOW.minusSeconds(30);

    private static final byte[] SEED = seed();

    private static final String TENANT = TENANT_ID.toString();

    private static final ToolRegistry REGISTRY =
            type ->
                    COMMAND_TYPE.equals(type)
                            ? Optional.of(new AgentToolSpec(COMMAND_TYPE, ActionClass.REVERSIBLE))
                            : Optional.empty();

    private static final DecisionTokenIssuer ISSUER = issuer(SEED, KID);
    private static final Map<String, PublicKey> KEYS = keysOf(ISSUER.jwks());
    private static final DecisionTokenVerifier VERIFIER =
            new DecisionTokenVerifier(KEYS, REGISTRY, AUDIENCE);

    private static final String POLICY_BUNDLE = "ticket-policy";

    private static final PolicyRef POLICY_PINNED =
            new PolicyRef("local", POLICY_BUNDLE, 3L, 41L, "allow", List.of(), null);

    private static final ToolRegistry IRREVERSIBLE_REGISTRY =
            type ->
                    COMMAND_TYPE.equals(type)
                            ? Optional.of(
                                    new AgentToolSpec(
                                            COMMAND_TYPE, ActionClass.IRREVERSIBLE_FINANCIAL))
                            : Optional.empty();

    private static Fixture irreversible(PolicyRef policy) {
        Fixture fixture = new Fixture();
        fixture.registry = IRREVERSIBLE_REGISTRY;
        fixture.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
        fixture.approval = approved(DEFAULT_ARGS_HASH);
        fixture.policy = policy;
        return fixture;
    }

    private static PolicyHeadSource headOf(long version, Long epoch) {
        return new PolicyHeadSource() {

            @Override
            public Optional<PolicyHead> head(String engine, String bundle) {
                return "local".equals(engine) && POLICY_BUNDLE.equals(bundle)
                        ? Optional.of(new PolicyHead("local", POLICY_BUNDLE, version, epoch))
                        : Optional.empty();
            }
        };
    }

    private static PolicyHeadSource unknownHeads() {
        return (engine, bundle) -> Optional.empty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    @DisplayName("拒绝分支逐条返回具体错误码")
    void rejects_with_specific_code(String name, Consumer<Fixture> tweak, String expectedWireCode) {
        Fixture fixture = new Fixture();
        tweak.accept(fixture);
        String token = fixture.jws();

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE)
                        .verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> {
                            assertThat(reject.code().wire()).isEqualTo(expectedWireCode);
                            assertThat(reject.detail()).doesNotContain(token);
                        });
    }

    /** 16 个拒绝分支：用例名与 spec §7.3 的校验项一一对应。 */
    static Stream<Arguments> rejections() {
        return Stream.of(
                // 1. 凭据：签名被篡改、kid 未发布
                reject("token_invalid", fixture -> fixture.tamperSignature = true, "token_invalid"),
                reject(
                        "token_unknown_kid",
                        fixture -> fixture.rawKid = "agent-decision-rotated",
                        "token_invalid"),
                // 2. 有效期
                reject(
                        "token_expired",
                        fixture -> {
                            fixture.issuedAt = NOW.minusSeconds(301);
                            fixture.expiresAt = NOW.minusSeconds(1);
                        },
                        "token_expired"),
                reject(
                        "iat_in_future",
                        fixture -> {
                            fixture.issuedAt = NOW.plusSeconds(61);
                            fixture.expiresAt = fixture.issuedAt.plusSeconds(60);
                        },
                        "token_expired"),
                reject(
                        "ttl_too_long",
                        fixture -> {
                            // issuer 拒签 901s，只能绕过 issuer 造一个签名有效的 901s token
                            fixture.rawKid = KID;
                            fixture.expiresAt = fixture.issuedAt.plusSeconds(901);
                        },
                        "token_expired"),
                // 3. 身份
                reject(
                        "identity_mismatch",
                        fixture -> fixture.ctxActor = OTHER_ACTOR,
                        "identity_mismatch"),
                reject(
                        "tenant_mismatch",
                        fixture -> fixture.tenant = OTHER_TENANT_ID.toString(),
                        "identity_mismatch"),
                // 4–8. 命令、哈希、动作分类与审批
                reject(
                        "command_not_registered",
                        fixture -> fixture.registry = type -> Optional.empty(),
                        "command_not_registered"),
                reject(
                        "command_mismatch",
                        fixture ->
                                fixture.command =
                                        new CommandRef(
                                                "billing.refund.other", TARGETS, EXPECTED_VERSION),
                        "command_mismatch"),
                reject(
                        "targets_mismatch",
                        fixture ->
                                fixture.command =
                                        new CommandRef(
                                                COMMAND_TYPE,
                                                List.of(TARGET_2, TARGET_1),
                                                EXPECTED_VERSION),
                        "targets_mismatch"),
                reject(
                        "version_mismatch",
                        fixture -> fixture.command = new CommandRef(COMMAND_TYPE, TARGETS, 8L),
                        "version_mismatch"),
                reject(
                        "args_hash_mismatch",
                        fixture ->
                                fixture.ctxPayload =
                                        Map.of(
                                                "amount",
                                                "128.00",
                                                "currency",
                                                "CNY",
                                                "note",
                                                "extra"),
                        "args_hash_mismatch"),
                reject(
                        "action_class_mismatch",
                        fixture -> {
                            fixture.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
                            fixture.approval = approved(DEFAULT_ARGS_HASH);
                        },
                        "action_class_mismatch"),
                reject(
                        "approval_missing",
                        fixture -> {
                            // core 已禁止「不可逆但无审批快照」的 claim，所以这里走审批绑定错参数这条
                            fixture.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
                            fixture.approval = approved("sha256:" + "0".repeat(64));
                            fixture.registry =
                                    type ->
                                            Optional.of(
                                                    new AgentToolSpec(
                                                            type,
                                                            ActionClass.IRREVERSIBLE_FINANCIAL));
                        },
                        "approval_missing"),
                // 9–10. 受众与幂等键
                reject(
                        "aud_mismatch",
                        fixture -> fixture.audience = "other-service",
                        "aud_mismatch"),
                reject(
                        "idempotency_key_mismatch",
                        fixture -> fixture.ctxIdempotencyKey = OTHER_ACTOR.toString(),
                        "idempotency_key_mismatch"));
    }

    private static Arguments reject(String name, Consumer<Fixture> tweak, String expectedWireCode) {
        return arguments(name, tweak, expectedWireCode);
    }

    @Test
    @DisplayName("匹配的 token 与请求放行，claims 关键字段逐项回读一致")
    void admits_matching_request() throws ParseException {
        Fixture fixture = new Fixture();
        String token = fixture.jws();

        GuardVerdict verdict = VERIFIER.verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Admit.class,
                        admit -> {
                            DecisionTokenClaims claims = admit.claims();
                            assertThat(claims.decisionId()).isEqualTo(DECISION_ID);
                            assertThat(claims.ledgerIntentId()).isEqualTo(LEDGER_INTENT_ID);
                            assertThat(claims.command()).isEqualTo(COMMAND);
                            assertThat(claims.argsHash()).isEqualTo(DEFAULT_ARGS_HASH);
                            assertThat(claims.actor()).isEqualTo(ACTOR);
                            assertThat(claims.onBehalfOf()).isEqualTo(ON_BEHALF_OF);
                            assertThat(claims.tenant()).isEqualTo(TENANT);
                            assertThat(claims.actionClass()).isEqualTo(ActionClass.REVERSIBLE);
                            assertThat(claims.policy()).isEqualTo(PolicyRef.none());
                            assertThat(claims.approval()).isNull();
                            assertThat(claims.issuedAt()).isEqualTo(IAT);
                            assertThat(claims.expiresAt()).isEqualTo(IAT.plusSeconds(300));
                            assertThat(claims.audience()).isEqualTo(AUDIENCE);
                        });

        String payloadJson =
                new String(JWSObject.parse(token).getPayload().toBytes(), StandardCharsets.UTF_8);
        assertThat(payloadJson)
                .contains("\"expectedVersion\":7")
                .contains("\"targets\":[\"" + TARGET_1 + "\",\"" + TARGET_2 + "\"]")
                .contains("\"iat\"")
                .doesNotContain("\"approval\"")
                .doesNotContain("\"sig\"");
    }

    @Test
    @DisplayName("issuer 拒绝超过 15 分钟的 TTL：配置期与签发期都拒绝")
    void issuer_rejects_ttl_over_15_minutes() {
        assertThatThrownBy(
                        () ->
                                new DecisionTokenIssuer(
                                        new AiProperties.Decision(
                                                null, KID, Duration.ofSeconds(901), AUDIENCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("900");

        assertThatThrownBy(
                        () ->
                                new DecisionTokenIssuer(
                                        new AiProperties.Decision(
                                                null, KID, Duration.ZERO, AUDIENCE)))
                .isInstanceOf(IllegalArgumentException.class);

        Fixture fixture = new Fixture();
        fixture.expiresAt = fixture.issuedAt.plusSeconds(901);
        assertThatThrownBy(() -> ISSUER.sign(fixture.claims()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("900");
    }

    @Test
    @DisplayName("JWKS 只暴露公钥参数，不出现私钥 d")
    void jwks_exposes_only_public_key() {
        Map<String, Object> document = ISSUER.jwks().document();

        assertThat(document).containsOnlyKeys("keys");
        assertThat((List<?>) document.get("keys"))
                .singleElement()
                .isInstanceOfSatisfying(
                        Map.class,
                        key -> {
                            assertThat(key)
                                    .containsEntry("kty", "OKP")
                                    .containsEntry("crv", "Ed25519")
                                    .containsEntry("kid", KID)
                                    .containsEntry("use", "sig")
                                    .containsEntry("alg", "EdDSA")
                                    .doesNotContainKey("d");
                            assertThat(Base64.getUrlDecoder().decode((String) key.get("x")))
                                    .hasSize(32);
                        });
    }

    @Test
    @DisplayName("不可逆资金动作带已批准快照时签名与校验闭环")
    void issuer_and_verifier_round_trip_with_approval() {
        Fixture fixture = new Fixture();
        fixture.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
        fixture.approval = approved(DEFAULT_ARGS_HASH);
        fixture.registry =
                type -> Optional.of(new AgentToolSpec(type, ActionClass.IRREVERSIBLE_FINANCIAL));
        String token = fixture.jws();

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE)
                        .verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Admit.class,
                        admit -> assertThat(admit.claims().approval()).isEqualTo(fixture.approval));
    }

    @Test
    @DisplayName("同一密钥的 seed 编码与 PKCS#8 编码产出同一个 JWKS x")
    void signing_key_accepts_pkcs8_and_seed_and_produces_same_jwks_x() {
        String seedEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(SEED);
        String pkcs8Encoded = Base64.getEncoder().encodeToString(pkcs8(SEED));

        DecisionTokenIssuer fromSeed =
                new DecisionTokenIssuer(
                        new AiProperties.Decision(seedEncoded, "kid-seed", TTL, AUDIENCE));
        DecisionTokenIssuer fromPkcs8 =
                new DecisionTokenIssuer(
                        new AiProperties.Decision(pkcs8Encoded, "kid-pkcs8", TTL, AUDIENCE));

        assertThat(jwksX(fromPkcs8.jwks())).isEqualTo(jwksX(fromSeed.jwks()));
        assertThat(fromPkcs8.kid()).isEqualTo("kid-pkcs8");
    }

    @Test
    @DisplayName("claim 签名用的是 JWS 标准输入：JDK 公钥可直接验第三段")
    void signature_is_verifiable_with_jdk_ec_public_key() throws GeneralSecurityException {
        String token = new Fixture().jws();
        String[] parts = token.split("\\.");

        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(publicKey(jwksX(ISSUER.jwks())));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

        assertThat(signature.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    @DisplayName("未配置 signing-key 时生成临时密钥，签发的 token 可被自己的 JWKS 验过")
    void missing_signing_key_generates_ephemeral_keypair() {
        DecisionTokenIssuer ephemeral =
                new DecisionTokenIssuer(new AiProperties.Decision(null, KID, TTL, AUDIENCE));
        Fixture fixture = new Fixture();
        String token = ephemeral.sign(fixture.claims());

        GuardVerdict verdict =
                new DecisionTokenVerifier(keysOf(ephemeral.jwks()), REGISTRY, AUDIENCE)
                        .verify(token, fixture.context(), NOW);

        assertThat(verdict).isInstanceOf(GuardVerdict.Admit.class);
    }

    @Test
    @DisplayName("T0 不比 policy：PolicyRef.none() 的不可逆 token 仍然放行（§7.3 第 9 条未实现）")
    void policy_check_is_not_part_of_t0() {
        Fixture fixture = new Fixture();
        fixture.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
        fixture.approval = approved(DEFAULT_ARGS_HASH);
        fixture.registry =
                type -> Optional.of(new AgentToolSpec(type, ActionClass.IRREVERSIBLE_FINANCIAL));
        String token = fixture.jws();

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE)
                        .verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Admit.class,
                        admit -> {
                            assertThat(admit.claims().policy()).isEqualTo(PolicyRef.none());
                            assertThat(admit.claims().actionClass())
                                    .isEqualTo(ActionClass.IRREVERSIBLE_FINANCIAL);
                        });
    }

    @Test
    @DisplayName("exp == now 已过期（RFC 7519：exp 是不得再被接受的时刻）")
    void exp_equal_now_is_rejected() {
        Fixture fixture = new Fixture();
        fixture.issuedAt = NOW.minusSeconds(300);
        fixture.expiresAt = NOW;
        String token = fixture.jws();

        GuardVerdict verdict = VERIFIER.verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> assertThat(reject.code().wire()).isEqualTo("token_expired"));
    }

    @Test
    @DisplayName("带未处理 crit 参数的 token 一律拒绝（fail-closed）")
    void crit_header_is_rejected() {
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(new JOSEObjectType("agent-decision+jws"))
                        .keyID(KID)
                        .criticalParams(Set.of("exp"))
                        .build();
        Fixture fixture = new Fixture();
        String token = rawSign(fixture.claims(), header);

        GuardVerdict verdict = VERIFIER.verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> assertThat(reject.code().wire()).isEqualTo("token_invalid"));
    }

    @Test
    @DisplayName("header typ 不是 agent-decision+jws 时拒绝（别的 JOSE 对象不是决策凭据）")
    void wrong_typ_is_rejected() {
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(new JOSEObjectType("JWT"))
                        .keyID(KID)
                        .build();
        Fixture fixture = new Fixture();
        String token = rawSign(fixture.claims(), header);

        GuardVerdict verdict = VERIFIER.verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> {
                            assertThat(reject.code().wire()).isEqualTo("token_invalid");
                            assertThat(reject.detail()).contains("typ");
                        });
    }

    @Test
    @DisplayName("单条 token 超过 4096 字节直接拒绝（不截断、也不进解析器）")
    void oversized_token_is_rejected() {
        String oversized = "a".repeat(4100);

        GuardVerdict verdict = VERIFIER.verify(oversized, new Fixture().context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> {
                            assertThat(reject.code().wire()).isEqualTo("token_invalid");
                            assertThat(reject.detail()).contains("too large");
                        });
        // 恰好 4096 字节的串仍走正常解析路径（拒绝理由换成「不是 JWS」，而不是长度）。
        assertThat(VERIFIER.verify("a".repeat(4096), new Fixture().context(), NOW))
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> assertThat(reject.detail()).doesNotContain("too large"));
    }

    @Test
    @DisplayName("identity_mismatch 的 detail 带期望与实际的 actor/tenant（都不是秘密）")
    void identity_mismatch_names_expected_and_actual() {
        Fixture fixture = new Fixture();
        fixture.ctxActor = OTHER_ACTOR;

        GuardVerdict verdict = VERIFIER.verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> {
                            assertThat(reject.code().wire()).isEqualTo("identity_mismatch");
                            assertThat(reject.detail())
                                    .contains(ACTOR.toString())
                                    .contains(OTHER_ACTOR.toString())
                                    .contains(TENANT);
                        });
    }

    @Test
    @DisplayName("expectedVersion=null 的 token 能放行，且 wire 上保留该 null 键")
    void expected_version_null_is_carried_on_the_wire() throws ParseException {
        Fixture fixture = new Fixture();
        fixture.command = new CommandRef(COMMAND_TYPE, TARGETS, null);
        fixture.argsHash =
                ArgsHash.of(new CommandFingerprint(COMMAND_TYPE, PAYLOAD, TARGETS, null));
        fixture.ctxExpectedVersion = null;
        String token = fixture.jws();

        GuardVerdict verdict = VERIFIER.verify(token, fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Admit.class,
                        admit -> assertThat(admit.claims().command().expectedVersion()).isNull());
        assertThat(
                        new String(
                                JWSObject.parse(token).getPayload().toBytes(),
                                StandardCharsets.UTF_8))
                .contains("\"expectedVersion\":null");
    }

    @Test
    @DisplayName("构造器 audience 为空时改用 GuardContext.audience()")
    void null_audience_falls_back_to_context() {
        Fixture fixture = new Fixture();
        DecisionTokenVerifier verifier = new DecisionTokenVerifier(KEYS, REGISTRY, null);

        assertThat(verifier.verify(fixture.jws(), fixture.context(), NOW))
                .isInstanceOf(GuardVerdict.Admit.class);

        fixture.ctxAudience = "other-service";
        assertThat(verifier.verify(fixture.jws(), fixture.context(), NOW))
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> assertThat(reject.code().wire()).isEqualTo("aud_mismatch"));
    }

    @Test
    @DisplayName("不可逆动作：策略引用与 head 一致即放行")
    void irreversible_action_with_matching_head_is_admitted() {
        Fixture fixture = irreversible(POLICY_PINNED);

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, headOf(3L, 41L))
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict).isInstanceOf(GuardVerdict.Admit.class);
    }

    @Test
    @DisplayName("不可逆动作：版本低于 head → policy_stale")
    void irreversible_action_with_stale_version_is_rejected() {
        Fixture fixture = irreversible(POLICY_PINNED);

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, headOf(4L, 41L))
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> assertThat(reject.code()).isEqualTo(AiErrorCode.POLICY_STALE));
    }

    @Test
    @DisplayName("不可逆动作：epoch 与 head 不一致 → policy_stale")
    void irreversible_action_with_stale_epoch_is_rejected() {
        Fixture fixture = irreversible(POLICY_PINNED);

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, headOf(3L, 42L))
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject -> assertThat(reject.code()).isEqualTo(AiErrorCode.POLICY_STALE));
    }

    @Test
    @DisplayName("不可逆动作：token 没有策略引用 → policy_unavailable")
    void irreversible_action_without_policy_is_rejected() {
        Fixture fixture = irreversible(PolicyRef.none());

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, headOf(3L, 41L))
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject ->
                                assertThat(reject.code())
                                        .isEqualTo(AiErrorCode.POLICY_UNAVAILABLE));
    }

    @Test
    @DisplayName("不可逆动作：本部署没有该 bundle 的 head → policy_unavailable")
    void irreversible_action_without_head_is_rejected() {
        Fixture fixture = irreversible(POLICY_PINNED);

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, unknownHeads())
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject ->
                                assertThat(reject.code())
                                        .isEqualTo(AiErrorCode.POLICY_UNAVAILABLE));
    }

    @Test
    @DisplayName("head 给了 epoch 而 token 没有：无法确认新鲜度 → policy_unavailable")
    void irreversible_action_without_epoch_is_rejected() {
        PolicyRef noEpoch =
                new PolicyRef("local", POLICY_BUNDLE, 3L, null, "allow", List.of(), null);
        Fixture fixture = irreversible(noEpoch);

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, headOf(3L, 41L))
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict)
                .isInstanceOfSatisfying(
                        GuardVerdict.Reject.class,
                        reject ->
                                assertThat(reject.code())
                                        .isEqualTo(AiErrorCode.POLICY_UNAVAILABLE));
    }

    @Test
    @DisplayName("可补偿动作不比对策略 head：第 9 条只约束不可逆档位")
    void compensable_action_ignores_policy_head() {
        Fixture fixture = new Fixture();
        fixture.registry =
                type ->
                        COMMAND_TYPE.equals(type)
                                ? Optional.of(
                                        new AgentToolSpec(COMMAND_TYPE, ActionClass.COMPENSABLE))
                                : Optional.empty();
        fixture.actionClass = ActionClass.COMPENSABLE;
        fixture.policy = POLICY_PINNED;

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, headOf(4L, 42L))
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict).isInstanceOf(GuardVerdict.Admit.class);
    }

    @Test
    @DisplayName("verify=none：head 源不参与校验，不可逆动作照常放行")
    void unenforced_head_source_skips_policy_check() {
        Fixture fixture = irreversible(POLICY_PINNED);

        GuardVerdict verdict =
                new DecisionTokenVerifier(KEYS, fixture.registry, AUDIENCE, PolicyHeadSource.none())
                        .verify(fixture.jws(), fixture.context(), NOW);

        assertThat(verdict).isInstanceOf(GuardVerdict.Admit.class);
    }

    /** 构造一条合法 token 与匹配的请求上下文；每个用例只改一处。 */
    private static final class Fixture {

        UUID actor = ACTOR;
        UUID onBehalfOf = ON_BEHALF_OF;
        String tenant = TENANT;
        CommandRef command = COMMAND;
        String argsHash = DEFAULT_ARGS_HASH;
        ActionClass actionClass = ActionClass.REVERSIBLE;
        PolicyRef policy = PolicyRef.none();
        ApprovalSnapshot approval;
        Instant issuedAt = IAT;
        Instant expiresAt = IAT.plusSeconds(300);
        String audience = AUDIENCE;

        /** 非空表示用测试侧签名器签发（模拟 issuer 拒绝签发的恶意形态）。 */
        String rawKid;

        boolean tamperSignature;

        UUID ctxActor = ACTOR;
        String ctxCommandType = COMMAND_TYPE;
        Map<String, Object> ctxPayload = PAYLOAD;
        List<UUID> ctxTargets = TARGETS;
        Long ctxExpectedVersion = EXPECTED_VERSION;
        String ctxIdempotencyKey = DECISION_ID.toString();
        String ctxAudience = AUDIENCE;
        ToolRegistry registry = REGISTRY;

        DecisionTokenClaims claims() {
            return new DecisionTokenClaims(
                    DecisionTokenClaims.VERSION,
                    DECISION_ID,
                    LEDGER_INTENT_ID,
                    command,
                    argsHash,
                    actor,
                    onBehalfOf,
                    tenant,
                    actionClass,
                    policy,
                    approval,
                    issuedAt,
                    expiresAt,
                    audience);
        }

        String jws() {
            String token = rawKid == null ? ISSUER.sign(claims()) : rawSign(claims(), rawKid);
            return tamperSignature ? tamperSignature(token) : token;
        }

        GuardContext context() {
            return new GuardContext(
                    ctxActor,
                    TENANT,
                    ctxCommandType,
                    ctxPayload,
                    ctxTargets,
                    ctxExpectedVersion,
                    ctxIdempotencyKey,
                    ctxAudience);
        }
    }

    private static DecisionTokenIssuer issuer(byte[] seed, String kid) {
        return new DecisionTokenIssuer(
                new AiProperties.Decision(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(seed),
                        kid,
                        TTL,
                        AUDIENCE));
    }

    private static ApprovalSnapshot approved(String argsHash) {
        return new ApprovalSnapshot(
                "wf-88f2",
                "wf-88f2:1",
                ApprovalStatus.APPROVED,
                NOW.minusSeconds(600),
                ON_BEHALF_OF,
                NOW.minusSeconds(300),
                argsHash,
                3L);
    }

    private static String rawSign(DecisionTokenClaims claims, String kid) {
        return rawSign(
                claims,
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(new JOSEObjectType("agent-decision+jws"))
                        .keyID(kid)
                        .build());
    }

    private static String rawSign(DecisionTokenClaims claims, JWSHeader header) {
        JWSObject jws = new JWSObject(header, new Payload(DecisionTokenPayload.canonical(claims)));
        try {
            jws.sign(TEST_SIGNER);
        } catch (JOSEException e) {
            throw new IllegalStateException("test signing failed", e);
        }
        return jws.serialize();
    }

    private static String tamperSignature(String token) {
        char last = token.charAt(token.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return token.substring(0, token.length() - 1) + replacement;
    }

    private static Map<String, PublicKey> keysOf(Ed25519Jwks jwks) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (Object entry : (List<?>) jwks.document().get("keys")) {
            Map<?, ?> key = (Map<?, ?>) entry;
            keys.put((String) key.get("kid"), publicKey((String) key.get("x")));
        }
        return Map.copyOf(keys);
    }

    private static String jwksX(Ed25519Jwks jwks) {
        Map<?, ?> key = (Map<?, ?>) ((List<?>) jwks.document().get("keys")).get(0);
        return (String) key.get("x");
    }

    private static PublicKey publicKey(String x) {
        byte[] raw = Base64.getUrlDecoder().decode(x);
        boolean xOdd = (raw[raw.length - 1] & 0x80) != 0;
        byte[] littleEndian = raw.clone();
        littleEndian[littleEndian.length - 1] &= (byte) 0x7f;
        byte[] bigEndian = new byte[littleEndian.length];
        for (int i = 0; i < littleEndian.length; i++) {
            bigEndian[i] = littleEndian[littleEndian.length - 1 - i];
        }
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(
                            new EdECPublicKeySpec(
                                    NamedParameterSpec.ED25519,
                                    new EdECPoint(xOdd, new BigInteger(1, bigEndian))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot rebuild ed25519 public key", e);
        }
    }

    private static byte[] pkcs8(byte[] seed) {
        try {
            PrivateKey privateKey =
                    KeyFactory.getInstance("Ed25519")
                            .generatePrivate(
                                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            return privateKey.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot encode ed25519 private key", e);
        }
    }

    private static PrivateKey privateKey(byte[] seed) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build ed25519 private key", e);
        }
    }

    private static byte[] seed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + 1);
        }
        return seed;
    }

    /** 测试侧签名器：用固定 seed 签 issuer 拒绝签发的形态。 */
    private static final JWSSigner TEST_SIGNER =
            new JWSSigner() {

                private final JCAContext jcaContext = new JCAContext();

                @Override
                public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
                    try {
                        Signature signature = Signature.getInstance("Ed25519");
                        signature.initSign(privateKey(SEED));
                        signature.update(signingInput);
                        return Base64URL.encode(signature.sign());
                    } catch (GeneralSecurityException e) {
                        throw new JOSEException("test signing failed", e);
                    }
                }

                @Override
                public Set<JWSAlgorithm> supportedJWSAlgorithms() {
                    return Set.of(JWSAlgorithm.EdDSA);
                }

                @Override
                public JCAContext getJCAContext() {
                    return jcaContext;
                }
            };
}
