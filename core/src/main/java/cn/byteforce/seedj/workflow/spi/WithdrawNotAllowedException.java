package cn.byteforce.seedj.workflow.spi;

/** 撤回权限不足或下游已完成。 */
public class WithdrawNotAllowedException extends WorkflowException {
    public WithdrawNotAllowedException(String message) { super(message); }
}
