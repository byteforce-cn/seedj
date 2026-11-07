package cn.byteforce.seedj.starter.iam.scim;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared SCIM assertions: error code plus message prefix. */
final class ScimAssertions {

    private ScimAssertions() {
    }

    static void assertIamError(ThrowingCallable call, IamErrorCode code, String messagePrefix) {
        assertThatThrownBy(call)
                .isInstanceOf(IamException.class)
                .hasMessageStartingWith(messagePrefix)
                .extracting(exception -> ((IamException) exception).getCode())
                .isEqualTo(code);
    }
}
