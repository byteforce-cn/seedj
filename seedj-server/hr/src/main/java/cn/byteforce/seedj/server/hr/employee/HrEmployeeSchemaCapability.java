package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.domain.ObjectSchemaCapability;
import cn.byteforce.seedj.module.ObjectModuleRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HR 员工表的显式迁移：V1 是签入的 literal SQL，不再由当前 schema 在启动时生成。
 *
 * <p>迁移 scope 固定 {@code hr-employee}。V1 冻结后只读：后续加字段一律新增 V2、V3…，
 * 由 {@code seedj-codegen} 生成候选、人工审阅后提交。这里读取 classpath 资源而不是拼接
 * {@code TemporalDdl}，避免「schema 改一次、V1 文本变一次、已有库 checksum 冲突」。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeSchemaCapability extends ObjectSchemaCapability {

    /** 能力名，同时是 {@code seedj_schema_history} 的 scope。 */
    public static final String NAME = "hr-employee";

    /** 冻结的 V1 资源（相对本包）。 */
    static final String V1_RESOURCE = "V1__hr_employee.sql";

    /** 对齐 eis Employee 契约的 V2 资源（相对本包）。 */
    static final String V2_RESOURCE = "V2__hr_employee_constraints.sql";

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     * @param modules 模块注册表
     */
    public HrEmployeeSchemaCapability(JdbcMigrationRunner runner,
                                      ObjectModuleRegistry modules) {
        super(NAME, runner, modules.require("employee").schema(),
                List.of(new Migration(1, "create hr_employee table", v1()),
                        new Migration(2, "align employee constraints with eis", v2())));
    }

    /**
     * 读取冻结的 V1 脚本。
     *
     * @return V1 SQL
     */
    static String v1() {
        return resource(V1_RESOURCE);
    }

    /**
     * 读取对齐 eis 契约的 V2 脚本。
     *
     * @return V2 SQL
     */
    static String v2() {
        return resource(V2_RESOURCE);
    }

    private static String resource(String name) {
        try (InputStream in = HrEmployeeSchemaCapability.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read " + name, error);
        }
    }
}
