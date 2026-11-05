package cn.byteforce.seedj.cqrs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标识契约：流键可解析、codec 可往返、非法输入必须显式失败。
 *
 * <p>这里测的是"边界语义"，不是实现细节：{@code canonical()} 的文本要能被切分回
 * 类型与标识；codec 的失败必须抛 {@link CqrsException}，不能返回 {@code null}
 * 或静默降级——否则错误会推迟到某个消费者把两条流当成一条时才暴露。
 */
class IdContractTest {

    @Test
    @DisplayName("流键规范文本可用类型前缀无歧义解析")
    void canonicalFormKeepsTypePrefixParseable() {
        StreamId<Long> stream = StreamId.of("hr.employee", 42L);
        String canonical = stream.canonical(IdCodecs.longs());

        assertThat(canonical).isEqualTo("hr.employee:42");
        String type = canonical.substring(0, canonical.indexOf(':'));
        String encoded = canonical.substring(canonical.indexOf(':') + 1);
        assertThat(type).isEqualTo(stream.aggregateType());
        assertThat(IdCodecs.longs().decode(encoded)).isEqualTo(stream.id());
    }

    @Test
    @DisplayName("类型名含 ':' 直接失败，守卫流键可解析性")
    void rejectsAmbiguousAggregateType() {
        assertThatThrownBy(() -> StreamId.of("hr:employee", 1L))
                .isInstanceOfSatisfying(CqrsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CqrsErrorCode.INVALID_STREAM_ID));
    }

    @Test
    @DisplayName("空类型名与 null 标识直接失败")
    void rejectsBlankTypeAndNullId() {
        assertThatThrownBy(() -> StreamId.of(" ", 1L))
                .isInstanceOf(CqrsException.class);
        assertThatThrownBy(() -> StreamId.of("hr.employee", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("内置 codec 对合法值可往返")
    void builtInCodecsRoundTrip() {
        assertThat(IdCodecs.strings().decode(IdCodecs.strings().encode("emp-1")))
                .isEqualTo("emp-1");
        assertThat(IdCodecs.longs().decode(IdCodecs.longs().encode(9_007_199_254_740_993L)))
                .isEqualTo(9_007_199_254_740_993L);

        UUID uuid = UUID.fromString("0190f3a2-7c4d-7f00-9a7e-2b6f0b5c9d10");
        assertThat(IdCodecs.uuids().decode(IdCodecs.uuids().encode(uuid))).isEqualTo(uuid);
    }

    @Test
    @DisplayName("非法文本抛 INVALID_ID，不返回 null")
    void rejectsMalformedText() {
        assertThatThrownBy(() -> IdCodecs.longs().decode("12x"))
                .isInstanceOfSatisfying(CqrsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CqrsErrorCode.INVALID_ID));
        assertThatThrownBy(() -> IdCodecs.uuids().decode("not-a-uuid"))
                .isInstanceOfSatisfying(CqrsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CqrsErrorCode.INVALID_ID));
        assertThatThrownBy(() -> IdCodecs.strings().encode(""))
                .isInstanceOfSatisfying(CqrsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CqrsErrorCode.INVALID_ID));
    }
}
