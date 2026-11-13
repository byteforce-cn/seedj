package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同一主体的多连接注册与解绑语义。 */
class ChannelRegistryTest {

    private final ChannelRegistry registry = new ChannelRegistry();

    @Test
    @DisplayName("同一 subject 可绑定多条连接")
    void bindsMultipleChannelsPerSubject() {
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("alice", first);
            registry.bind("alice", second);

            assertThat(registry.channels("alice")).containsExactlyInAnyOrder(first, second);
            assertThat(registry.size()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("解绑一条连接不影响同一 subject 的另一条")
    void unbindKeepsOtherChannels() {
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("alice", first);
            registry.bind("alice", second);

            registry.unbind("alice", first);

            assertThat(registry.channels("alice")).containsExactly(second);
            assertThat(registry.size()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("最后一条连接解绑后移除 subject")
    void unbindLastChannelRemovesSubject() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            registry.bind("alice", channel);

            registry.unbind("alice", channel);

            assertThat(registry.channels("alice")).isEmpty();
            assertThat(registry.size()).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
