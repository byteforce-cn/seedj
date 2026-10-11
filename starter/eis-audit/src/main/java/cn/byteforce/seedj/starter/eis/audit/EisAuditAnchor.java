package cn.byteforce.seedj.starter.eis.audit;

/**
 * 审计锚定 SPI：把链头（序号 + 哈希）导出到独立位置，供"替换整链"场景的外部核验
 * （规范 §5：替换整链需外部锚定方可检测；属部署策略）。
 *
 * <p>v1 提供日志与文件两种实现；锚定动作由运维/定时任务触发（IMS 侧由选主任务周期
 * 导出，见 {@code LANDING-IAM §3.2}），不在每次追加时执行。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisAuditAnchor {

    /**
     * 锚定链头。
     *
     * @param head 链头快照
     */
    void anchor(EisAuditHead head);
}
