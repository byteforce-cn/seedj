package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldDefinitionTest {

    @Test
    void defaultsArePublicAndNotIndexed() {
        FieldDefinition field = FieldDefinition.of("name", FieldType.TEXT);

        assertThat(field.privacy()).isEqualTo(Privacy.PUBLIC);
        assertThat(field.sortable()).isFalse();
        assertThat(field.indexed()).isFalse();
        assertThat(field.masker()).isNull();
        assertThat(field.eventVisible()).isTrue();
    }

    @Test
    void withersKeepOtherComponents() {
        FieldDefinition field =
                FieldDefinition.of("phone", FieldType.TEXT)
                        .withRequired()
                        .withFilterable()
                        .withSortable()
                        .withIndexed()
                        .withPrivacy(Privacy.INTERNAL)
                        .withMasker(Maskers.last4());

        assertThat(field.required()).isTrue();
        assertThat(field.filterable()).isTrue();
        assertThat(field.sortable()).isTrue();
        assertThat(field.indexed()).isTrue();
        assertThat(field.privacy()).isEqualTo(Privacy.INTERNAL);
        assertThat(field.name()).isEqualTo("phone");
    }

    @Test
    void sensitiveFieldCannotBeEventVisible() {
        assertThatThrownBy(
                        () ->
                                new FieldDefinition(
                                        "id_number",
                                        FieldType.TEXT,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false,
                                        Privacy.SENSITIVE,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVisible");
    }

    @Test
    void restrictedFieldCannotBeEventVisible() {
        assertThatThrownBy(
                        () ->
                                new FieldDefinition(
                                        "salary",
                                        FieldType.DECIMAL,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false,
                                        Privacy.RESTRICTED,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVisible");
    }

    @Test
    void internalFieldMayBeEventVisible() {
        FieldDefinition field =
                new FieldDefinition(
                        "department",
                        FieldType.TEXT,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        Privacy.INTERNAL,
                        null);

        assertThat(field.eventVisible()).isTrue();
    }

    @Test
    void maskersLastFourKeepsSuffix() {
        assertThat(Maskers.last4().mask("id_number", "110101199001011234")).isEqualTo("****1234");
        assertThat(Maskers.last4().mask("id_number", "1234")).isEqualTo("****");
        assertThat(Maskers.last4().mask("id_number", null)).isNull();
    }

    @Test
    void maskersEmailKeepsFirstCharacters() {
        assertThat(Maskers.email().mask("email", "alice@example.com"))
                .isEqualTo("a****@e**********");
        assertThat(Maskers.email().mask("email", "broken")).isEqualTo("****");
        assertThat(Maskers.email().mask("email", null)).isNull();
    }

    @Test
    void maskersFixedReplacesValue() {
        assertThat(Maskers.fixed("***").mask("salary", "123")).isEqualTo("***");
        assertThat(Maskers.fixed("***").mask("salary", null)).isNull();
    }

    @Test
    @DisplayName("filterable 工厂与 privacy 缺省归一为 PUBLIC")
    void filterableFactoryAndDefaultPrivacy() {
        FieldDefinition filterable = FieldDefinition.filterable("org_unit_id", FieldType.TEXT);
        FieldDefinition withoutPrivacy =
                new FieldDefinition(
                        "status",
                        FieldType.TEXT,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        null,
                        null);

        assertThat(filterable.filterable()).isTrue();
        assertThat(filterable.eventVisible()).isTrue();
        assertThat(withoutPrivacy.privacy()).isEqualTo(Privacy.PUBLIC);
    }
}
