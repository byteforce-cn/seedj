package cn.byteforce.seedj.iam.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SCIM PATCH 模型：请求体包装、操作校验与多值路径解析。 */
class ScimPatchModelTest {

    @Test
    @DisplayName("PATCH body 支持 {Operations:[...]} 包装与裸数组")
    void parsesWrappedAndBareBodies() {
        List<ScimPatchOperation> wrapped =
                ScimPatchParser.parse(
                        Map.of(
                                "Operations",
                                List.of(
                                        Map.of(
                                                "op",
                                                "add",
                                                "path",
                                                "displayName",
                                                "value",
                                                "Alice"))));
        List<ScimPatchOperation> bare =
                ScimPatchParser.parse(
                        List.of(Map.of("op", "replace", "path", "active", "value", true)));

        assertThat(wrapped).hasSize(1);
        assertThat(wrapped.get(0).op()).isEqualTo("add");
        assertThat(wrapped.get(0).path()).isEqualTo("displayName");
        assertThat(wrapped.get(0).value()).isEqualTo("Alice");
        assertThat(bare).hasSize(1);
        assertThat(bare.get(0).value()).isEqualTo(true);
    }

    @Test
    @DisplayName("非数组 body 或非对象 operation 报 SCIM_PATCH_INVALID")
    void rejectsMalformedBodies() {
        assertThatThrownBy(() -> ScimPatchParser.parse("not-a-list"))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.SCIM_PATCH_INVALID);
        assertThatThrownBy(() -> ScimPatchParser.parse(Map.of("Operations", "not-a-list")))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("Operations");
        assertThatThrownBy(() -> ScimPatchParser.parse(List.of("not-a-map")))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("operation must be an object");
    }

    @Test
    @DisplayName("缺少 op 与非法 op 均被拒绝")
    void rejectsMissingOrUnsupportedOp() {
        assertThatThrownBy(() -> ScimPatchParser.parse(List.of(Map.of("path", "active"))))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("missing op");
        assertThatThrownBy(
                        () ->
                                ScimPatchParser.parse(
                                        List.of(Map.of("op", "delete", "path", "active"))))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("unsupported PATCH op");
    }

    @Test
    @DisplayName("op 大小写归一化，空白 path 归一化为 null")
    void normalizesOpAndPath() {
        ScimPatchOperation operation = new ScimPatchOperation("REPLACE", "  ", Boolean.TRUE);

        assertThat(operation.op()).isEqualTo("replace");
        assertThat(operation.path()).isNull();
        assertThat(operation.value()).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("remove 必须有 path，add/replace 必须有 value")
    void enforcesRemoveAndValueRules() {
        assertThatThrownBy(() -> new ScimPatchOperation("remove", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remove requires a path");
        assertThatThrownBy(() -> new ScimPatchOperation("add", "displayName", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add requires a value");
        assertThatThrownBy(() -> new ScimPatchOperation(null, "displayName", "Alice"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("op");

        assertThat(new ScimPatchOperation("remove", "members", null).path()).isEqualTo("members");
    }

    @Test
    @DisplayName("ScimPatchPath：简单属性、子属性与多值过滤器")
    void patchPathForms() {
        assertThat(ScimPatchPath.parse(null)).isNull();
        assertThat(ScimPatchPath.parse(" ")).isNull();

        ScimPatchPath simple = ScimPatchPath.parse(" active ");
        assertThat(simple.attribute()).isEqualTo("active");
        assertThat(simple.valueFilter()).isNull();

        ScimPatchPath nested = ScimPatchPath.parse("name.givenName");
        assertThat(nested.attribute()).isEqualTo("name.givenName");

        ScimPatchPath filtered = ScimPatchPath.parse("members[value eq \"u-1\"]");
        assertThat(filtered.attribute()).isEqualTo("members");
        assertThat(filtered.valueFilter()).isInstanceOf(ScimFilter.Compare.class);
        assertThat(((ScimFilter.Compare) filtered.valueFilter()).operator())
                .isEqualTo(ScimComparisonOperator.EQ);
    }

    @Test
    @DisplayName("ScimPatchPath：未闭合过滤器与空白属性被拒绝")
    void patchPathValidation() {
        assertThatThrownBy(() -> ScimPatchPath.parse("members[value eq \"u-1\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unterminated");
        assertThatThrownBy(() -> new ScimPatchPath(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attribute");
        assertThatThrownBy(() -> new ScimPatchPath(" ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
