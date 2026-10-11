package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EisObjectType} / {@link EisObjectTypeRegistry} 注册面校验测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisObjectTypeTest {

    @Test
    void buildsTypeWithFieldsAndSensitiveColumns() {
        EisObjectType type = EisObjectType.builder("iam_user_account")
                .table("iam_user_account")
                .field("username", EisFieldKind.TEXT)
                .field("status", EisFieldKind.TEXT)
                .field("built_in", EisFieldKind.BOOLEAN)
                .sensitive("username")
                .build();

        assertThat(type.getObjectType()).isEqualTo("iam_user_account");
        assertThat(type.getTableName()).isEqualTo("iam_user_account");
        assertThat(type.getFields()).hasSize(3);
        assertThat(type.getField("built_in").getKind()).isEqualTo(EisFieldKind.BOOLEAN);
        assertThat(type.getSensitiveFields()).containsExactly("username");
        assertThat(type.getField("missing")).isNull();
    }

    @Test
    void tableDefaultsToObjectType() {
        EisObjectType type = EisObjectType.builder("iam_role")
                .field("key", EisFieldKind.TEXT)
                .build();

        assertThat(type.getTableName()).isEqualTo("iam_role");
    }

    @Test
    void rejectsInvalidIdentifiers() {
        assertThatThrownBy(() -> EisObjectType.builder("Bad-Name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EisObjectType.builder("ok").field("Bad Field", EisFieldKind.TEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EisObjectType.builder("ok").table("1bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateFieldAndUnknownSensitive() {
        assertThatThrownBy(() -> EisObjectType.builder("t")
                .field("a", EisFieldKind.TEXT).field("a", EisFieldKind.TEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复字段");
        assertThatThrownBy(() -> EisObjectType.builder("t")
                .field("a", EisFieldKind.TEXT).sensitive("b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感列未注册");
    }

    @Test
    void rejectsEmptyFieldSet() {
        assertThatThrownBy(() -> EisObjectType.builder("t").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册任何领域字段");
    }

    @Test
    void registryResolvesAndRejectsDuplicates() {
        EisObjectType first = EisObjectType.builder("a").field("x", EisFieldKind.TEXT).build();
        EisObjectType second = EisObjectType.builder("b").field("y", EisFieldKind.UUID).build();
        EisObjectTypeRegistry registry = new EisObjectTypeRegistry(List.of(first, second));

        assertThat(registry.get("a")).isSameAs(first);
        assertThat(registry.require("b")).isSameAs(second);
        assertThat(registry.all()).hasSize(2);
        assertThat(registry.get("missing")).isNull();
        assertThatThrownBy(() -> registry.require("missing")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EisObjectTypeRegistry(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复注册");
        assertThat(new EisObjectTypeRegistry(null).all()).isEmpty();
    }
}
