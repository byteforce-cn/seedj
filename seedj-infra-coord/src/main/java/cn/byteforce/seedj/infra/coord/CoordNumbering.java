package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.idgen.IdGenClient;
import cn.byteforce.seedj.numbering.Numbering;
import java.util.Objects;
import java.util.regex.Pattern;

/** coord 分段 ID 的编号适配：每个 scope 映射到独立的 idgen 名称。 */
final class CoordNumbering implements Numbering {

    private static final Pattern SCOPE = Pattern.compile("^[a-z0-9_]+$");

    private final IdGenClient client;
    private final String prefix;

    CoordNumbering(IdGenClient client, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("coord numbering prefix must not be blank");
        }
        this.prefix = prefix;
    }

    @Override
    public long next(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (!SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("illegal numbering scope: " + scope);
        }
        try {
            return client.nextId(prefix + ":" + scope);
        } catch (CoordException e) {
            throw new IllegalStateException(
                    "coord numbering failed for " + scope + ": " + e.getErrorCode(), e);
        }
    }
}
