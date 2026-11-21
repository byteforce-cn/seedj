package cn.byteforce.seedj.infra.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.core.Migration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 通用 schema 能力：迁移声明是数据，模板逻辑只此一份。 */
class JdbcSchemaCapabilityTest {

    private static final MigrationSet SET =
            new MigrationSet(
                    "postgres-test", "test", 42, List.of(new Migration(1, "first", "SELECT 1")));

    private final JdbcMigrationRunner runner = mock(JdbcMigrationRunner.class);

    @Test
    void exposesNameScopeOrderAndMigrations() {
        JdbcSchemaCapability capability = new JdbcSchemaCapability(SET, runner);

        assertThat(capability.getName()).isEqualTo("postgres-test");
        assertThat(capability.getOrder()).isEqualTo(42);
        assertThat(capability.migrationSet()).isEqualTo(SET);
        assertThat(capability.migrationSet().scope()).isEqualTo("test");
    }

    @Test
    void delegatesReadinessAndLifecycleToRunnerWithScope() {
        when(runner.isUpToDate("test", SET.migrations())).thenReturn(true);
        JdbcSchemaCapability capability = new JdbcSchemaCapability(SET, runner);

        assertThat(capability.isReady()).isTrue();
        capability.initialize();
        capability.validate();

        verify(runner).migrate("test", SET.migrations());
        verify(runner).validate("test", SET.migrations());
    }

    @Test
    void rejectsInvalidDeclarations() {
        assertThatThrownBy(() -> new MigrationSet(null, "test", 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MigrationSet("name", " ", 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MigrationSet("name", "test", -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacesMigrationsWithoutLosingIdentity() {
        List<Migration> replacement = List.of(new Migration(1, "only v1", "SELECT 1"));

        MigrationSet upgraded = SET.withMigrations(replacement);

        assertThat(upgraded.name()).isEqualTo(SET.name());
        assertThat(upgraded.scope()).isEqualTo(SET.scope());
        assertThat(upgraded.order()).isEqualTo(SET.order());
        assertThat(upgraded.migrations()).isEqualTo(replacement);
    }

    @Test
    void everyPostgresSchemaDeclaresDistinctCapability() {
        List<JdbcSchemaCapability> capabilities =
                List.of(
                        new cn.byteforce.seedj.infra.jdbc.ai.AiSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.audit.AuditSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.cqrs.CommandSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.cqrs.EventSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.iam.IamSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.iam.OAuth2SchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.im.ImSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.lock.PostgresLockSchemaCapability(runner),
                        new cn.byteforce.seedj.infra.jdbc.outbox.PostgresOutboxSchemaCapability(
                                runner),
                        new cn.byteforce.seedj.infra.jdbc.workflow.WorkflowSchemaCapability(
                                runner));

        List<MigrationSet> sets =
                capabilities.stream().map(JdbcSchemaCapability::migrationSet).toList();
        assertThat(sets).extracting(MigrationSet::name).doesNotHaveDuplicates();
        assertThat(sets).extracting(MigrationSet::scope).doesNotHaveDuplicates();
        assertThat(sets).allSatisfy(set -> assertThat(set.migrations()).isNotEmpty());
        assertThat(capabilities).extracting(JdbcSchemaCapability::getName).doesNotHaveDuplicates();
        assertThat(capabilities)
                .allSatisfy(capability -> assertThat(capability.getOrder()).isNotNegative());
    }
}
