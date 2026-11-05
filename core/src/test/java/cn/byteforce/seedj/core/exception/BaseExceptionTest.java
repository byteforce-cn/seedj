package cn.byteforce.seedj.core.exception;

import cn.byteforce.seedj.core.SampleCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 异常体系契约测试：错误码、HTTP 状态默认值与消息回落规则。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class BaseExceptionTest {

    @Test
    void baseExceptionDefaultsToBusinessHttpStatus() {
        BaseException ex = new BaseException(10007, "库存不足");

        assertThat(ex.getCode()).isEqualTo(10007);
        assertThat(ex.getMessage()).isEqualTo("库存不足");
        assertThat(ex.getHttpStatus()).isEqualTo(200);
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void baseExceptionSupportsCustomHttpStatusAndCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        BaseException ex = new BaseException(20001, "依赖不可用", 503, cause);

        assertThat(ex.getHttpStatus()).isEqualTo(503);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void bizExceptionResolvesMessageFromCodeLabel() {
        BizException ex = new BizException(SampleCode.ERROR);

        assertThat(ex.getCode()).isEqualTo(20);
        assertThat(ex.getMessage()).isEqualTo("失败");
        assertThat(ex.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void bizExceptionSupportsCustomMessageAndCause() {
        Throwable cause = new RuntimeException("root");
        BizException ex = new BizException(SampleCode.OK, "自定义消息", cause);

        assertThat(ex.getCode()).isEqualTo(10);
        assertThat(ex.getMessage()).isEqualTo("自定义消息");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void bizExceptionRejectsNullCodeEnum() {
        assertThatThrownBy(() -> new BizException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
    }

    @Test
    void bizExceptionSupportsIntCode() {
        BizException ex = new BizException(10001, "参数错误");

        assertThat(ex.getCode()).isEqualTo(10001);
        assertThat(ex.getMessage()).isEqualTo("参数错误");
        assertThat(ex.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void remoteExceptionDefaultsToServiceUnavailable() {
        RemoteException ex = new RemoteException(SampleCode.ERROR);

        assertThat(ex.getCode()).isEqualTo(20);
        assertThat(ex.getMessage()).isEqualTo("失败");
        assertThat(ex.getHttpStatus()).isEqualTo(503);
    }

    @Test
    void remoteExceptionSupportsTimeoutSemantics() {
        RemoteException ex = new RemoteException(20002, "请求超时", 504, null);

        assertThat(ex.getCode()).isEqualTo(20002);
        assertThat(ex.getHttpStatus()).isEqualTo(504);
    }
}
