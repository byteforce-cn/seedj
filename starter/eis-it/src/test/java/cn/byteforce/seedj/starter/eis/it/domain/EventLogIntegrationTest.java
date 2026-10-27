package cn.byteforce.seedj.starter.eis.it.domain;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.starter.eis.domain.EventLogStore;
import cn.byteforce.seedj.starter.eis.domain.EventStream;
import cn.byteforce.seedj.starter.eis.domain.OptimisticLockingException;
import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;
import cn.byteforce.seedj.starter.eis.domain.jackson.JacksonEventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.jdbc.PostgresEventLogStore;
import cn.byteforce.seedj.starter.eis.domain.spi.EventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcaster;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcasterRegistry;
import cn.byteforce.seedj.starter.eis.it.AbstractEisIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件脊柱集成测试（真实 PG16）：追加、尾部加载、乐观锁、租户隔离与 upcasting。
 *
 * <p>这些用例即「业务开发契约」：结论会反向驱动 eis 契约的补齐（见 M7 issue #27）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EventLogIntegrationTest extends AbstractEisIntegrationTest {

    private static final long TENANT = 4242L;

    private final EventTypeRegistry eventTypes = new EventTypeRegistry();

    private final EventUpcasterRegistry upcasters = new EventUpcasterRegistry();

    private EventLogStore store;

    @BeforeEach
    void setUp() {
        RequestContext.setTenantId(TENANT);
        eventTypes.register(Opened.class);
        eventTypes.register(Deposited.class);
        upcasters.register(EventUpcaster.of("it.account.deposited", 1, 2, payload -> {
            payload.putIfAbsent("currency", "CNY");
            return payload;
        }));
        EventPayloadCodec codec =
                new JacksonEventPayloadCodec(JsonMapper.builder().build(), eventTypes, upcasters);
        store = new PostgresEventLogStore(jdbcTemplate(), codec, eventTypes,
                RequestContext::tenantId, true);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("追加后可按全量与尾部两种方式读回，payload 的 long 保持数值")
    void appendThenLoad() {
        store.append("it-1", "Account", 0L, List.of(new Opened("alice")));
        store.append("it-1", "Account", 1L, List.of(new Deposited(100L, "CNY")));

        EventStream all = store.loadStream("it-1");
        assertThat(all.currentVersion()).isEqualTo(2L);
        assertThat(all.events()).hasSize(2);
        assertThat(((Deposited) all.events().get(1)).getAmount()).isEqualTo(100L);

        EventStream tail = store.loadStream("it-1", 1L);
        assertThat(tail.events()).hasSize(1);
        assertThat(tail.currentVersion()).isEqualTo(2L);

        Integer numeric = jdbcTemplate().queryForObject(
                "SELECT count(*) FROM evt_log WHERE payload -> 'amount' = '100'::jsonb", Integer.class);
        assertThat(numeric).isEqualTo(1);
    }

    @Test
    @DisplayName("乐观锁：旧版本追加失败，异常带 expected/actual")
    void optimisticLocking() {
        store.append("it-2", "Account", 0L, List.of(new Opened("alice")));
        store.append("it-2", "Account", 1L, List.of(new Deposited(10L, "CNY")));

        assertThatThrownBy(() -> store.append("it-2", "Account", 1L, List.of(new Deposited(20L, "CNY"))))
                .isInstanceOf(OptimisticLockingException.class)
                .hasMessageContaining("expected version: 1")
                .hasMessageContaining("actual version:   2");
    }

    @Test
    @DisplayName("旧 payload（schema v1）经 upcaster 读成当前版本")
    void upcastsStoredPayload() {
        JdbcTemplate jdbc = jdbcTemplate();
        new TransactionTemplate(transactionManager()).executeWithoutResult(status -> {
            jdbc.update("""
                            INSERT INTO evt_stream (stream_id, aggregate_type, tenant_id, current_version)
                            VALUES (?, ?, ?, 1)
                            """, "it-3", "Account", TENANT);
            jdbc.update("""
                            INSERT INTO evt_log (event_id, stream_id, aggregate_type, event_type,
                                                 schema_version, stream_version, tenant_id, payload,
                                                 occurred_at, recorded_at)
                            VALUES (gen_random_uuid(), ?, ?, ?, 1, 1, ?, CAST(? AS jsonb),
                                    clock_timestamp(), clock_timestamp())
                            """, "it-3", "Account", "it.account.deposited", TENANT,
                    "{\"amount\":42}");
        });

        Deposited event = (Deposited) store.loadStream("it-3").events().get(0);

        assertThat(event.getAmount()).isEqualTo(42L);
        assertThat(event.getCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("租户必填：无租户上下文读写直接失败（fail-closed）")
    void tenantRequiredFailsClosed() {
        RequestContext.clear();

        assertThatThrownBy(() -> store.loadStream("it-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
        assertThatThrownBy(() -> store.append("it-4", "Account", 0L, List.of(new Opened("alice"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    @Test
    @DisplayName("租户隔离：另一个租户读不到本租户的流")
    void tenantIsolation() {
        store.append("it-5", "Account", 0L, List.of(new Opened("alice")));

        RequestContext.setTenantId(9999L);

        assertThat(store.loadStream("it-5").isEmpty()).isTrue();
        assertThatThrownBy(() -> store.append("it-5", "Account", 0L, List.of(new Opened("bob"))))
                .isInstanceOf(OptimisticLockingException.class);
    }

    @Test
    @DisplayName("空事件列表被拒绝")
    void rejectsEmptyEvents() {
        assertThatThrownBy(() -> store.append("it-6", "Account", 0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
