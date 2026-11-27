package cn.byteforce.seedj.infra.coord.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.file.FileErrorCode;
import cn.byteforce.seedj.file.FileException;
import cn.byteforce.seedj.file.FileMetadata;
import cn.byteforce.seedj.file.FileService;
import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** 真集群上的文件语义：v1 对象存储 + KV sidecar，走完整的 agent → server 链路。 */
class CoordFileServiceClusterTest {

    @Test
    void fileRoundTripOnRealV1Cluster() throws Exception {
        try (CoordCluster cluster = CoordCluster.startV1WithObjectStorage()) {
            FileService files = service(cluster);
            byte[] payload = new byte[1024 * 1024];
            new SecureRandom(new byte[] {7}).nextBytes(payload);

            FileMetadata put =
                    files.put(
                            "cluster/file.bin",
                            "application/octet-stream",
                            payload.length,
                            new ByteArrayInputStream(payload));
            assertThat(put.size()).isEqualTo(payload.length);
            assertThat(put.version()).isNotBlank();

            FileMetadata stat = files.stat("cluster/file.bin").orElseThrow();
            assertThat(stat.contentType()).isEqualTo("application/octet-stream");
            assertThat(stat.size()).isEqualTo(payload.length);
            assertThat(files.open("cluster/file.bin").readAllBytes()).isEqualTo(payload);

            assertThatThrownBy(
                            () ->
                                    files.put(
                                            "cluster/file.bin",
                                            "text/plain",
                                            1,
                                            new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOfSatisfying(
                            FileException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(FileErrorCode.ALREADY_EXISTS));

            assertThat(files.delete("cluster/file.bin")).isTrue();
            assertThat(files.stat("cluster/file.bin")).isEmpty();

            // 元数据对象 id 是确定的：删除后必须能重建同一个 key。
            byte[] rebuilt = "rebuilt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            files.put(
                    "cluster/file.bin",
                    "text/plain",
                    rebuilt.length,
                    new ByteArrayInputStream(rebuilt));
            assertThat(files.open("cluster/file.bin").readAllBytes()).isEqualTo(rebuilt);
        }
    }

    private static FileService service(CoordCluster cluster) {
        return new CoordFileService(
                cluster.client().objectStore(),
                cluster.agentHost() + ":" + cluster.agentPort(),
                "seedj:cluster-test",
                "seedj-files",
                8 * 1024 * 1024,
                Clock.systemUTC());
    }
}
