package cn.byteforce.seedj.infra.jdbc.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账本表迁移能力：名字、顺序与对迁移执行器的委托（scope 固定为 {@code ai}）。 */
class AiSchemaCapabilityTest {

    private final JdbcMigrationRunner runner = mock(JdbcMigrationRunner.class);

    @Test
    @DisplayName("能力名、迁移 scope 与执行顺序固定")
    void exposesNameScopeAndOrder() {
        AiSchemaCapability capability = new AiSchemaCapability(runner);

        assertThat(capability.getName()).isEqualTo("postgres-ai-ledger");
        assertThat(AiSchemaCapability.MIGRATION_SCOPE).isEqualTo("ai");
        assertThat(capability.getOrder()).isEqualTo(120);
        assertThat(capability.isRequired()).isTrue();
    }

    @Test
    @DisplayName("探针、初始化与校验都委托给同一个 scope 的迁移列表")
    void delegatesToTheRunnerWithAiScope() {
        when(runner.isUpToDate(eq("ai"), anyList())).thenReturn(false);
        AiSchemaCapability capability = new AiSchemaCapability(runner);

        assertThat(capability.isReady()).isFalse();

        capability.initialize();
        verify(runner).migrate(eq("ai"), anyList());

        capability.validate();
        verify(runner).validate(eq("ai"), anyList());
    }
}
