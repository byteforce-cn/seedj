package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.cqrs.IdempotencyFingerprint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 两个指纹的契约：{@link ArgsHash} 覆盖版本、{@link IdemFingerprint} 与既有幂等指纹一致。
 *
 * <p>两者口径不同不能合并：审批要让“版本变化”变成新的影响面，幂等要让“同键重放”视为同一请求。
 */
class ArgsHashTest {

    private static final UUID UUID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UUID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("args_hash 覆盖 targets 与 expectedVersion")
    void args_hash_covers_targets_and_expected_version() {
        var base = new CommandFingerprint("ticket.create", Map.of("title", "t"), List.of(UUID_1), 7L);

        assertThat(ArgsHash.of(base))
                .isNotEqualTo(
                        ArgsHash.of(
                                new CommandFingerprint(
                                        "ticket.create", Map.of("title", "t"), List.of(UUID_1), 8L)));
        assertThat(ArgsHash.of(base))
                .isNotEqualTo(
                        ArgsHash.of(
                                new CommandFingerprint(
                                        "ticket.create", Map.of("title", "t"), List.of(UUID_2), 7L)));
        assertThat(ArgsHash.of(base)).startsWith("sha256:");
    }

    @Test
    @DisplayName("idem_fingerprint 与 seedj 既有幂等指纹逐位一致")
    void idem_fingerprint_matches_seedj_idempotency_fingerprint() {
        var fp = new CommandFingerprint("ticket.create", Map.of("title", "t"), List.of(UUID_1), 7L);
        var other =
                new CommandFingerprint("ticket.create", Map.of("title", "t"), List.of(UUID_1), null);

        assertThat(IdemFingerprint.of(fp)).isEqualTo(IdemFingerprint.of(other));
        assertThat(IdemFingerprint.of(fp))
                .isEqualTo(
                        IdempotencyFingerprint.of(
                                "ticket.create", Map.of("title", "t"), List.of(UUID_1)));
    }

    @Test
    @DisplayName("expectedVersion 为 null 与省略不同：args_hash 区分，idem_fingerprint 不区分")
    void expected_version_null_is_hashed_as_null_key() {
        var withoutVersion =
                new CommandFingerprint("ticket.create", Map.of("title", "t"), List.of(UUID_1), null);
        var withVersion =
                new CommandFingerprint("ticket.create", Map.of("title", "t"), List.of(UUID_1), 7L);

        assertThat(ArgsHash.of(withoutVersion)).isNotEqualTo(ArgsHash.of(withVersion));
        assertThat(IdemFingerprint.of(withoutVersion)).isEqualTo(IdemFingerprint.of(withVersion));
    }
}
