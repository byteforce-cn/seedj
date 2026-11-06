package cn.byteforce.seedj.infra.local.domain;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.TemporalStoreContractTest;
import java.util.List;

/**
 * 内存实现跑公共契约套件。
 */
class InMemoryTemporalStoreContractTest extends TemporalStoreContractTest {

    private final TestClock clock = new TestClock(T0);

    private final InMemoryTemporalStore store = new InMemoryTemporalStore(clock,
            new ObjectSchemaRegistry(List.of(ObjectSchema.builder("probe", "probe_object")
                    .field(cn.byteforce.seedj.domain.FieldDefinition
                            .filterable("name", FieldType.TEXT).withSortable())
                    .filterable("status", FieldType.TEXT)
                    .filterable("hire_date", FieldType.DATE)
                    .field("secret", FieldType.TEXT)
                    .build())));

    @Override
    protected TemporalStore store() {
        return store;
    }

    @Override
    protected TestClock clock() {
        return clock;
    }

    @Override
    protected void reset() {
        store.clear();
    }
}
