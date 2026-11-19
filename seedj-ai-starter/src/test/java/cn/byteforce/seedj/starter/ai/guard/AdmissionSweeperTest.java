package cn.byteforce.seedj.starter.ai.guard;

import static org.assertj.core.api.Assertions.assertThatCode;

import cn.byteforce.seedj.ai.DecisionAdmission;
import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 过期扫描入口：调用存储并按宽限删除，失败不外抛。 */
class AdmissionSweeperTest {

    @Test
    @DisplayName("run 调 deleteExpired 且不抛")
    void runDeletesExpired() {
        AtomicInteger deleted = new AtomicInteger();
        DecisionAdmissionStore store =
                new DecisionAdmissionStore() {
                    @Override
                    public void admit(DecisionAdmission admission) {}

                    @Override
                    public Optional<DecisionAdmission> find(UUID decisionId) {
                        return Optional.empty();
                    }

                    @Override
                    public int deleteExpired(Instant before) {
                        return deleted.incrementAndGet();
                    }
                };

        new AdmissionSweeper(store, java.time.Duration.ofHours(1)).run();

        org.assertj.core.api.Assertions.assertThat(deleted.get()).isEqualTo(1);
        assertThatCode(() -> new AdmissionSweeper(store, java.time.Duration.ofHours(1)).run())
                .doesNotThrowAnyException();
    }
}
