package cn.byteforce.seedj.starter.eis.audit.web;

import cn.byteforce.seedj.starter.eis.audit.EisAuditChain;
import cn.byteforce.seedj.starter.eis.audit.EisAuditHead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EisAuditAdminController} 响应形状测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuditAdminControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private EisAuditChain chain;

    private EisAuditAdminController controller;

    @BeforeEach
    void setUp() {
        chain = mock(EisAuditChain.class);
        controller = new EisAuditAdminController(chain);
    }

    @Test
    void verifyReportsValidChain() {
        when(chain.verify()).thenReturn(List.of());
        when(chain.loadEntries()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.verify();

        assertThat(response.getBody()).containsEntry("valid", true).containsEntry("entries", 0);
        assertThat((List<?>) response.getBody().get("errors")).isEmpty();
    }

    @Test
    void verifyReportsViolations() {
        when(chain.verify()).thenReturn(List.of(
                new cn.byteforce.seedj.starter.eis.audit.EisAuditVerifier.EisAuditViolation(
                        "invalid_hash", 2L, "记录内容与哈希不符（可能被篡改）", "a", "b")));
        when(chain.loadEntries()).thenReturn(List.of());

        Map<String, Object> body = controller.verify().getBody();

        assertThat(body).containsEntry("valid", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("type", "invalid_hash")
                .containsEntry("sequence", 2L)
                .containsEntry("expected", "a")
                .containsEntry("actual", "b");
    }

    @Test
    void headAndAnchorReturnHeadBody() {
        when(chain.head()).thenReturn(new EisAuditHead(4L, "d".repeat(64), NOW));
        when(chain.anchorNow()).thenReturn(new EisAuditHead(5L, "e".repeat(64), NOW));

        Map<String, Object> headBody = controller.head().getBody();
        Map<String, Object> anchorBody = controller.anchor().getBody();

        assertThat(headBody).containsEntry("sequence", 4L).containsEntry("hash", "d".repeat(64));
        assertThat(anchorBody).containsEntry("sequence", 5L).containsEntry("hash", "e".repeat(64));
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
