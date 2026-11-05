package cn.byteforce.seedj.outbox.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL outbox 存储配置（{@code seedj.outbox.store.*}）。
 */
@ConfigurationProperties(prefix = "seedj.outbox.store")
public class PostgresOutboxProperties {

    /** append 时分区序号冲突的最大重试次数。 */
    private int appendMaxAttempts = 3;

    public int getAppendMaxAttempts() {
        return appendMaxAttempts;
    }

    public void setAppendMaxAttempts(int appendMaxAttempts) {
        this.appendMaxAttempts = appendMaxAttempts;
    }
}
