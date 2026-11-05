package cn.byteforce.seedj.transit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Transit 配置。
 *
 * <p>默认磁盘持久化：同机多个服务指向同一个 {@code state-file}
 * 就共享同一套密钥，符合"本地内存 + 磁盘、多服务统一可用"的定位。
 */
@ConfigurationProperties(prefix = "seedj.transit")
public class TransitProperties {

    /** 是否启用内置 Transit（关闭后需要业务方自行提供 {@code TransitProvider} bean）。 */
    private boolean enabled = true;

    /** 是否持久化密钥材料。false 时退化为纯内存（仅建议测试使用）。 */
    private boolean persist = true;

    /** 状态文件；多服务共享同一路径即可共用密钥。 */
    private Path stateFile = Path.of(
            System.getProperty("java.io.tmpdir"), "seedj-transit-state.properties");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPersist() {
        return persist;
    }

    public void setPersist(boolean persist) {
        this.persist = persist;
    }

    public Path getStateFile() {
        return stateFile;
    }

    public void setStateFile(Path stateFile) {
        this.stateFile = stateFile;
    }
}
