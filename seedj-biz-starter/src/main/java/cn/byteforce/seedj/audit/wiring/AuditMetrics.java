package cn.byteforce.seedj.audit.wiring;

import java.time.Duration;

/**
 * 审计指标 seam：只量“append 是否成功”，不量事务提交（装饰器看不到提交）。
 */
public interface AuditMetrics {

    void appended(String action, String resourceType, boolean ok, Duration took);

    static AuditMetrics noop() {
        return (action, resourceType, ok, took) -> {
        };
    }
}
