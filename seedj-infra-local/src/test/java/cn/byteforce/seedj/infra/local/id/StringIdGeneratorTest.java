package cn.byteforce.seedj.infra.local.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.id.IdGenerator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 字符串 ID 适配：委托关闭只发生一次，关闭后拒绝发号。 */
class StringIdGeneratorTest {

    @Test
    @DisplayName("关闭幂等且关闭后拒绝发号")
    void closesIdempotently() {
        AtomicInteger closeCount = new AtomicInteger();
        IdGenerator<Long> delegate =
                new IdGenerator<>() {
                    @Override
                    public Long id() {
                        return 42L;
                    }

                    @Override
                    public void close() {
                        closeCount.incrementAndGet();
                    }
                };
        StringIdGenerator generator = new StringIdGenerator(delegate);

        assertThat(generator.id()).isEqualTo("42");

        generator.close();
        generator.close();

        assertThat(closeCount).hasValue(1);
        assertThatThrownBy(generator::id)
                .isInstanceOfSatisfying(
                        IdException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(IdErrorCode.PROVIDER_CLOSED));
    }
}
