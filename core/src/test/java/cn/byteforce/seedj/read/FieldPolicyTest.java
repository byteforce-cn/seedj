package cn.byteforce.seedj.read;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字段策略：默认动作、收窄合并与不可变性。
 */
class FieldPolicyTest {

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("employee", "hr_employee")
            .field(FieldDefinition.of("employee_no", FieldType.TEXT))
            .field(new FieldDefinition("phone", FieldType.TEXT, false, false, false, false, false,
                    false, Privacy.SENSITIVE, Maskers.last4()))
            .writeOnly("id_number_hash", FieldType.TEXT)
            .field(new FieldDefinition("notes", FieldType.TEXT, false, false, false, false, false,
                    false, Privacy.RESTRICTED, null))
            .build();

    @Test
    @DisplayName("默认策略：公开字段 FULL，敏感/受限与写入专用 HIDDEN")
    void defaultsBySchema() {
        FieldPolicy policy = FieldPolicy.defaults(SCHEMA);

        assertThat(policy.fields()).containsEntry("employee_no",
                        new FieldAccess(FieldAccess.Action.FULL, null))
                .containsEntry("phone", new FieldAccess(FieldAccess.Action.HIDDEN, null))
                .containsEntry("notes", new FieldAccess(FieldAccess.Action.HIDDEN, null))
                .containsEntry("id_number_hash", new FieldAccess(FieldAccess.Action.HIDDEN, null));
    }

    @Test
    @DisplayName("收窄取更严动作；同动作保留先出现的策略 id")
    void narrowKeepsStricterAction() {
        FieldPolicy full = new FieldPolicy(Map.of("employee_no",
                new FieldAccess(FieldAccess.Action.FULL, "p-full")));
        FieldPolicy hidden = new FieldPolicy(Map.of("employee_no",
                new FieldAccess(FieldAccess.Action.HIDDEN, "p-hide")));

        assertThat(full.narrow(hidden).fields().get("employee_no"))
                .isEqualTo(new FieldAccess(FieldAccess.Action.HIDDEN, "p-hide"));
        assertThat(hidden.narrow(full).fields().get("employee_no"))
                .isEqualTo(new FieldAccess(FieldAccess.Action.HIDDEN, "p-hide"));
        assertThat(full.narrow(full).fields().get("employee_no").policyId()).isEqualTo("p-full");
    }

    @Test
    @DisplayName("收窄保留双方独有的字段")
    void narrowUnionsFields() {
        FieldPolicy left = new FieldPolicy(Map.of("employee_no",
                new FieldAccess(FieldAccess.Action.FULL, null)));
        FieldPolicy right = new FieldPolicy(Map.of("notes",
                new FieldAccess(FieldAccess.Action.MASKED, "p")));

        assertThat(left.narrow(right).fields()).containsOnlyKeys("employee_no", "notes");
    }

    @Test
    @DisplayName("构造期拒绝 null 映射、null 访问结果与不可修改")
    void guardsConstruction() {
        assertThatThrownBy(() -> new FieldPolicy(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldPolicy(Map.of("employee_no", null)))
                .isInstanceOf(NullPointerException.class);

        FieldPolicy policy = new FieldPolicy(Map.of("employee_no",
                new FieldAccess(FieldAccess.Action.FULL, null)));
        assertThatThrownBy(() -> policy.fields().put("x",
                new FieldAccess(FieldAccess.Action.FULL, null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.narrow(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FieldPolicy.defaults(null))
                .isInstanceOf(NullPointerException.class);
    }
}
