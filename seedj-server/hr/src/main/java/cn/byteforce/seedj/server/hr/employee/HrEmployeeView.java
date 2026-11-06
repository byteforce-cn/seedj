package cn.byteforce.seedj.server.hr.employee;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 员工读模型：只包含可读字段，不含写入专用的证件散列。
 *
 * @param id             员工 ID
 * @param version        对象版本
 * @param validFrom      业务生效时间
 * @param validTo        业务失效时间
 * @param employeeNo     工号
 * @param name           姓名
 * @param status         状态
 * @param hireDate       入职日期
 * @param employmentType 雇佣类型
 * @param workLocation   工作地点
 * @param orgUnitId      组织单元
 * @author ByteForce
 * @since 1.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HrEmployeeView(UUID id, long version, Instant validFrom, Instant validTo,
                             String employeeNo, String name, String status, LocalDate hireDate,
                             String employmentType, String workLocation, String orgUnitId) {}
