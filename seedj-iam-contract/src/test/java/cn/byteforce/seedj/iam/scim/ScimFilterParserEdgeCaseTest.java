package cn.byteforce.seedj.iam.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SCIM filter 解析器边界：全部比较运算符、字面量类型与语法错误位置。 */
class ScimFilterParserEdgeCaseTest {

    @Test
    @DisplayName("null / 空白过滤条件解析为 null（表示无过滤）")
    void blankFilterIsNull() {
        assertThat(ScimFilterParser.parse(null)).isNull();
        assertThat(ScimFilterParser.parse(" ")).isNull();
    }

    @Test
    @DisplayName("支持 ne/sw/ew/gt/lt/ge/le 全部比较运算符")
    void parsesEveryComparisonOperator() {
        assertThat(operator("userName ne \"alice\"")).isEqualTo(ScimComparisonOperator.NE);
        assertThat(operator("userName sw \"al\"")).isEqualTo(ScimComparisonOperator.SW);
        assertThat(operator("userName ew \"ice\"")).isEqualTo(ScimComparisonOperator.EW);
        assertThat(operator("version gt 3")).isEqualTo(ScimComparisonOperator.GT);
        assertThat(operator("version lt 3")).isEqualTo(ScimComparisonOperator.LT);
        assertThat(operator("version ge 3")).isEqualTo(ScimComparisonOperator.GE);
        assertThat(operator("version le 3")).isEqualTo(ScimComparisonOperator.LE);
        assertThat(operator("userName EQ \"alice\"")).isEqualTo(ScimComparisonOperator.EQ);
    }

    @Test
    @DisplayName("值类型：布尔、null、整数、浮点与裸词")
    void parsesValueTypes() {
        assertThat(value("active eq true")).isEqualTo(Boolean.TRUE);
        assertThat(value("active eq FALSE")).isEqualTo(Boolean.FALSE);
        assertThat(value("externalId eq null")).isNull();
        assertThat(value("version eq 7")).isEqualTo(7L);
        assertThat(value("score gt 1.5")).isEqualTo(1.5d);
        assertThat(value("userName eq alice")).isEqualTo("alice");
    }

    @Test
    @DisplayName("尾部多余 token 视为语法错误")
    void rejectsTrailingTokens() {
        assertThatThrownBy(() -> ScimFilterParser.parse("active eq true extra"))
                .isInstanceOf(IamException.class)
                .extracting(error -> ((IamException) error).getCode())
                .isEqualTo(IamErrorCode.SCIM_FILTER_INVALID);
    }

    @Test
    @DisplayName("缺少属性名或运算符时报无效过滤条件")
    void rejectsMissingOperands() {
        assertThatThrownBy(() -> ScimFilterParser.parse("eq \"x\""))
                .isInstanceOf(IamException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse("\"x\" eq \"y\""))
                .isInstanceOf(IamException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse("userName eq"))
                .isInstanceOf(IamException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse("(active eq true"))
                .isInstanceOf(IamException.class);
    }

    @Test
    @DisplayName("未闭合字符串字面量报错")
    void rejectsUnterminatedString() {
        assertThatThrownBy(() -> ScimFilterParser.parse("userName eq \"alice"))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("unterminated string");
    }

    @Test
    @DisplayName("括号内的属性名以 ')' 结束单词扫描")
    void wordScanStopsAtParenthesis() {
        ScimFilter filter = ScimFilterParser.parse("(externalId pr)");

        assertThat(filter).isInstanceOf(ScimFilter.Present.class);
        assertThat(((ScimFilter.Present) filter).attribute()).isEqualTo("externalId");
    }

    @Test
    @DisplayName("not 可叠加")
    void notIsRecursive() {
        ScimFilter filter = ScimFilterParser.parse("not not (active eq true)");

        assertThat(filter).isInstanceOf(ScimFilter.Not.class);
        assertThat(((ScimFilter.Not) filter).filter()).isInstanceOf(ScimFilter.Not.class);
    }

    @Test
    @DisplayName("and/or 链式组合保留顺序")
    void chainsAndOrParts() {
        ScimFilter filter = ScimFilterParser.parse("a eq 1 and b eq 2 and c eq 3");

        assertThat(filter).isInstanceOf(ScimFilter.And.class);
        assertThat(((ScimFilter.And) filter).filters()).hasSize(3);
    }

    private static ScimComparisonOperator operator(String input) {
        return ((ScimFilter.Compare) ScimFilterParser.parse(input)).operator();
    }

    private static Object value(String input) {
        return ((ScimFilter.Compare) ScimFilterParser.parse(input)).value();
    }
}
