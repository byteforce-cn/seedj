package cn.byteforce.seedj.infra.jdbc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectSchemaCapabilityTest {

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("order", "order_object").field("status", FieldType.TEXT).build();

    private static final List<Migration> MIGRATIONS =
            List.of(new Migration(1, "create order_object", "CREATE TABLE order_object (id TEXT)"));

    private final JdbcMigrationRunner runner = mock(JdbcMigrationRunner.class);

    @Test
    void exposesNameSchemaAndInfrastructureOrder() {
        ObjectSchemaCapability capability =
                new ObjectSchemaCapability("order-schema", runner, SCHEMA, MIGRATIONS);

        assertThat(capability.getName()).isEqualTo("order-schema");
        assertThat(capability.schema()).isSameAs(SCHEMA);
        assertThat(capability.getOrder()).isEqualTo(200);
        assertThat(capability.isRequired()).isTrue();
    }

    @Test
    void isReadyDelegatesToTheRunnerWithItsOwnScope() {
        when(runner.isUpToDate("order-schema", MIGRATIONS)).thenReturn(true, false);
        ObjectSchemaCapability capability =
                new ObjectSchemaCapability("order-schema", runner, SCHEMA, MIGRATIONS);

        assertThat(capability.isReady()).isTrue();
        assertThat(capability.isReady()).isFalse();
        verify(runner, times(2)).isUpToDate("order-schema", MIGRATIONS);
    }

    @Test
    void initializeDelegatesToMigrate() {
        ObjectSchemaCapability capability =
                new ObjectSchemaCapability("order-schema", runner, SCHEMA, MIGRATIONS);

        capability.initialize();

        verify(runner).migrate("order-schema", MIGRATIONS);
    }

    @Test
    void validateDelegatesToTheRunnerValidation() {
        ObjectSchemaCapability capability =
                new ObjectSchemaCapability("order-schema", runner, SCHEMA, MIGRATIONS);

        capability.validate();

        verify(runner).validate("order-schema", MIGRATIONS);
    }

    @Test
    void constructorRejectsMissingName() {
        assertThatThrownBy(() -> new ObjectSchemaCapability(null, runner, SCHEMA, MIGRATIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability name is required");
        assertThatThrownBy(() -> new ObjectSchemaCapability("  ", runner, SCHEMA, MIGRATIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability name is required");
        verifyNoInteractions(runner);
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThatThrownBy(
                        () -> new ObjectSchemaCapability("order-schema", null, SCHEMA, MIGRATIONS))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runner");
        assertThatThrownBy(
                        () -> new ObjectSchemaCapability("order-schema", runner, null, MIGRATIONS))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> new ObjectSchemaCapability("order-schema", runner, SCHEMA, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("migrations");
    }
}
