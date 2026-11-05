package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.audit.AuditErrorCode;
import cn.byteforce.seedj.audit.AuditException;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.Repository;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventApplier;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.lock.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令审计 seam 的位置契约：accepted 在事务内且只在成功路径上调用；
 * rejected 只在业务拒绝时调用，且在事务外。
 */
class CommandAgentAuditTest {

    private final List<String> trace = new ArrayList<>();

    private final RecordingAudit audit = new RecordingAudit(trace);

    private final TransactionRunner transactionRunner = new TransactionRunner() {
        @Override
        public <R> R inTransaction(java.util.function.Supplier<R> work) {
            trace.add("tx:begin");
            try {
                return work.get();
            } finally {
                trace.add("tx:end");
            }
        }
    };

    private TestAgent agent(CommandAudit commandAudit) {
        return new TestAgent(new InMemoryRepository(trace), new NoopPublisher(trace),
                commandAudit, transactionRunner, trace);
    }

    @Test
    @DisplayName("accepted 在事务内、save 与 publish 之后调用")
    void acceptedRunsLastInsideTransaction() {
        RecordingAudit txAware = new RecordingAudit(trace) {
            @Override
            public void accepted(Command<?> command, String resourceId) {
                super.accepted(command, resourceId);
                assertThat(trace).contains("tx:begin").doesNotContain("tx:end");
            }
        };

        agent(txAware).execute(new Cmd("a-1", Kind.OK));

        assertThat(trace).containsExactly(
                "tx:begin", "load:a-1", "save", "publish", "audit:accepted:a-1", "tx:end");
    }

    @Test
    @DisplayName("命令抛技术异常时不写 accepted，也不写 rejected")
    void technicalFailureIsNotAudited() {
        assertThatThrownBy(() -> agent(audit).execute(new Cmd("a-2", Kind.BOOM)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(audit.calls).isEmpty();
    }

    @Test
    @DisplayName("业务拒绝走 rejected（事务外），不写 accepted")
    void businessRejectionIsAuditedOutsideTransaction() {
        assertThatThrownBy(() -> agent(audit).execute(new Cmd("a-3", Kind.REJECT)))
                .isInstanceOf(CqrsException.class);

        assertThat(audit.calls).containsExactly("rejected:a-3");
        assertThat(audit.rejectedInsideTransaction).isFalse();
    }

    @Test
    @DisplayName("审计失败原样上抛，且不会被误记成业务拒绝")
    void auditFailureIsNotRecordedAsRejection() {
        CommandAudit failing = new CommandAudit() {
            @Override
            public void accepted(Command<?> command, String resourceId) {
                throw new AuditException(AuditErrorCode.APPEND_FAILED, "audit store down");
            }
        };

        assertThatThrownBy(() -> agent(failing).execute(new Cmd("a-4", Kind.OK)))
                .isInstanceOf(AuditException.class);
    }

    // ---------------------------------------------------------------- fixtures

    enum Kind { OK, REJECT, BOOM }

    record Cmd(String counterId, Kind kind) implements Command<String> {
        @Override
        public String aggregateId() {
            return counterId;
        }
    }

    record Touched(String counterId) implements DomainEvent {
    }

    static final class Counter extends AbstractAggregateRoot<String>
            implements EventApplier<Touched> {

        private Counter(String id) {
            super(id);
        }

        static Counter load(String id) {
            return new Counter(id);
        }

        void touch() {
            raise(new Touched(getId()));
        }

        protected void apply(Touched event) {
            // 状态无关紧要：这里只验证事件被发布
        }
    }

    static final class TestAgent extends CommandAgent<Cmd, Counter, String> {

        TestAgent(Repository<Counter, String> repository, EventPublisher publisher,
                  CommandAudit audit, TransactionRunner runner, List<String> trace) {
            super(repository, publisher, IdCodecs.strings(),
                    new Options<>(runner, LockProvider.disabled(), Duration.ofSeconds(5),
                            1, List.of(), audit));
        }

        @Override
        protected String aggregateType() {
            return "test.counter";
        }

        @Override
        protected void handle(Counter aggregate, Cmd command) {
            switch (command.kind()) {
                case REJECT -> throw new CqrsException(CqrsErrorCode.COMMAND_REJECTED,
                        "already onboarded");
                case BOOM -> throw new IllegalStateException("boom");
                case OK -> aggregate.touch();
            }
        }
    }

    static final class InMemoryRepository implements Repository<Counter, String> {

        private final List<String> trace;

        InMemoryRepository(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public Optional<Counter> load(String id) {
            trace.add("load:" + id);
            return Optional.of(Counter.load(id));
        }

        @Override
        public void save(Counter aggregate) {
            trace.add("save");
        }
    }

    static final class NoopPublisher implements EventPublisher {

        private final List<String> trace;

        NoopPublisher(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void publish(DomainEvent event) {
            trace.add("publish");
        }

        @Override
        public void publish(EventMessage message) {
            trace.add("publish");
        }
    }

    static class RecordingAudit implements CommandAudit {

        final List<String> calls = new ArrayList<>();

        private final List<String> trace;

        boolean rejectedInsideTransaction;

        RecordingAudit(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void accepted(Command<?> command, String resourceId) {
            calls.add("accepted:" + resourceId);
            trace.add("audit:accepted:" + resourceId);
        }

        @Override
        public void rejected(Command<?> command, String resourceId, RuntimeException error) {
            calls.add("rejected:" + resourceId);
            rejectedInsideTransaction = !trace.contains("tx:end");
        }
    }
}
