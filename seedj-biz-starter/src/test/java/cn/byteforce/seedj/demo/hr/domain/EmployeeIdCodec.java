package cn.byteforce.seedj.demo.hr.domain;

import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.IdCodec;

/**
 * {@link EmployeeId} 的规范文本编解码。
 *
 * <p>全系统只有这一个地方把员工标识变成文本（事件存储的流键）。适配器不得自己写 {@code getId().value().toString()}，否则同一个员工在不同存储里会有不同形态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EmployeeIdCodec implements IdCodec<EmployeeId> {

    @Override
    public String encode(EmployeeId id) {
        if (id == null) {
            throw new CqrsException(CqrsErrorCode.INVALID_ID, "employeeId must not be null");
        }
        return id.value();
    }

    @Override
    public EmployeeId decode(String encoded) {
        try {
            return EmployeeId.of(encoded);
        } catch (IllegalArgumentException e) {
            throw new CqrsException(CqrsErrorCode.INVALID_ID, "not an employee id: " + encoded, e);
        }
    }
}
