package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.idgen.IdGenClient;
import cn.byteforce.seedj.id.IdGenerator;

/** coord 分段 ID 的字符串适配器：十进制字符串形态，委托 Long 生成器发号。 */
final class CoordIdGenerator implements IdGenerator<String> {

    private final CoordLongIdGenerator delegate;

    CoordIdGenerator(IdGenClient client, String name, int batchSize) {
        this.delegate = new CoordLongIdGenerator(client, name, batchSize);
    }

    @Override
    public String id() {
        return Long.toString(delegate.id());
    }

    @Override
    public void close() {
        delegate.close();
    }
}
