package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL profile：注册员工表迁移能力。
 *
 * <p>只在 {@code postgres} profile 生效；local 模式没有 DataSource，也不会尝试建表。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class HrEmployeePostgresConfiguration {

    /**
     * 员工表迁移能力。
     *
     * @param dataSource 数据源
     * @param runners    迁移执行器
     * @param modules    模块注册表
     * @return 能力
     */
    /**
     * PostgreSQL 工号序列：与 V2 创建的 {@code hr_employee_no_seq} 对齐。
     *
     * @param jdbc JDBC 模板
     * @return 序列
     */
    @Bean
    public EmployeeNoSequence hrEmployeeNoSequence(JdbcTemplate jdbc) {
        return () -> {
            Long value = jdbc.queryForObject("select nextval('hr_employee_no_seq')",
                    Long.class);
            if (value == null) {
                throw new IllegalStateException("hr_employee_no_seq returned no value");
            }
            return value;
        };
    }

    @Bean
    public InfrastructureCapability hrEmployeeSchemaCapability(
            DataSource dataSource,
            ObjectProvider<JdbcMigrationRunner> runners,
            ObjectModuleRegistry modules) {
        return new HrEmployeeSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)), modules);
    }
}
