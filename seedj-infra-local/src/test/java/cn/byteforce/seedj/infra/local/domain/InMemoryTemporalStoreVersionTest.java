package cn.byteforce.seedj.infra.local.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 内存存储的版本 CAS：期望版本与当前版本不等即 409。 */
class InMemoryTemporalStoreVersionTest {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final UUID ID = UUID.randomUUID();

    private static final UUID ACTOR = UUID.randomUUID();

    private static TemporalStore store() {
        return new InMemoryTemporalStore(() -> NOW);
    }

    private static AppendRequest create(long expected) {
        return AppendRequest.create("probe", ID, NOW, null, ACTOR,
                Map.of("name", "v1"), expected);
    }

    @Test
    @DisplayName("stale expected version 被拒绝")
    void staleExpectedVersionIsRejected() {
        TemporalStore store = store();
        store.append(create(0L));
        store.append(AppendRequest.correction("probe", ID, NOW, null, ACTOR,
                Map.of("name", "v2"), 1L));

        assertThatThrownBy(() -> store.append(AppendRequest.correction("probe", ID, NOW, null,
                ACTOR, Map.of("name", "stale"), 1L)))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.VERSION_CONFLICT);
                    assertThat(error.details()).containsEntry("expected_version", 1L);
                    assertThat(error.details()).containsEntry("current_version", 2L);
                });
        assertThat(store.current("probe", ID).orElseThrow().version().version()).isEqualTo(2);
        assertThat(store.current("probe", ID).orElseThrow().fields())
                .containsEntry("name", "v2");
    }

    @Test
    @DisplayName("create with expected 0 只允许创建一次")
    void createExpectedZeroRequiresMissingRow() {
        TemporalStore store = store();
        store.append(create(0L));

        assertThatThrownBy(() -> store.append(create(0L)))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("null expected 保持旧语义：不校验")
    void nullExpectedSkipsCheck() {
        TemporalStore store = store();
        store.append(AppendRequest.create("probe", ID, NOW, null, ACTOR,
                Map.of("name", "v1")));
        store.append(AppendRequest.correction("probe", ID, NOW, null, ACTOR,
                Map.of("name", "v2")));

        assertThat(store.find("probe", ID, cn.byteforce.seedj.domain.TemporalQuery.current(),
                ScopePredicate.all()).orElseThrow().version().version()).isEqualTo(2);
    }

    @Test
    @DisplayName("expectedVersion 正确时递增")
    void matchingExpectedVersionSucceeds() {
        TemporalStore store = store();
        store.append(create(0L));
        store.append(AppendRequest.correction("probe", ID, NOW, null, ACTOR,
                Map.of("name", "v2"), 1L));

        assertThat(store.current("probe", ID).orElseThrow().version().version()).isEqualTo(2);
    }
}
