package cn.byteforce.seedj.starter.eis.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志锚定：把链头写入应用日志（运维侧随日志归档留痕）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class LoggingEisAuditAnchor implements EisAuditAnchor {

    private static final Logger log = LoggerFactory.getLogger(LoggingEisAuditAnchor.class);

    @Override
    public void anchor(EisAuditHead head) {
        log.info("EIS 审计链锚点: sequence={}, hash={}, anchored_at={}",
                head.getSequence(), head.getHash(), head.getAnchoredAt());
    }
}
