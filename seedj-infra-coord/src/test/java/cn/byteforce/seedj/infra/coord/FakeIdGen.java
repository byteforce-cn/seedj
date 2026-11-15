package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.idgen.IdGenClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

final class FakeIdGen implements IdGenClient {

    private final AtomicLong next = new AtomicLong();
    private final List<String> names = new CopyOnWriteArrayList<>();

    @Override
    public long nextId(String name) {
        names.add(name);
        return next.incrementAndGet();
    }

    @Override
    public List<Long> nextBatch(String name, int count) {
        names.add(name);
        List<Long> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(next.incrementAndGet());
        }
        return out;
    }

    List<String> names() {
        return List.copyOf(names);
    }
}
