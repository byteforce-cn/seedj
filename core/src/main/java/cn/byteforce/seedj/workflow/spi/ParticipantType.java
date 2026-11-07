package cn.byteforce.seedj.workflow.spi;

/** 参与者类型；{@link #EXPRESSION} 由 Provider 的参与者解析能力展开。 */
public enum ParticipantType {
    USER,
    ROLE,
    GROUP,
    DEPARTMENT,
    EXPRESSION
}
