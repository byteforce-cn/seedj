package cn.byteforce.seedj.infra.local.ai;

import cn.byteforce.seedj.ai.DecisionAdmission;
import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存准入存储：开发与测试用，语义与 PostgreSQL 实现一致。
 *
 * <p>同 decision 同命令/目标/参数/主体幂等；任一不同即冲突，不覆盖首次准入。过期清理是惰性遍历删除。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryDecisionAdmissionStore implements DecisionAdmissionStore {

    private final ConcurrentHashMap<UUID, DecisionAdmission> rows = new ConcurrentHashMap<>();

    @Override
    public void admit(DecisionAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        DecisionAdmission existing = rows.putIfAbsent(admission.decisionId(), admission);
        if (existing != null && !same(existing, admission)) {
            throw new IllegalStateException(
                    "admission conflict: decision " + admission.decisionId());
        }
    }

    @Override
    public Optional<DecisionAdmission> find(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        return Optional.ofNullable(rows.get(decisionId));
    }

    @Override
    public int deleteExpired(Instant before) {
        Objects.requireNonNull(before, "before");
        AtomicInteger deleted = new AtomicInteger();
        rows.entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getValue().expiresAt().isBefore(before)) {
                                deleted.incrementAndGet();
                                return true;
                            }
                            return false;
                        });
        return deleted.get();
    }

    private static boolean same(DecisionAdmission left, DecisionAdmission right) {
        return left.tenantId().equals(right.tenantId())
                && left.commandType().equals(right.commandType())
                && left.targets().equals(right.targets())
                && left.argsHash().equals(right.argsHash())
                && left.actor().equals(right.actor());
    }

    /** 测试用：清空。 */
    void clear() {
        rows.clear();
    }

    /** 测试用：全部行。 */
    List<DecisionAdmission> all() {
        return List.copyOf(rows.values());
    }
}
