package cn.byteforce.seedj.infra.jdbc;

import cn.byteforce.seedj.core.Migration;
import java.util.List;
import java.util.Objects;

/**
 * 一个能力的 schema 迁移声明：能力名、迁移 scope、执行顺序与迁移列表。
 *
 * <p>迁移是数据，不是类：新增能力只需要声明一份 {@code MigrationSet}，由 {@link JdbcSchemaCapability}
 * 统一接入启动期能力链。已应用的迁移不得修改，只追加新版本。
 *
 * @param name 能力名（{@code InfrastructureCapability#getName()}）
 * @param scope 迁移 scope（{@code seedj_schema_history} 的分组键）
 * @param order 启动期执行顺序（越小越先）
 * @param migrations 按版本升序的迁移
 * @author ByteForce
 * @since 1.0.0
 */
public record MigrationSet(String name, String scope, int order, List<Migration> migrations) {

    public MigrationSet {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("capability name is required");
        }
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("migration scope is required");
        }
        if (order < 0) {
            throw new IllegalArgumentException("order must be >= 0");
        }
        migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
    }

    /**
     * 用另一组迁移替换本声明（用于升级/回滚测试）。
     *
     * @param migrations 替换后的迁移
     * @return 新的声明
     */
    public MigrationSet withMigrations(List<Migration> migrations) {
        return new MigrationSet(name, scope, order, migrations);
    }
}
