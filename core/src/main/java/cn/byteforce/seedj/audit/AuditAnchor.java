package cn.byteforce.seedj.audit;

/**
 * 审计锚定 SPI：把链头（序号 + 哈希）导出到独立位置，供“替换整链”场景的外部核验 （规范 §5：替换整链需外部锚定方可检测；属部署策略）。
 *
 * <p>锚定动作由运维/定时任务触发，不在每次追加时执行。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface AuditAnchor {

    /**
     * 锚定链头。
     *
     * @param head 链头快照
     */
    void anchor(AuditHead head);
}
