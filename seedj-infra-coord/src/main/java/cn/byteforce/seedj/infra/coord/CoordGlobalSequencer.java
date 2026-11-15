package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.idgen.IdGenClient;
import cn.byteforce.seedj.core.clock.spi.GlobalSequencer;
import java.util.Objects;

/** coord 全局序列锚点：用 idgen 的单调分段序列表达。 */
final class CoordGlobalSequencer implements GlobalSequencer {

    private final CoordEndpoint endpoint;
    private final IdGenClient client;
    private final String name;

    CoordGlobalSequencer(CoordEndpoint endpoint, IdGenClient client, String name) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("coord clock name must not be blank");
        }
        this.name = name;
    }

    @Override
    public long next() {
        try {
            return client.nextId(name);
        } catch (CoordException e) {
            throw new IllegalStateException(
                    "coord global sequencer failed at " + endpoint + ": " + e.getErrorCode(), e);
        }
    }
}
