package cn.byteforce.seedj.starter.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.iam.totp.TotpEnrollment;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/** TOTP 自助端点的认证、用户解析与 disable 双重凭证语义。 */
class IamTotpControllerTest {

    private static final IamUser USER =
            IamUser.create("user-1", "default", "alice", "Alice", "alice@example.com", true);

    private final IamTotpService totp = mock(IamTotpService.class);
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final IamIdentityService identities = mock(IamIdentityService.class);

    private IamTotpController controller;

    @BeforeEach
    void setUp() {
        controller = new IamTotpController(totp, users, identities, IamProperties.defaults());
    }

    @Test
    void enrollRequiresAuthentication() {
        assertThatThrownBy(() -> controller.enroll(null))
                .isInstanceOf(IamException.class)
                .extracting(e -> ((IamException) e).getCode())
                .isEqualTo(IamErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void enrollFailsForUnknownUser() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.enroll(authentication()))
                .isInstanceOf(IamException.class)
                .extracting(e -> ((IamException) e).getCode())
                .isEqualTo(IamErrorCode.USER_NOT_FOUND);
    }

    @Test
    void enrollReturnsSecretAndOtpauthUri() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        when(totp.enroll(USER.id()))
                .thenReturn(
                        new TotpEnrollment("SECRET", "otpauth://totp/Seedj:alice?secret=SECRET"));

        Map<String, Object> body = controller.enroll(authentication());

        assertThat(body)
                .containsEntry("secret", "SECRET")
                .containsEntry("otpauthUri", "otpauth://totp/Seedj:alice?secret=SECRET");
    }

    @Test
    void confirmReturnsRecoveryCodes() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        when(totp.confirm(USER.id(), "123456")).thenReturn(List.of("A1", "B2"));

        Map<String, Object> body = controller.confirm(authentication(), Map.of("code", "123456"));

        assertThat(body)
                .containsEntry("enabled", true)
                .containsEntry("recoveryCodes", List.of("A1", "B2"));
    }

    @Test
    void statusReportsEnrollmentState() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        when(totp.status(USER.id())).thenReturn(new IamTotpService.TotpStatus(true, 7));

        Map<String, Object> body = controller.status(authentication());

        assertThat(body).containsEntry("enabled", true).containsEntry("recoveryCodesRemaining", 7);
    }

    @Test
    void disableAcceptsValidCode() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));

        Map<String, Object> body = controller.disable(authentication(), Map.of("code", "123456"));

        assertThat(body).containsEntry("enabled", false);
        verify(totp).verify(USER.id(), "123456");
        verify(totp).disable(USER.id());
        verify(identities).revokeSessions(USER);
    }

    @Test
    void disableAcceptsPasswordWhenCodeIsMissing() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        when(identities.authenticate("default", "alice", "pw")).thenReturn(USER);

        controller.disable(authentication(), Map.of("password", "pw"));

        verify(totp, never()).verify(anyString(), anyString());
        verify(totp).disable(USER.id());
    }

    @Test
    void disableFallsBackToPasswordWhenCodeIsRejected() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        doThrow(new IamException(IamErrorCode.TOTP_REPLAY, "replay"))
                .when(totp)
                .verify(USER.id(), "123456");
        when(identities.authenticate("default", "alice", "pw")).thenReturn(USER);

        controller.disable(authentication(), Map.of("code", "123456", "password", "pw"));

        verify(identities).authenticate("default", "alice", "pw");
        verify(totp).disable(USER.id());
    }

    @Test
    void disableWithoutAnyCredentialIsRejected() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));

        assertThatThrownBy(() -> controller.disable(authentication(), null))
                .isInstanceOf(IamException.class)
                .extracting(e -> ((IamException) e).getCode())
                .isEqualTo(IamErrorCode.TOTP_INVALID);
        verify(totp, never()).disable(anyString());
    }

    @Test
    void disablePropagatesNonTotpFailures() {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        doThrow(new IamException(IamErrorCode.TOTP_NOT_ENROLLED, "not enrolled"))
                .when(totp)
                .verify(USER.id(), "123456");

        assertThatThrownBy(() -> controller.disable(authentication(), Map.of("code", "123456")))
                .isInstanceOf(IamException.class)
                .extracting(e -> ((IamException) e).getCode())
                .isEqualTo(IamErrorCode.TOTP_NOT_ENROLLED);
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken("alice", "n/a");
    }
}
