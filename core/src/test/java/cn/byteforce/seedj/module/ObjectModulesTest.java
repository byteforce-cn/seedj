package cn.byteforce.seedj.module;

import cn.byteforce.seedj.core.security.ScopeKey;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Privacy;
import cn.byteforce.seedj.read.ReadModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static cn.byteforce.seedj.core.security.ScopeKeyType.LIST_STRING;
import static cn.byteforce.seedj.core.security.ScopeKeyType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectModulesTest {

    private static final ScopeKey<List<String>> ORG_UNITS =
            new ScopeKey<>("org.units", LIST_STRING);

    @Test
    void freezeBuildsSchemaAndReadModel() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .contextKey(ORG_UNITS)
                .field("employee_no", FieldType.TEXT).required().filterable().sortable().indexed()
                .field("id_number", FieldType.TEXT).privacy(Privacy.SENSITIVE)
                .query(Map.class).path("/api/employees").permission("hr.employee.read")
                .sort("employee_no");

        ObjectModuleRegistry registry = modules.freeze();

        ReadModel model = registry.find("employee").orElseThrow();
        assertThat(model.readPermission()).isEqualTo("hr.employee.read");
        assertThat(model.schema().fields()).extracting("name")
                .containsExactly("employee_no", "id_number");
        assertThat(model.schema().requireField("employee_no").sortable()).isTrue();
        assertThat(model.schema().requireField("employee_no").indexed()).isTrue();
        assertThat(model.schema().requireField("id_number").privacy())
                .isEqualTo(Privacy.SENSITIVE);
        assertThat(model.schema().requireField("id_number").eventVisible()).isFalse();
        assertThat(registry.all()).hasSize(1);
        assertThat(registry.queries()).hasSize(1);
        assertThat(registry.scopeKeys().keys()).containsEntry("org.units", LIST_STRING);
    }

    @Test
    void writeOnlyFieldStaysOutOfEvents() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT).required()
                .field("secret", FieldType.TEXT).writeOnly();

        var schema = modules.freeze().find("employee").orElseThrow().schema();

        assertThat(schema.writeOnlyFields()).containsExactly("secret");
        assertThat(schema.eventFields()).containsExactly("name");
    }

    @Test
    void missingReadPermissionFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee").field("name", FieldType.TEXT);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readPermission");
    }

    @Test
    void objectWithoutFieldsFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee").readPermission("hr.employee.read");

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field");
    }

    @Test
    void duplicateObjectTypeFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read").field("name", FieldType.TEXT);
        modules.object("employee", "hr_employee_2")
                .readPermission("hr.employee.read").field("name", FieldType.TEXT);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate object type");
    }

    @Test
    void unknownQuerySortFieldFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .query(Map.class).path("/api/employees").sort("missing");

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort");
    }

    @Test
    void querySortFieldMustBeSortable() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT).filterable()
                .query(Map.class).path("/api/employees").sort("name");

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort");
    }

    @Test
    void queryPermissionDefaultsToReadPermission() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .query(Map.class).path("/api/employees");

        assertThat(modules.freeze().queries().get(0).permission())
                .isEqualTo("hr.employee.read");
    }

    @Test
    void queryPathMustBeAbsoluteAndUnique() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .query(Map.class).path("api/employees");

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");

        ObjectModules duplicate = ObjectModules.create();
        duplicate.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .query(Map.class).path("/api/employees");
        duplicate.object("department", "hr_department")
                .readPermission("hr.department.read")
                .field("name", FieldType.TEXT)
                .query(Map.class).path("/api/employees");

        assertThatThrownBy(duplicate::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void duplicateContextKeyWithDifferentTypeFails() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .contextKey(ORG_UNITS)
                .field("name", FieldType.TEXT);
        modules.object("department", "hr_department")
                .readPermission("hr.department.read")
                .contextKey(new ScopeKey<>("org.units", STRING))
                .field("name", FieldType.TEXT);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.units");
    }

    @Test
    void sameContextKeySameTypeIsDeduplicated() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .contextKey(ORG_UNITS)
                .field("name", FieldType.TEXT);
        modules.object("department", "hr_department")
                .readPermission("hr.department.read")
                .contextKey(new ScopeKey<>("org.units", LIST_STRING))
                .field("name", FieldType.TEXT);

        assertThat(modules.freeze().scopeKeys().all()).containsExactly(ORG_UNITS);
    }
}
