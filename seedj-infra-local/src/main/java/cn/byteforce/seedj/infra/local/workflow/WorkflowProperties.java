package cn.byteforce.seedj.infra.local.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code seedj.workflow.*} 配置。 */
@ConfigurationProperties(prefix = "seedj.workflow")
public class WorkflowProperties {

    /** 是否启用工作流能力。 */
    private boolean enabled = true;

    /** Provider 选择；内置实现为 {@code memory}。 */
    private String provider = "memory";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
