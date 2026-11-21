package cn.byteforce.seedj.im.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImCardAction;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 卡片动作存储契约：抢占、完成、放弃。 */
public abstract class ImCardActionStoreContractTest {

    protected abstract ImCardActionStore store();

    @Test
    @DisplayName("首次 begin 为空，完成后重试返回结果")
    void completeMakesRetryReturnResult() {
        ImCardAction action = action();

        assertThat(store().begin(action)).isEmpty();
        store().complete(action, Map.of("status", "approved"));
        assertThat(store().begin(action)).contains(Map.of("status", "approved"));
    }

    @Test
    @DisplayName("并发 begin：第二个 409")
    void secondBeginConflicts() {
        ImCardAction action = action();
        assertThat(store().begin(action)).isEmpty();

        assertThatThrownBy(() -> store().begin(action))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("abandon 后允许重试")
    void abandonAllowsRetry() {
        ImCardAction action = action();
        store().begin(action);
        store().abandon(action);

        assertThat(store().begin(action)).isEmpty();
    }

    protected static ImCardAction action() {
        return new ImCardAction(UUID.randomUUID(), "approve", "t1", UUID.randomUUID(), "u-1");
    }
}
