package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.jdbc.ObjectSchemaCapability;
import cn.byteforce.seedj.domain.jdbc.TemporalDdl;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;

import java.util.List;

/**
 * HR 员工表的显式迁移：V1 建表，DDL 由 schema 派生。
 *
 * <p>迁移 scope 固定 {@code hr-employee}；后续加字段用 V2 {@code ALTER TABLE ... ADD COLUMN}，
 * 不修改 V1 内容。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeSchemaCapability extends ObjectSchemaCapability {

    /** 能力名，同时是 {@code seedj_schema_history} 的 scope。 */
    public static final String NAME = "hr-employee";

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     * @param schema 员工 schema
     */
    public HrEmployeeSchemaCapability(JdbcMigrationRunner runner, ObjectSchema schema) {
        super(NAME, runner, schema, List.of(new Migration(1, "create hr_employee table",
                TemporalDdl.createTable(TemporalTable.from(schema)))));
    }
}
