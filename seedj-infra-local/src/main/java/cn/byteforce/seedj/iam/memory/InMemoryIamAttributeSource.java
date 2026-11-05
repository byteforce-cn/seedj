package cn.byteforce.seedj.iam.memory;

import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import cn.byteforce.seedj.iam.authz.IamAttributeSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内存属性源：按 principal + 业务时点选最新快照，供开发与测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryIamAttributeSource implements IamAttributeSource {

    private final List<AttributeSnapshot> snapshots = new ArrayList<>();

    private final Set<String> unavailable = new HashSet<>();

    @Override
    public synchronized AttributeLookup lookup(String principal, Instant asOf,
                                               Instant recordedAsOf) {
        if (unavailable.contains(principal)) {
            return AttributeLookup.unavailable();
        }
        AttributeSnapshot selected = snapshots.stream()
                .filter(snapshot -> snapshot.principal().equals(principal))
                .filter(snapshot -> !snapshot.asOf().isAfter(asOf))
                .filter(snapshot -> recordedAsOf == null
                        || !snapshot.recordedAt().isAfter(recordedAsOf))
                .max(Comparator.comparing(AttributeSnapshot::asOf))
                .orElse(null);
        if (selected == null) {
            return AttributeLookup.missing();
        }
        return new AttributeLookup(selected.stateAt(asOf), selected);
    }

    /**
     * 保存快照。
     *
     * @param snapshot 快照
     */
    public synchronized void save(AttributeSnapshot snapshot) {
        snapshots.add(snapshot);
    }

    /**
     * 标记属主不可达。
     *
     * @param principal 主体
     */
    public synchronized void markSourceUnavailable(String principal) {
        unavailable.add(principal);
    }

    /**
     * 清空全部数据（测试用）。
     */
    public synchronized void clear() {
        snapshots.clear();
        unavailable.clear();
    }
}
