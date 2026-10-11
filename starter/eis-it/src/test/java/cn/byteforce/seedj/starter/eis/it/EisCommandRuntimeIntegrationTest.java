package cn.byteforce.seedj.starter.eis.it;

import cn.byteforce.seedj.starter.eis.audit.EisAuditChain;
import cn.byteforce.seedj.starter.eis.audit.EisAuditHead;
import cn.byteforce.seedj.starter.eis.core.EisClock;
import cn.byteforce.seedj.starter.eis.core.EisCommandContext;
import cn.byteforce.seedj.starter.eis.core.EisCommandHandler;
import cn.byteforce.seedj.starter.eis.core.EisCommandHandlerResult;
import cn.byteforce.seedj.starter.eis.core.EisCommandRejectedException;
import cn.byteforce.seedj.starter.eis.core.EisCommandRequest;
import cn.byteforce.seedj.starter.eis.core.EisCommandResult;
import cn.byteforce.seedj.starter.eis.core.EisCommandRuntime;
import cn.byteforce.seedj.starter.eis.core.EisCommandStore;
import cn.byteforce.seedj.starter.eis.core.EisEvent;
import cn.byteforce.seedj.starter.eis.core.EisEventDraft;
import cn.byteforce.seedj.starter.eis.core.EisEventStore;
import cn.byteforce.seedj.starter.eis.core.EisObjectRef;
import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectRow;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import cn.byteforce.seedj.starter.eis.core.EisOutboxDispatcher;
import cn.byteforce.seedj.starter.eis.core.EisRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令受理闭环真库集成测试：命令/事件/对象/审计/outbox 同事务、幂等重放与并发裁决、
 * 拒绝路径、提交后分发（outbox + 失效钩子）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisCommandRuntimeIntegrationTest extends AbstractEisIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final String CREATE = "it.widget.create";

    private static final String UPDATE = "it.widget.update";

    private EisObjectRepository repository;

    private EisCommandStore commandStore;

    private EisEventStore eventStore;

    private EisAuditChain chain;

    private EisCommandRuntime runtime;

    private final List<EisOutboxDispatcher.EisOutboxEntry> dispatched = new CopyOnWriteArrayList<>();

    private final List<EisEvent> notified = new CopyOnWriteArrayList<>();

    private final List<EisAuditHead> anchored = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUpRuntime() {
        EisClock clock = EisClock.monotonic(EisClock.system());
        repository = new EisObjectRepository(jdbcTemplate(),
                new EisObjectTypeRegistry(List.of(EisObjectRepositoryIntegrationTest.widgetType())));
        commandStore = new EisCommandStore(jdbcTemplate());
        eventStore = new EisEventStore(jdbcTemplate());
        chain = new EisAuditChain(jdbcTemplate(), clock, anchored::add);
        EisOutboxDispatcher dispatcher = dispatched::add;
        runtime = new EisCommandRuntime(clock,
                new EisObjectTypeRegistry(List.of(EisObjectRepositoryIntegrationTest.widgetType())),
                repository, commandStore, eventStore, chain, null,
                List.of(new CreateHandler(), new UpdateHandler(), new RejectHandler()),
                List.of(new NameUniqueRule()), dispatcher, events -> notified.addAll(events),
                transactionManager());
    }

    private EisCommandRequest request(String type, String payload, String key) {
        return new EisCommandRequest(type, MAPPER.readTree(payload), key, List.of(), ACTOR);
    }

    @Test
    void acceptedCommandWritesEverythingInOneTransaction() {
        EisCommandResult result = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k1"));

        assertThat(result.getStatus()).isEqualTo("accepted");
        assertThat(result.getCreated().getObjectType()).isEqualTo("it_widget");
        assertThat(result.getEventIds()).hasSize(1);

        EisCommandStore.EisStoredCommand command = commandStore.findCommand(result.getCommandId()).orElseThrow();
        assertThat(command.getStatus()).isEqualTo("accepted");
        assertThat(command.getEventIds()).isEqualTo(result.getEventIds());
        assertThat(command.getCreated()).isNotNull();
        assertThat(command.getCreated().getObjectType()).isEqualTo("it_widget");
        assertThat(command.getCreated().getId()).isEqualTo(result.getCreated().getId());

        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM event WHERE type = 'it.widget.created'",
                Long.class)).isEqualTo(1L);
        assertThat(repository.findCurrent("it_widget", result.getCreated().getId())).isPresent()
                .get().satisfies(row -> assertThat(row.field("name")).isEqualTo("alice"));

        assertThat(chain.verify()).isEmpty();
        List<?> entries = chain.loadEntries();
        assertThat(entries).hasSize(1);
        assertThat(chain.head().getHash()).isEqualTo(chain.loadEntries().get(0).getHash());

        // 提交后（afterCommit）：outbox 分发 + 失效钩子；分发成功回填 dispatched_at
        assertThat(dispatched).hasSize(1);
        assertThat(notified).hasSize(1);
        assertThat(eventStore.findUndispatched(10)).isEmpty();
    }

    @Test
    void acceptedCommandRoundTripsDateAndNumericFields() {
        EisCommandResult result = runtime.submit(request(CREATE,
                "{\"name\":\"erin\",\"hire_date\":\"2026-01-02\",\"attempts\":3,\"amount\":\"12.34\"}", "k1d"));

        assertThat(result.getStatus()).isEqualTo("accepted");
        EisObjectRow row = repository.findCurrent("it_widget", result.getCreated().getId()).orElseThrow();
        assertThat(row.field("hire_date")).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(row.field("attempts")).isEqualTo(3L);
        assertThat(row.field("amount")).isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void replayWithSameKeyReturnsOriginalWithoutSideEffects() {
        EisCommandResult first = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k2"));
        EisCommandResult replay = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k2"));

        assertThat(replay.isReplayed()).isTrue();
        assertThat(replay.getStatus()).isEqualTo("accepted");
        assertThat(replay.getCommandId()).isEqualTo(first.getCommandId());
        assertThat(replay.getCreated()).isNotNull();
        assertThat(replay.getCreated().getId()).isEqualTo(first.getCreated().getId());
        assertThat(replay.getCreated().getObjectType()).isEqualTo("it_widget");
        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM command", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM audit_entry", Long.class)).isEqualTo(1L);
        assertThat(repository.findCurrentAllBy("it_widget", Map.of("name", "alice"))).hasSize(1);
    }

    @Test
    void replayOfRejectedKeepsRejection() {
        runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k3"));
        EisCommandResult rejected = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k4"));
        assertThat(rejected.getStatus()).isEqualTo("rejected");

        EisCommandResult replay = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k4"));

        assertThat(replay.isReplayed()).isTrue();
        assertThat(replay.getStatus()).isEqualTo("rejected");
        assertThat(replay.getCode()).isEqualTo("IT-409-001");
    }

    @Test
    void sameKeyDifferentPayloadConflictsAndKeepsOriginal() {
        EisCommandResult first = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k5"));
        EisCommandResult conflict = runtime.submit(request(CREATE, "{\"name\":\"bob\"}", "k5"));

        assertThat(conflict.getStatus()).isEqualTo("conflict");
        assertThat(conflict.getCommandId()).isEqualTo(first.getCommandId());
        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM command", Long.class)).isEqualTo(1L);
        assertThat(repository.findCurrentAllBy("it_widget", Map.of("name", "bob"))).isEmpty();
    }

    @Test
    void rejectedCommandLeavesNoEventOrAudit() {
        runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k6"));
        EisCommandResult rejected = runtime.submit(request(CREATE, "{\"name\":\"alice\"}", "k7"));

        assertThat(rejected.getStatus()).isEqualTo("rejected");
        EisCommandStore.EisStoredCommand command = commandStore.findCommand(rejected.getCommandId())
                .orElseThrow();
        assertThat(command.getStatus()).isEqualTo("rejected");
        assertThat(command.getRejectionCode()).isEqualTo("IT-409-001");
        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM audit_entry", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM eis_outbox", Long.class)).isEqualTo(1L);
    }

    @Test
    void storedPayloadIsRedacted() {
        EisCommandResult result = runtime.submit(
                request(CREATE, "{\"name\":\"carol\",\"secret\":\"s3cr3t\"}", "k8"));

        String payload = jdbcTemplate().queryForObject("SELECT payload::text FROM command WHERE id = ?",
                String.class, result.getCommandId());
        assertThat(payload).doesNotContain("s3cr3t").doesNotContain("secret");
        assertThat(payload).contains("carol");
    }

    @Test
    void concurrentSameKeyResolvesExactlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<EisCommandResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return runtime.submit(request(CREATE, "{\"name\":\"dave\"}", "k9"));
                }));
            }
            start.countDown();
            List<EisCommandResult> results = new ArrayList<>();
            for (Future<EisCommandResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(results).allSatisfy(result -> assertThat(result.getStatus()).isEqualTo("accepted"));
            assertThat(results.stream().filter(result -> !result.isReplayed())).hasSize(1);
            assertThat(results.stream().filter(EisCommandResult::isReplayed)).hasSize(1);
            assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM command", Long.class)).isEqualTo(1L);
            assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM event", Long.class)).isEqualTo(1L);
            assertThat(chain.verify()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCommandsKeepAuditChainContiguous() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<EisCommandResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return runtime.submit(request(CREATE, "{\"name\":\"user" + index + "\"}",
                            "kc" + index));
                }));
            }
            start.countDown();
            for (Future<EisCommandResult> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).getStatus()).isEqualTo("accepted");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(chain.verify()).isEmpty();
        List<Long> sequences = jdbcTemplate().queryForList(
                "SELECT sequence FROM audit_entry ORDER BY sequence", Long.class);
        assertThat(sequences).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(sequences.get(i)).isEqualTo(i + 1L);
        }
    }

    // ------------------------------------------------------------------
    // 微型测试域（it.widget.*）
    // ------------------------------------------------------------------

    /**
     * 创建处理器：name 必填；产出事件；演示敏感字段脱敏。
     */
    private static final class CreateHandler implements EisCommandHandler {

        @Override
        public Set<String> commandTypes() {
            return Set.of(CREATE);
        }

        @Override
        public EisCommandHandlerResult handle(EisCommandContext context) {
            JsonNode name = context.getPayload().get("name");
            if (name == null || !name.isString() || name.stringValue().isBlank()) {
                throw new EisCommandRejectedException("IT-400-001", "name 必填");
            }
            UUID id = context.newId();
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("name", name.stringValue());
            data.put("active", true);
            data.put("ref", null);
            data.put("meta", null);
            data.put("tags", List.of());
            JsonNode hireDate = context.getPayload().get("hire_date");
            JsonNode attempts = context.getPayload().get("attempts");
            JsonNode amount = context.getPayload().get("amount");
            data.put("hire_date", hireDate == null || hireDate.isNull()
                    ? null : LocalDate.parse(hireDate.stringValue()));
            data.put("attempts", attempts == null || attempts.isNull() ? null : attempts.intValue());
            data.put("amount", amount == null || amount.isNull()
                    ? null : new BigDecimal(amount.stringValue()));
            context.getObjects().create("it_widget", id, data, context.getActor(), context.getNow());
            ObjectNode eventPayload = MAPPER.createObjectNode();
            eventPayload.put("widget_id", id.toString());
            eventPayload.put("name", name.stringValue());
            return new EisCommandHandlerResult(
                    List.of(new EisEventDraft("it.widget.created", List.of(id), eventPayload)),
                    new EisObjectRef("it_widget", id));
        }

        @Override
        public JsonNode storedPayload(JsonNode payload) {
            if (payload != null && payload.has("secret")) {
                ObjectNode copy = (ObjectNode) payload.deepCopy();
                copy.remove("secret");
                return copy;
            }
            return payload;
        }
    }

    /**
     * 更新处理器：按 widget_id 改 name。
     */
    private static final class UpdateHandler implements EisCommandHandler {

        @Override
        public Set<String> commandTypes() {
            return Set.of(UPDATE);
        }

        @Override
        public EisCommandHandlerResult handle(EisCommandContext context) {
            UUID widgetId = UUID.fromString(context.getPayload().get("widget_id").stringValue());
            String name = context.getPayload().get("name").stringValue();
            context.getObjects().update("it_widget", widgetId, Map.of("name", name), context.getActor(),
                    context.getNow());
            return new EisCommandHandlerResult(
                    List.of(new EisEventDraft("it.widget.updated", List.of(widgetId),
                            MAPPER.createObjectNode().put("widget_id", widgetId.toString()))),
                    new EisObjectRef("it_widget", widgetId));
        }
    }

    /**
     * 恒拒绝处理器（测试命令级拒绝码落库）。
     */
    private static final class RejectHandler implements EisCommandHandler {

        @Override
        public Set<String> commandTypes() {
            return Set.of("it.widget.rejectme");
        }

        @Override
        public EisCommandHandlerResult handle(EisCommandContext context) {
            throw new EisCommandRejectedException("IT-409-002", "恒拒绝");
        }
    }

    /**
     * 名称唯一规则（prevent）。
     */
    private static final class NameUniqueRule implements EisRule {

        @Override
        public Set<String> commandTypes() {
            return Set.of(CREATE);
        }

        @Override
        public void enforce(EisCommandContext context) {
            JsonNode name = context.getPayload().get("name");
            if (name != null && name.isString()
                    && !context.getObjects().findCurrentAllBy("it_widget", Map.of("name", name.stringValue()))
                    .isEmpty()) {
                throw new EisCommandRejectedException("IT-409-001",
                        "名称已存在: " + name.stringValue());
            }
        }
    }
}
