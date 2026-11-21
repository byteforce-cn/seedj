package cn.byteforce.seedj.workflow.spi;

/** 定义版本；实例启动时固定，运行中不升级。 */
public record WorkflowDefinitionVersion(int major, int minor) {
    public WorkflowDefinitionVersion {
        if (major < 0 || minor < 0) throw new IllegalArgumentException("version must be >= 0");
    }
}
