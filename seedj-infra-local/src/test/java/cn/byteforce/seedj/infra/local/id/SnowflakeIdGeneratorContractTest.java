package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.IdGeneratorContractTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorContractTest extends IdGeneratorContractTest {

    @Override
    protected IdGenerator<Long> newGenerator() {
        return new SnowflakeIdGenerator(7);
    }

    @Test
    @DisplayName("不同节点编号产生的 ID 不重叠（高位不同）")
    void nodeIdIsEncodedInId() {
        SnowflakeIdGenerator a = new SnowflakeIdGenerator(1);
        SnowflakeIdGenerator b = new SnowflakeIdGenerator(2);

        long idA = a.id();
        long idB = b.id();

        assertThat(idA >> 12 & 0x3FF).isEqualTo(1);
        assertThat(idB >> 12 & 0x3FF).isEqualTo(2);
    }

    @Test
    @DisplayName("节点编号越界在构造期失败")
    void nodeIdBounds() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024)).isInstanceOf(IdException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1)).isInstanceOf(IdException.class);
    }

    @Test
    @DisplayName("时钟轻微回拨不产生重复 ID；大幅回拨抛 CLOCK_BACKWARDS")
    void clockBackwards(@TempDir Path dir) {
        long[] now = {10_000L};
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3,
                SnowflakeIdGenerator.DEFAULT_EPOCH_MILLIS, () -> now[0]);
        long first = generator.id();

        now[0] -= 1; // 回拨 1ms
        long second = generator.id();
        assertThat(second).isGreaterThan(first);

        now[0] -= 10_000; // 回拨 10s
        assertThatThrownBy(generator::id).isInstanceOf(IdException.class);
    }

    @Test
    @DisplayName("共享文件分配节点编号：多实例拿到不同编号，用尽后失败")
    void nodeIdAllocation(@TempDir Path dir) {
        Path stateFile = dir.resolve("nodes.properties");

        int first = new NodeIdAllocator(stateFile).allocate();
        int second = new NodeIdAllocator(stateFile).allocate();
        int third = new NodeIdAllocator(stateFile).allocate();

        assertThat(first).isEqualTo(0);
        assertThat(second).isEqualTo(1);
        assertThat(third).isEqualTo(2);
    }

    @Test
    @DisplayName("节点编号用尽时抛 NODE_ID_EXHAUSTED")
    void nodeIdExhaustion(@TempDir Path dir) throws Exception {
        Path stateFile = dir.resolve("nodes.properties");
        java.nio.file.Files.writeString(stateFile, "next=1024\n");

        assertThatThrownBy(() -> new NodeIdAllocator(stateFile).allocate())
                .isInstanceOf(IdException.class);
    }
}
