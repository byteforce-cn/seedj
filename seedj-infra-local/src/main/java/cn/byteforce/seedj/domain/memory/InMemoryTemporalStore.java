package cn.byteforce.seedj.domain.memory;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Sealing;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存双时间轴对象存储（单进程，不跨服务共享）。
 *
 * <p>语义与 PostgreSQL 实现一致：封口/追加都委托 {@link Sealing}，
 * 每个对象的版本列表独立加锁，保证同一对象的封口与追加原子。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryTemporalStore implements TemporalStore {

    private final Clock clock;

    private final Map<Key, List<TemporalRow>> rows = new ConcurrentHashMap<>();

    /**
     * 构造内存存储。
     *
     * @param clock 取时入口
     */
    public InMemoryTemporalStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query) {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(query, "query");
        List<TemporalRow> versions = rows.get(new Key(objectType, id));
        if (versions == null) {
            return Optional.empty();
        }
        synchronized (versions) {
            Optional<TemporalRow> hit = versions.stream()
                    .filter(row -> matches(row.version(), query))
                    .max(Comparator.comparingLong(row -> row.version().version()));
            if (hit.isPresent() && query.minVersion() != null
                    && hit.get().version().version() < query.minVersion()) {
                long readable = hit.get().version().version();
                throw new ContractException(ContractErrorCode.READ_MODEL_NOT_READY,
                        "readable version " + readable + " is below required "
                                + query.minVersion(),
                        Map.of("minVersion", query.minVersion(), "readableVersion", readable),
                        null);
            }
            return hit;
        }
    }

    @Override
    public AppendResult append(AppendRequest request) {
        Objects.requireNonNull(request, "request");
        List<TemporalRow> versions = rows.computeIfAbsent(
                new Key(request.objectType(), request.id()), key -> new ArrayList<>());
        synchronized (versions) {
            TemporalRow current = versions.stream()
                    .filter(row -> row.version().supersededAt() == null)
                    .findFirst()
                    .orElse(null);
            AppendResult result = Sealing.next(request, current, clock.now());
            result.superseded().ifPresent(old -> replace(versions, old));
            versions.add(result.appended());
            return result;
        }
    }

    @Override
    public List<TemporalRow> history(String objectType, UUID id) {
        List<TemporalRow> versions = rows.get(new Key(objectType, id));
        if (versions == null) {
            return List.of();
        }
        synchronized (versions) {
            return versions.stream()
                    .sorted(Comparator.comparing(row -> row.version().recordedAt()))
                    .toList();
        }
    }

    /**
     * 清空全部对象（测试用）。
     */
    public void clear() {
        rows.clear();
    }

    private static boolean matches(ObjectVersion version, TemporalQuery query) {
        if (query.asOf() == null && query.recordedAsOf() == null) {
            return version.isCurrent();
        }
        if (query.asOf() != null && !version.validAt(query.asOf())) {
            return false;
        }
        return query.recordedAsOf() == null || version.recordedAt(query.recordedAsOf());
    }

    private static void replace(List<TemporalRow> versions, TemporalRow sealed) {
        for (int i = 0; i < versions.size(); i++) {
            TemporalRow candidate = versions.get(i);
            if (candidate.version().id().equals(sealed.version().id())
                    && candidate.version().recordedAt().equals(sealed.version().recordedAt())) {
                versions.set(i, sealed);
                return;
            }
        }
        throw new IllegalStateException("sealed row not found: " + sealed.version().id());
    }

    private record Key(String objectType, UUID id) {
    }
}
