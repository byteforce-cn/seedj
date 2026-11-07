package cn.byteforce.seedj.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 时点参数推导视图：当前/时点；历史由 ReadGateway 的 history 入口表达。 */
class TemporalViewTest {

    @Test
    @DisplayName("无时点为 CURRENT")
    void noTimestampsIsCurrent() {
        assertThat(TemporalQuery.current().view()).isEqualTo(TemporalView.CURRENT);
    }

    @Test
    @DisplayName("业务时点为 AS_OF")
    void validTimeIsAsOf() {
        assertThat(new TemporalQuery(Instant.EPOCH, null, null).view())
                .isEqualTo(TemporalView.AS_OF);
    }

    @Test
    @DisplayName("系统时点为 AS_OF")
    void recordedTimeIsAsOf() {
        assertThat(new TemporalQuery(null, Instant.EPOCH, null).view())
                .isEqualTo(TemporalView.AS_OF);
    }

    @Test
    @DisplayName("HISTORY 是独立入口，不由 TemporalQuery 推导")
    void historyIsExplicit() {
        assertThat(TemporalView.values()).containsExactly(TemporalView.CURRENT,
                TemporalView.AS_OF, TemporalView.HISTORY);
    }
}
