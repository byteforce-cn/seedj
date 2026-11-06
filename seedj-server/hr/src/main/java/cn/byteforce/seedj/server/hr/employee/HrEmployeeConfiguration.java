package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.security.DerivedField;
import cn.byteforce.seedj.core.security.DerivedFieldKeyRing;
import cn.byteforce.seedj.cqrs.CommandPayloadRedactor;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HR 员工活样板装配：对象表、派生字段、脱敏与两个命令代理。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HrEmployeeConfiguration {

    /**
     * 员工对象表；证件散列为写入专用列。
     *
     * @return 对象表
     */
    @Bean
    public TemporalTable hrEmployeeTable() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("employee_no", "TEXT");
        columns.put("name", "TEXT");
        columns.put("status", "TEXT");
        columns.put("hire_date", "TEXT");
        columns.put("employment_type", "TEXT");
        columns.put("work_location", "TEXT");
        columns.put("id_number_hash", "TEXT");
        columns.put("org_unit_id", "TEXT");
        return new TemporalTable("employee", "hr_employee", columns,
                Set.of("id_number_hash"));
    }

    /**
     * 证件散列派生；生产必须注入 {@code SEEDJ_FIELD_HASH_KEYS}。
     *
     * @return 派生字段
     */
    @Bean
    public DerivedField hrIdNumberHash() {
        return new DerivedField("seedj:hr:employee:id_number_hash:v1",
                DerivedFieldKeyRing.fromEnvironment());
    }

    /**
     * 命令记录脱敏：原始证件号与派生散列都不落命令记录。
     *
     * @return 脱敏器
     */
    @Bean
    public CommandPayloadRedactor hrPayloadRedactor() {
        return payload -> {
            Map<String, Object> redacted = new LinkedHashMap<>(payload);
            redacted.remove("id_number");
            redacted.remove("id_number_hash");
            return redacted;
        };
    }

    /**
     * 建档代理。
     *
     * @param runtime 命令运行时
     * @param derived 派生字段
     * @return 代理
     */
    @Bean
    public HrEmployeeCreateAgent hrEmployeeCreateAgent(CommandRuntime runtime,
                                                       DerivedField derived,
                                                       CommandPayloadRedactor redactor) {
        return new HrEmployeeCreateAgent(runtime, derived, new AtomicLong(), redactor);
    }

    /**
     * 更新代理。
     *
     * @param runtime 命令运行时
     * @param derived 派生字段
     * @return 代理
     */
    @Bean
    public HrEmployeeUpdateAgent hrEmployeeUpdateAgent(CommandRuntime runtime,
                                                       DerivedField derived,
                                                       CommandPayloadRedactor redactor) {
        return new HrEmployeeUpdateAgent(runtime, derived, redactor);
    }
}
