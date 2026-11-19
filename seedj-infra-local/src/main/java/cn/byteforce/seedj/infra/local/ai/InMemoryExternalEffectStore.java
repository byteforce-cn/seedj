package cn.byteforce.seedj.infra.local.ai;

import cn.byteforce.seedj.ai.ExternalEffect;
import cn.byteforce.seedj.ai.ExternalEffectState;
import cn.byteforce.seedj.ai.spi.ExternalEffectStore;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存外部效果存储：语义与 PostgreSQL 实现一致，进程重启即丢。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryExternalEffectStore implements ExternalEffectStore {

    private final ConcurrentHashMap<UUID, ExternalEffect> rows = new ConcurrentHashMap<>();

    @Override
    public ExternalEffect prepare(ExternalEffect effect) {
        Objects.requireNonNull(effect, "effect");
        ExternalEffect existing = rows.putIfAbsent(effect.decisionId(), effect);
        if (existing == null) {
            return effect;
        }
        if (!same(existing, effect)) {
            throw new IllegalStateException(
                    "external effect conflict: decision " + effect.decisionId());
        }
        return existing;
    }

    @Override
    public ExternalEffect complete(
            UUID decisionId,
            ExternalEffectState state,
            String externalRef,
            String errorCode,
            Instant now) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        ExternalEffect updated =
                rows.compute(
                        decisionId,
                        (key, existing) -> {
                            if (existing == null) {
                                throw new IllegalArgumentException(
                                        "unknown external effect: " + decisionId);
                            }
                            return new ExternalEffect(
                                    existing.decisionId(),
                                    existing.tenantId(),
                                    existing.commandType(),
                                    existing.argsHash(),
                                    state,
                                    externalRef,
                                    errorCode,
                                    existing.preparedAt(),
                                    now);
                        });
        return updated;
    }

    @Override
    public Optional<ExternalEffect> find(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        return Optional.ofNullable(rows.get(decisionId));
    }

    private static boolean same(ExternalEffect left, ExternalEffect right) {
        return left.tenantId().equals(right.tenantId())
                && left.commandType().equals(right.commandType())
                && left.argsHash().equals(right.argsHash());
    }
}
