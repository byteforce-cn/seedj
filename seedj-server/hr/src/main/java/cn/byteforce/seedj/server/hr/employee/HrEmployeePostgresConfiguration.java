package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
     * @param schema     员工 schema
     * @return 能力
     */
    @Bean
    public InfrastructureCapability hrEmployeeSchemaCapability(
            DataSource dataSource,
            ObjectProvider<JdbcMigrationRunner> runners,
            ObjectSchema schema) {
        return new HrEmployeeSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)), schema);
    }
}
