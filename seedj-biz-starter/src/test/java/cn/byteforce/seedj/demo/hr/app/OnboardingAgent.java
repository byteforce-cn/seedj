package cn.byteforce.seedj.demo.hr.app;

import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.IdCodec;
import cn.byteforce.seedj.demo.hr.domain.Employee;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;
import cn.byteforce.seedj.demo.hr.domain.EmployeeRepository;
import cn.byteforce.seedj.eventbus.EventPublisher;

/**
 * 入职命令代理：只表达"新建怎么做、已存在怎么拒绝"，顺序保证全在
 * {@link CommandAgent} 里。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class OnboardingAgent
        extends CommandAgent<OnboardEmployeeCommand, Employee, EmployeeId> {

    public OnboardingAgent(EmployeeRepository employees, EventPublisher publisher,
                           IdCodec<EmployeeId> codec, Options<EmployeeId> options) {
        super(employees, publisher, codec, options);
    }

    @Override
    protected String aggregateType() {
        return Employee.STREAM_TYPE;
    }

    @Override
    protected boolean createsOnEmpty() {
        return true;
    }

    @Override
    protected Employee create(EmployeeId id, OnboardEmployeeCommand command) {
        return Employee.onboard(id, command.employeeNo(), command.name(),
                command.department(), command.position(), command.hireDate());
    }

    @Override
    protected void handle(Employee employee, OnboardEmployeeCommand command) {
        throw new CqrsException(CqrsErrorCode.COMMAND_REJECTED,
                "employee already onboarded: " + employee.getId());
    }
}
