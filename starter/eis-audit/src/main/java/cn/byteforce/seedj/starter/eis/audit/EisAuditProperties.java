package cn.byteforce.seedj.starter.eis.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EIS 审计配置属性。
 *
 * <p>前缀：{@code byteforce.eis.audit}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.eis.audit")
public class EisAuditProperties {

    /**
     * 是否启用审计自动配置。
     */
    private boolean enabled = true;

    /**
     * 锚定文件路径（为空 = 日志锚定；设置后启用文件锚定）。
     */
    private String anchorFile;

    /**
     * 获取是否启用。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取锚定文件路径。
     *
     * @return 锚定文件路径；未配置为 {@code null}
     */
    public String getAnchorFile() {
        return anchorFile;
    }

    /**
     * 设置锚定文件路径。
     *
     * @param anchorFile 锚定文件路径
     */
    public void setAnchorFile(String anchorFile) {
        this.anchorFile = anchorFile;
    }
}
