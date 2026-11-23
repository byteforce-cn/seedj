package cn.byteforce.seedj.infra.coord.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code seedj.workflow.coord.*} 配置。
 *
 * <p>连接参数复用 {@code seedj.coord.*}（agent 地址、TLS、token、namespace）；这里只放适配器自己的 语义开关。
 */
@ConfigurationProperties(prefix = "seedj.workflow.coord")
public class CoordWorkflowProperties {

    /**
     * 服务主体：编译进每个 {@code approve.signAdmins}，加签/减签/终止以它执行。
     *
     * <p>必须与 agent 侧 {@code services.workflow_on_behalf_services} 中的 seedj 服务主体一致；
     * 为空时启动失败（没有它管理动作会被 coord 拒绝）。
     */
    private String signAdmin;

    /** 内嵌定义 JSON 的上限（字节）：防止超大步长 / metadata 放大 coord 定义实体。 */
    private int maxDefinitionBytes = 64 * 1024;

    public String getSignAdmin() {
        return signAdmin;
    }

    public void setSignAdmin(String signAdmin) {
        this.signAdmin = signAdmin;
    }

    public int getMaxDefinitionBytes() {
        return maxDefinitionBytes;
    }

    public void setMaxDefinitionBytes(int maxDefinitionBytes) {
        this.maxDefinitionBytes = maxDefinitionBytes;
    }

    /** 启动期校验；消息点名属性与 agent 白名单，避免运行期才暴露 403。 */
    void validate() {
        if (signAdmin == null || signAdmin.isBlank()) {
            throw new IllegalStateException(
                    "seedj.workflow.coord.sign-admin is required: it is compiled into every"
                            + " approve.signAdmins and authorizes add-sign/remove-sign/terminate."
                            + " Set it to the seedj service principal listed in the coord agent's"
                            + " services.workflow_on_behalf_services.");
        }
        if (maxDefinitionBytes < 1024) {
            throw new IllegalStateException(
                    "seedj.workflow.coord.max-definition-bytes must be >= 1024");
        }
    }
}
