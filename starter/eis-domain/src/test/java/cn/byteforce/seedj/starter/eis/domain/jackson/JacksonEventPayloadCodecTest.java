package cn.byteforce.seedj.starter.eis.domain.jackson;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventType;
import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcaster;
import cn.byteforce.seedj.starter.eis.domain.spi.EventUpcasterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编解码器：三支路（等版本 / 旧版本 upcast / 新版本 fail-fast）与数值形态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class JacksonEventPayloadCodecTest {

    private final EventTypeRegistry eventTypes = new EventTypeRegistry();

    private final EventUpcasterRegistry upcasters = new EventUpcasterRegistry();

    private JacksonEventPayloadCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JacksonEventPayloadCodec(JsonMapper.builder().build(), eventTypes, upcasters);
    }

    @Test
    @DisplayName("payload 中 long 保持数值形态（不复用 Web 层的 Long 字符串化）")
    void keepsNumbersNumeric() {
        eventTypes.register(DepositedCurrent.class);

        String payload = codec.encode(new DepositedCurrent(12L, "CNY"));

        assertThat(payload).contains("\"amount\":12");
        assertThat(payload).doesNotContain("\"amount\":\"12\"");
    }

    @Test
    @DisplayName("存储版本等于当前版本：直接反序列化")
    void decodesCurrentVersion() {
        eventTypes.register(DepositedCurrent.class);

        DomainEvent event = codec.decode("test.deposited", 2, "{\"amount\":7,\"currency\":\"CNY\"}");

        assertThat(event).isInstanceOf(DepositedCurrent.class);
        DepositedCurrent deposited = (DepositedCurrent) event;
        assertThat(deposited.getAmount()).isEqualTo(7L);
        assertThat(deposited.getCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("存储版本更旧：经 upcaster 链补齐默认值")
    void upcastsOlderPayload() {
        eventTypes.register(DepositedCurrent.class);
        upcasters.register(EventUpcaster.of("test.deposited", 1, 2, payload -> {
            payload.putIfAbsent("currency", "CNY");
            return payload;
        }));

        DomainEvent event = codec.decode("test.deposited", 1, "{\"amount\":7}");

        assertThat(((DepositedCurrent) event).getCurrency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("存储版本更新：数据比代码新，必须失败")
    void rejectsNewerPayload() {
        eventTypes.register(DepositedCurrent.class);

        assertThatThrownBy(() -> codec.decode("test.deposited", 3, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ahead of the running code")
                .hasMessageContaining("schema v3");
    }

    @Test
    @DisplayName("版本 0：非法写入，必须失败")
    void rejectsZeroVersion() {
        eventTypes.register(DepositedCurrent.class);

        assertThatThrownBy(() -> codec.decode("test.deposited", 0, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("versions start at 1");
    }

    @Test
    @DisplayName("upcaster 返回 null：立即失败并指名")
    void rejectsNullUpcastResult() {
        eventTypes.register(DepositedCurrent.class);
        upcasters.register(EventUpcaster.of("test.deposited", 1, 2, payload -> null));

        assertThatThrownBy(() -> codec.decode("test.deposited", 1, "{\"amount\":7}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null while upcasting");
    }

    @Test
    @DisplayName("未注册事件名：报错里列出已注册集合")
    void rejectsUnknownType() {
        eventTypes.register(DepositedCurrent.class);

        assertThatThrownBy(() -> codec.decode("who.knows", 1, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type")
                .hasMessageContaining("test.deposited");
    }

    /**
     * 当前版本事件（v2）。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    @EventType(value = "test.deposited", version = 2)
    static final class DepositedCurrent implements DomainEvent {

        private long amount;

        private String currency;

        /**
         * 供 Jackson 反序列化使用。
         */
        DepositedCurrent() {
        }

        DepositedCurrent(long amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }

        public long getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }
    }
}
