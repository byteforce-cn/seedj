package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.idgen.IdGenClient;
import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.id.IdGenerator;
import java.util.List;
import java.util.Objects;

/**
 * coord 分段 ID 的 Long 生成器：每次预取一段，段内本地发号。
 *
 * <p>允许空洞，不要求连续；同一实例的发号顺序严格递增。
 */
final class CoordLongIdGenerator implements IdGenerator<Long> {

    private final IdGenClient client;
    private final String name;
    private final int batchSize;

    private List<Long> batch = List.of();
    private int cursor;
    private boolean closed;

    CoordLongIdGenerator(IdGenClient client, String name, int batchSize) {
        this.client = Objects.requireNonNull(client, "client");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("coord id name must not be blank");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("coord id batchSize must be in 1..10000");
        }
        this.name = name;
        this.batchSize = batchSize;
    }

    @Override
    public synchronized Long id() {
        if (closed) {
            throw new IdException(IdErrorCode.PROVIDER_CLOSED, "coord id generator is closed");
        }
        if (cursor >= batch.size()) {
            fetch();
        }
        return batch.get(cursor++);
    }

    private void fetch() {
        try {
            List<Long> next = client.nextBatch(name, batchSize);
            if (next == null || next.isEmpty()) {
                throw new IdException(
                        IdErrorCode.STATE_UNAVAILABLE,
                        "coord idgen returned an empty batch for " + name);
            }
            batch = List.copyOf(next);
            cursor = 0;
        } catch (CoordException e) {
            throw CoordErrors.id(e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        batch = List.of();
        cursor = 0;
    }
}
