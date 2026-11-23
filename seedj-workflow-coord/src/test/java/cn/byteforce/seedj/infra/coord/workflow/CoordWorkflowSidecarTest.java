package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordWorkflowSidecarTest {

    private final KvClient kv = mock(KvClient.class);
    private final Map<String, byte[]> store = new TreeMap<>();

    private final CoordWorkflowSidecar sidecar = new CoordWorkflowSidecar(kv, "ns");

    @BeforeEach
    void setUp() {
        when(kv.put(any(byte[].class), any(byte[].class)))
                .thenAnswer(
                        invocation -> {
                            store.put(
                                    text(invocation.getArgument(0)),
                                    invocation.getArgument(1));
                            return null;
                        });
        when(kv.range(any(), any(), anyLong(), anyLong(), anyBoolean(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            byte[] start = invocation.getArgument(0);
                            byte[] end = invocation.getArgument(1);
                            List<KvClient.KeyValue> kvs = new ArrayList<>();
                            for (var entry : store.entrySet()) {
                                byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
                                if (Arrays.compareUnsigned(key, start) >= 0
                                        && (end.length == 0
                                                || Arrays.compareUnsigned(key, end) < 0)) {
                                    kvs.add(
                                            new KvClient.KeyValue(
                                                    key, entry.getValue(), 0, 0, 1, 0));
                                }
                            }
                            return new KvClient.RangeResult(kvs, kvs.size(), 0);
                        });
    }

    @Test
    void formEntriesMergeByWriteTime() {
        sidecar.putForm("wf-1", "wf-1:round-1", "a1", Instant.ofEpochMilli(10), Map.of("k", "old"));
        sidecar.putForm(
                "wf-1",
                "wf-1:round-1",
                "a2",
                Instant.ofEpochMilli(20),
                Map.of("k", "new", "extra", "v"));

        assertThat(sidecar.form("wf-1", "wf-1:round-1"))
                .containsEntry("k", "new")
                .containsEntry("extra", "v");
    }

    @Test
    void actionExtrasRoundTripAndCorruptEntriesAreIgnored() {
        sidecar.putAction("wf-1", "a1", Map.of("operator", "u1", "targetStep", "start"));
        assertThat(sidecar.action("wf-1", "a1"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("operator", "u1", "targetStep", "start"));

        store.put("ns:workflow:sidecar:wf-1:action:broken", "{not-json".getBytes(StandardCharsets.UTF_8));
        assertThat(sidecar.action("wf-1", "broken")).isEmpty();
        assertThat(sidecar.actions("wf-1")).containsKey("a1").doesNotContainKey("broken");
    }

    @Test
    void formWriteFailureRejectsTheAction() {
        when(kv.put(any(byte[].class), any(byte[].class)))
                .thenThrow(new CoordException(ErrorCode.UNAVAILABLE, "down"));

        assertThatThrownBy(
                        () ->
                                sidecar.putForm(
                                        "wf-1",
                                        "wf-1:round-1",
                                        "a1",
                                        Instant.now(),
                                        Map.of("k", "v")))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("form data");
    }

    @Test
    void actionExtrasWriteFailureDoesNotBlockTheCommand() {
        when(kv.put(any(byte[].class), any(byte[].class)))
                .thenThrow(new CoordException(ErrorCode.UNAVAILABLE, "down"));

        sidecar.putAction("wf-1", "a1", Map.of("operator", "u1"));
        sidecar.markRemoved("wf-1", "wf-1:round-1", List.of("u1"));
    }

    @Test
    void serverClockOnlyMovesForward() {
        CoordWorkflowSidecar.ServerClock clock = new CoordWorkflowSidecar.ServerClock();
        clock.observe(1_000L);
        clock.observe(500L);
        assertThat(clock.now()).isEqualTo(Instant.ofEpochMilli(1_000));
        clock.observe(Instant.ofEpochMilli(2_000));
        assertThat(clock.now()).isEqualTo(Instant.ofEpochMilli(2_000));
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
