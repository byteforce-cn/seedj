package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命令字注册表：查表、重复注册失败。 */
class ImCommandRegistryTest {

    @Test
    @DisplayName("按命令字查表，未注册为空")
    void findsRegisteredHandler() {
        ImCommandHandler<LoginReq> handler = handler(Cmd.ACK_REQ);
        ImCommandRegistry registry = new ImCommandRegistry(List.of(handler));

        assertThat(registry.find(Cmd.ACK_REQ)).containsSame(handler);
        assertThat(registry.find(Cmd.CHAT_SINGLE_REQ)).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.commands()).containsExactly(Cmd.ACK_REQ);
    }

    @Test
    @DisplayName("同一命令字注册两次是启动失败，不是后者覆盖")
    void duplicateCommandFailsFast() {
        List<ImCommandHandler<?>> handlers = List.of(handler(Cmd.ACK_REQ), handler(Cmd.ACK_REQ));

        assertThatThrownBy(() -> new ImCommandRegistry(handlers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate IM command handler");
    }

    @Test
    @DisplayName("空集合是合法注册表")
    void emptyRegistryIsAllowed() {
        assertThat(new ImCommandRegistry(List.of()).size()).isZero();
    }

    private static ImCommandHandler<LoginReq> handler(byte cmd) {
        return new ImCommandHandler<>() {
            @Override
            public byte cmd() {
                return cmd;
            }

            @Override
            public Class<LoginReq> bodyType() {
                return LoginReq.class;
            }

            @Override
            public ImReply handle(ImCallContext context, LoginReq body) {
                return null;
            }
        };
    }
}
