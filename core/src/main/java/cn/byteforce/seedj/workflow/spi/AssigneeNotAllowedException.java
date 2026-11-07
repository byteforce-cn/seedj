package cn.byteforce.seedj.workflow.spi;

/** 审批人越权、催办人不在参与者集合、加签位置不被规格允许。 */
public class AssigneeNotAllowedException extends WorkflowException {
    public AssigneeNotAllowedException(String message) {
        super(message);
    }
}
