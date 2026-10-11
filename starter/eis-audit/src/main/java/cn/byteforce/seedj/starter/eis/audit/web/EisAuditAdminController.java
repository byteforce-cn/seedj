package cn.byteforce.seedj.starter.eis.audit.web;

import cn.byteforce.seedj.starter.eis.audit.EisAuditChain;
import cn.byteforce.seedj.starter.eis.audit.EisAuditHead;
import cn.byteforce.seedj.starter.eis.audit.EisAuditVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计管理端点（受应用默认安全链保护）：
 * {@code GET /admin/audit/verify}、{@code GET /admin/audit/head}、
 * {@code POST /admin/audit/anchor}。
 *
 * <p>验证响应与 {@code verify.tool --json} 报告同形（valid / entries / errors），
 * 便于运维统一核验口径。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public class EisAuditAdminController {

    private final EisAuditChain chain;

    /**
     * 构造管理端点。
     *
     * @param chain 审计链
     */
    public EisAuditAdminController(EisAuditChain chain) {
        this.chain = chain;
    }

    /**
     * 全链验证。
     *
     * @return {valid, entries, errors}
     */
    @GetMapping("/admin/audit/verify")
    public ResponseEntity<Map<String, Object>> verify() {
        List<EisAuditVerifier.EisAuditViolation> errors = chain.verify();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", errors.isEmpty());
        body.put("entries", chain.loadEntries().size());
        List<Map<String, Object>> errorBodies = new ArrayList<>();
        for (EisAuditVerifier.EisAuditViolation error : errors) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", error.getType());
            item.put("sequence", error.getSequence());
            item.put("message", error.getMessage());
            if (error.getExpected() != null) {
                item.put("expected", error.getExpected());
            }
            if (error.getActual() != null) {
                item.put("actual", error.getActual());
            }
            errorBodies.add(item);
        }
        body.put("errors", errorBodies);
        return ResponseEntity.ok(body);
    }

    /**
     * 链头快照。
     *
     * @return {sequence, hash, anchored_at}
     */
    @GetMapping("/admin/audit/head")
    public ResponseEntity<Map<String, Object>> head() {
        return ResponseEntity.ok(headBody(chain.head()));
    }

    /**
     * 立即锚定链头。
     *
     * @return 已锚定链头
     */
    @PostMapping("/admin/audit/anchor")
    public ResponseEntity<Map<String, Object>> anchor() {
        return ResponseEntity.ok(headBody(chain.anchorNow()));
    }

    private static Map<String, Object> headBody(EisAuditHead head) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sequence", head.getSequence());
        body.put("hash", head.getHash());
        body.put("anchored_at", head.getAnchoredAt());
        return body;
    }
}
