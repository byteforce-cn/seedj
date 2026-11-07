package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.EventStream;
import cn.byteforce.seedj.cqrs.IdCodec;
import cn.byteforce.seedj.cqrs.IdCodecs;
import cn.byteforce.seedj.cqrs.StreamId;
import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.cqrs.spi.EventStoreContractTest;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventTypeRegistry;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL 事件存储：公共契约 + 持久化重开 + 并发裁决 + 损坏检测 + 事务参与。
 */
@Testcontainers
class PostgresEventStoreContractTest extends EventStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new EventSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE event_stream RESTART IDENTITY");
    }

    @Override
    protected EventStore<String> newStore() {
        return new PostgresEventStore<>(jdbc, IdCodecs.strings(), new EventTypeRegistry());
    }

    @Test
    @DisplayName("关闭后重开：新实例注册聚合并从数据库还原同一事件")
    void reopensAndReplaysPersistedStream() {
        StreamId<String> stream = StreamId.of("test.order", "persisted");
        newStore().append(stream, 0, List.of(new Created("o-1")));

        PostgresEventStore<String> reopened =
                new PostgresEventStore<>(jdbc, IdCodecs.strings(), new EventTypeRegistry());
        reopened.register(Order.class);

        EventStream<String> read = reopened.read(stream);
        assertThat(read.version()).isEqualTo(1);
        assertThat(read.events()).containsExactly(new Created("o-1"));
    }

    @Test
    @DisplayName("并发追加同一条流：一个成功，另一个 CONCURRENCY_CONFLICT，流不污染")
    void concurrentAppendHasSingleWinner() throws Exception {
        StreamId<String> stream = StreamId.of("test.order", "concurrent");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> append = () -> {
            EventStore<String> store = newStore();
            ready.countDown();
            go.await();
            try {
                return store.append(stream, 0, List.of(new Created("o-1")));
            } catch (CqrsException e) {
                return e;
            }
        };
        try {
            Future<Object> first = pool.submit(append);
            Future<Object> second = pool.submit(append);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Object> outcomes = new ArrayList<>();
            outcomes.add(first.get());
            outcomes.add(second.get());

            assertThat(outcomes).filteredOn(value -> value instanceof Long)
                    .containsExactly(1L);
            assertThat(outcomes).filteredOn(value -> value instanceof CqrsException)
                    .singleElement()
                    .isInstanceOfSatisfying(CqrsException.class, error ->
                            assertThat(error.getErrorCode())
                                    .isEqualTo(CqrsErrorCode.CONCURRENCY_CONFLICT));
            PostgresEventStore<String> reader =
                    new PostgresEventStore<>(jdbc, IdCodecs.strings(), new EventTypeRegistry());
            reader.register(Order.class);
            assertThat(reader.read(stream).version()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("版本空洞按 CORRUPT_STREAM 失败，而不是把 3 条历史读成 2 条")
    void gapInStreamIsCorrupt() {
        StreamId<String> stream = StreamId.of("test.order", "gap");
        EventStore<String> store = newStore();
        store.append(stream, 0, List.of(
                new Created("o-1"), new Confirmed("o-1"), new Renamed("o-1", "new")));
        jdbc.update("DELETE FROM event_stream"
                + " WHERE aggregate_type = ? AND stream_key = ? AND version = 2",
                "test.order", "gap");

        assertThatThrownBy(() -> store.read(stream))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.CORRUPT_STREAM));
        assertThatThrownBy(() -> store.append(stream, 3, List.of(new Renamed("o-1", "x"))))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.CORRUPT_STREAM));
    }

    @Test
    @DisplayName("加入调用方事务：外层回滚后事件流不存在")
    void appendJoinsCallerTransaction() {
        StreamId<String> stream = StreamId.of("test.order", "rollback");
        EventStore<String> store = newStore();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            store.append(stream, 0, List.of(new Created("o-1")));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.read(stream).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("未知事件类型：读取失败关闭为 CORRUPT_STREAM，不静默跳过")
    void unknownEventTypeIsCorrupt() {
        StreamId<String> stream = StreamId.of("test.order", "unknown");
        jdbc.update("""
                INSERT INTO event_stream
                    (aggregate_type, stream_key, version, event_type, event_version, payload)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "test.order", "unknown", 1L, "unknown.event", 1, new byte[]{1});

        assertThatThrownBy(() -> newStore().read(stream))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.CORRUPT_STREAM));
    }

    @Test
    @DisplayName("序列化失败：append 报 INTERNAL_ERROR，且不写入事件")
    void serializationFailureWritesNothing() {
        StreamId<String> stream = StreamId.of("test.order", "serialize");
        EventPayloadSerializer failing = new EventPayloadSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                throw new IllegalStateException("boom");
            }
        };
        PostgresEventStore<String> store = new PostgresEventStore<>(
                jdbc, IdCodecs.strings(), failing, new EventTypeRegistry());

        assertThatThrownBy(() -> store.append(stream, 0, List.of(new Created("o-1"))))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.INTERNAL_ERROR));
        assertThat(store.read(stream).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("序列化器返回 null 或反序列化失败：都失败关闭")
    void serializerContractViolationsAreCorrupt() {
        StreamId<String> stream = StreamId.of("test.order", "bad-serializer");
        EventPayloadSerializer nullPayload = new EventPayloadSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                return null;
            }
        };
        PostgresEventStore<String> writer = new PostgresEventStore<>(
                jdbc, IdCodecs.strings(), nullPayload, new EventTypeRegistry());
        assertThatThrownBy(() -> writer.append(stream, 0, List.of(new Created("o-1"))))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.INTERNAL_ERROR));

        newStore().append(stream, 0, List.of(new Created("o-1")));
        EventPayloadSerializer failingRead = new EventPayloadSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                return new byte[0];
            }

            @Override
            public <T> T deserialize(byte[] payload, Class<T> type) {
                throw new IllegalStateException("cannot read");
            }
        };
        EventTypeRegistry types = new EventTypeRegistry();
        types.register(Created.class);
        PostgresEventStore<String> reader = new PostgresEventStore<>(
                jdbc, IdCodecs.strings(), failingRead, types);
        assertThatThrownBy(() -> reader.read(stream))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.CORRUPT_STREAM));
    }

    @Test
    @DisplayName("空标识、负版本、空事件列表按契约边界处理")
    void boundaryConditions() {
        StreamId<String> stream = StreamId.of("test.order", "boundary");
        EventStore<String> store = newStore();
        IdCodec<String> blank = new IdCodec<>() {
            @Override
            public String encode(String id) {
                return " ";
            }

            @Override
            public String decode(String encoded) {
                return encoded;
            }
        };
        PostgresEventStore<String> blankStore = new PostgresEventStore<>(
                jdbc, blank, new EventTypeRegistry());

        assertThatThrownBy(() -> blankStore.read(stream))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(CqrsErrorCode.INVALID_ID));
        assertThatThrownBy(() -> store.append(stream, -1, List.of(new Created("o-1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.append(stream, -1, List.of())).isEqualTo(-1L);
        List<DomainEvent> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> store.append(stream, 0, withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("没有 DataSource 的 JdbcTemplate 在装配期失败，而不是运行期 NPE")
    void requiresDataSource() {
        assertThatThrownBy(() -> new PostgresEventStore<>(
                new JdbcTemplate(), IdCodecs.strings(), new EventTypeRegistry()))
                .isInstanceOfSatisfying(CqrsException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(CqrsErrorCode.STORE_UNAVAILABLE));
    }

    @Test
    @DisplayName("聚合处理 sealed 事件父类型时，所有 permitted 子类型都自动注册")
    void sealedEventFamilyIsRegistered() {
        StreamId<String> stream = StreamId.of("test.ledger", "l-1");
        newStore().append(stream, 0, List.of(new Paid("p-1")));

        EventTypeRegistry types = new EventTypeRegistry();
        PostgresEventStore<String> reader =
                new PostgresEventStore<>(jdbc, IdCodecs.strings(), types);
        reader.register(Ledger.class);

        assertThat(reader.read(stream).events()).containsExactly(new Paid("p-1"));
    }

    @Test
    @DisplayName("schema capability 可重复初始化并校验")
    void schemaCapabilityIsIdempotent() {
        EventSchemaCapability capability =
                new EventSchemaCapability(new JdbcMigrationRunner(dataSource));

        capability.initialize();
        assertThat(capability.isReady()).isTrue();
        capability.validate();
    }

    /**
     * 只用于让 {@link PostgresEventStore#register(Class)} 从 {@code apply} 方法发现事件类型。
     */
    private static final class Order extends AbstractAggregateRoot<String> {

        private Order(String id) {
            super(id);
        }

        private void apply(Created event) {
            // state is irrelevant for the registration check
        }
    }

    private sealed interface PaymentEvent extends DomainEvent permits Paid, Refunded {
    }

    private record Paid(String paymentId) implements PaymentEvent {
    }

    private record Refunded(String paymentId) implements PaymentEvent {
    }

    private static final class Ledger extends AbstractAggregateRoot<String> {

        private Ledger(String id) {
            super(id);
        }

        private void apply(PaymentEvent event) {
            // handles the sealed family
        }
    }
}
