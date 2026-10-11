package cn.byteforce.seedj.starter.eis.it;

import cn.byteforce.seedj.starter.eis.audit.EisAuditAnchor;
import cn.byteforce.seedj.starter.eis.audit.EisAuditChain;
import cn.byteforce.seedj.starter.eis.audit.EisAuditHead;
import cn.byteforce.seedj.starter.eis.audit.FileEisAuditAnchor;
import cn.byteforce.seedj.starter.eis.core.EisAuditAppender;
import cn.byteforce.seedj.starter.eis.core.EisClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计链真库集成测试：串行分配连续性、并发写入、事务回滚不留缺口、锚定落盘。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuditChainIntegrationTest extends AbstractEisIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID SUBJECT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private EisAuditChain chain;

    @BeforeEach
    void setUpChain() {
        chain = new EisAuditChain(jdbcTemplate(), EisClock.monotonic(EisClock.system()),
                new EisAuditAnchor() {
                    @Override
                    public void anchor(EisAuditHead head) {
                        // 记录型锚定（测试无副作用）
                    }
                });
    }

    private void append(String suffix) {
        transactionTemplate().executeWithoutResult(status -> chain.append(
                new EisAuditAppender.EisAuditAppendRequest("it.widget.create", ACTOR, "it", SUBJECT,
                        MAPPER.readTree("{\"n\":\"" + suffix + "\"}"))));
    }

    @Test
    void sequentialAppendsAreContiguousAndHeadMatchesLastHash() {
        append("a");
        append("b");
        append("c");

        assertThat(chain.verify()).isEmpty();
        List<Long> sequences = jdbcTemplate().queryForList(
                "SELECT sequence FROM audit_entry ORDER BY sequence", Long.class);
        assertThat(sequences).containsExactly(1L, 2L, 3L);
        EisAuditHead head = chain.head();
        assertThat(head.getSequence()).isEqualTo(3L);
        assertThat(head.getHash()).isEqualTo(chain.loadEntries().get(2).getHash());
    }

    @Test
    void rolledBackAppendLeavesNoGap() {
        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status -> {
            chain.append(new EisAuditAppender.EisAuditAppendRequest("it.widget.create", ACTOR, "it", SUBJECT,
                    MAPPER.readTree("{\"n\":\"x\"}")));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        append("y");

        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM audit_entry", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate().queryForObject("SELECT sequence FROM audit_entry", Long.class)).isEqualTo(1L);
        assertThat(chain.head().getSequence()).isEqualTo(1L);
        assertThat(chain.verify()).isEmpty();
    }

    @Test
    void concurrentAppendsRemainSerial() throws Exception {
        int threads = 8;
        int perThread = 3;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        append(index + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate().queryForObject("SELECT count(*) FROM audit_entry", Long.class))
                .isEqualTo((long) threads * perThread);
        assertThat(chain.verify()).isEmpty();
        List<Long> sequences = jdbcTemplate().queryForList(
                "SELECT sequence FROM audit_entry ORDER BY sequence", Long.class);
        for (int i = 0; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isEqualTo(i + 1L);
        }
        assertThat(chain.head().getSequence()).isEqualTo(sequences.size());
    }

    @Test
    void fileAnchorWritesHeadLine(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("audit/anchor.log");
        EisAuditChain fileChain = new EisAuditChain(jdbcTemplate(), EisClock.monotonic(EisClock.system()),
                new FileEisAuditAnchor(file));
        append("anchored");

        EisAuditHead head = fileChain.anchorNow();

        assertThat(head.getSequence()).isEqualTo(1L);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readAllLines(file)).hasSize(1);
        assertThat(Files.readAllLines(file).get(0)).startsWith("1\t");
    }
}
