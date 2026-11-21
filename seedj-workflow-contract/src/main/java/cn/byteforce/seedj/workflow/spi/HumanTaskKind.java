package cn.byteforce.seedj.workflow.spi;

/** 任务来源：原始步骤任务、前加签派生、后加签派生。 */
public enum HumanTaskKind {
    ORIGINAL,
    BEFORE_ADDED,
    AFTER_ADDED
}
