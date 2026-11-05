package cn.byteforce.seedj.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 标识生成配置。
 *
 * <p>多服务部署：不配置 {@code node-id} 时，各实例从共享文件
 * {@code node-state-file} 原子领取互不相同的节点编号。
 */
@ConfigurationProperties(prefix = "seedj.id")
public class IdProperties {

    /** 是否启用内置标识生成器。 */
    private boolean enabled = true;

    /** 雪花节点编号 0..1023；不配置则从共享文件自动分配。 */
    private Integer nodeId;

    /** 雪花起始时间（毫秒）。默认 2024-01-01T00:00:00Z。 */
    private long epochMillis = SnowflakeIdGenerator.DEFAULT_EPOCH_MILLIS;

    /** 节点编号分配文件；同机多服务共享同一路径。 */
    private Path nodeStateFile = Path.of(
            System.getProperty("java.io.tmpdir"), "seedj-id-nodes.properties");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getNodeId() {
        return nodeId;
    }

    public void setNodeId(Integer nodeId) {
        this.nodeId = nodeId;
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public void setEpochMillis(long epochMillis) {
        this.epochMillis = epochMillis;
    }

    public Path getNodeStateFile() {
        return nodeStateFile;
    }

    public void setNodeStateFile(Path nodeStateFile) {
        this.nodeStateFile = nodeStateFile;
    }
}
