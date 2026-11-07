package cn.byteforce.seedj.core.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认解析器：按 {@link ContextWriter#order()} 升序执行全部 writer，再冻结快照。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DefaultContextResolver implements ContextResolver {

    private final List<ContextWriter> writers;

    /**
     * 构造解析器。
     *
     * @param writers 写入扩展点
     */
    public DefaultContextResolver(List<ContextWriter> writers) {
        Objects.requireNonNull(writers, "writers");
        List<ContextWriter> copy = new ArrayList<>(writers.size());
        for (ContextWriter writer : writers) {
            copy.add(Objects.requireNonNull(writer, "writer"));
        }
        copy.sort(Comparator.comparingInt(ContextWriter::order));
        this.writers = List.copyOf(copy);
    }

    @Override
    public ScopeContext resolve(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        Map<String, Object> facts = new LinkedHashMap<>();
        ContextSink sink =
                new ContextSink() {
                    @Override
                    public <T> void put(ScopeKey<T> key, T value) {
                        Objects.requireNonNull(key, "key");
                        if (facts.containsKey(key.name())) {
                            throw new IllegalStateException("duplicate scope fact: " + key.name());
                        }
                        facts.put(key.name(), key.type().validate(key.name(), value));
                    }
                };
        for (ContextWriter writer : writers) {
            writer.write(sink, caller);
        }
        return ScopeContext.of(facts);
    }
}
