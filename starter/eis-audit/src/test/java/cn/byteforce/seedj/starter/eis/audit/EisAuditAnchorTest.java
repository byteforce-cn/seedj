package cn.byteforce.seedj.starter.eis.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锚定实现测试（文件锚定落盘格式 / 日志锚定冒烟）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuditAnchorTest {

    @TempDir
    Path tempDir;

    @Test
    void fileAnchorAppendsLinesAndCreatesDirectories() throws Exception {
        Path file = tempDir.resolve("nested/dir/anchor.log");
        FileEisAuditAnchor anchor = new FileEisAuditAnchor(file);

        anchor.anchor(new EisAuditHead(5L, "a".repeat(64), Instant.parse("2026-10-10T09:00:00Z")));
        anchor.anchor(new EisAuditHead(6L, "b".repeat(64), Instant.parse("2026-10-10T09:00:01Z")));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("5\t" + "a".repeat(64) + "\t");
        assertThat(lines.get(1)).startsWith("6\t" + "b".repeat(64) + "\t");
        assertThat(anchor.getFile()).isEqualTo(file);
    }

    @Test
    void loggingAnchorDoesNotThrow() {
        new LoggingEisAuditAnchor()
                .anchor(new EisAuditHead(1L, "c".repeat(64), Instant.parse("2026-10-10T09:00:00Z")));
    }
}
