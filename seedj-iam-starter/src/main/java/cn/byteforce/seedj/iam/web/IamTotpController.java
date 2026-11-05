package cn.byteforce.seedj.iam.web;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.identity.IamIdentityService;
import cn.byteforce.seedj.iam.identity.IamTotpService;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.iam.totp.TotpEnrollment;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** TOTP enrollment endpoints for the authenticated browser session or bearer token. */
@RestController
@RequestMapping(path = "/iam/mfa/totp", produces = MediaType.APPLICATION_JSON_VALUE)
public class IamTotpController {

    private final IamTotpService totp;
    private final IamUserRepository users;
    private final IamIdentityService identities;
    private final String tenantId;

    public IamTotpController(IamTotpService totp, IamUserRepository users,
                             IamIdentityService identities, IamProperties properties) {
        this.totp = totp;
        this.users = users;
        this.identities = identities;
        this.tenantId = properties.tenantId();
    }

    @PostMapping("/enroll")
    public Map<String, Object> enroll(Authentication authentication) {
        IamUser user = current(authentication);
        TotpEnrollment enrollment = totp.enroll(user.id());
        return Map.of("secret", enrollment.secret(), "otpauthUri", enrollment.otpauthUri());
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(Authentication authentication,
                                       @RequestBody Map<String, Object> body) {
        IamUser user = current(authentication);
        String code = string(body.get("code"));
        List<String> recoveryCodes = totp.confirm(user.id(), code);
        return Map.of("enabled", true, "recoveryCodes", recoveryCodes);
    }

    @GetMapping("/status")
    public Map<String, Object> status(Authentication authentication) {
        IamUser user = current(authentication);
        IamTotpService.TotpStatus status = totp.status(user.id());
        return Map.of("enabled", status.enabled(),
                "recoveryCodesRemaining", status.recoveryCodesRemaining());
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(Authentication authentication,
                                       @RequestBody(required = false) Map<String, Object> body) {
        IamUser user = current(authentication);
        String code = body == null ? null : string(body.get("code"));
        String password = body == null ? null : string(body.get("password"));
        boolean verified = false;
        if (code != null && !code.isBlank()) {
            try {
                totp.verify(user.id(), code);
                verified = true;
            } catch (IamException e) {
                if (e.getCode() != IamErrorCode.TOTP_INVALID
                        && e.getCode() != IamErrorCode.TOTP_REPLAY) {
                    throw e;
                }
            }
        }
        if (!verified && password != null && !password.isBlank()) {
            identities.authenticate(user.tenantId(), user.userName(), password);
            verified = true;
        }
        if (!verified) {
            throw new IamException(IamErrorCode.TOTP_INVALID,
                    "a valid TOTP code or password is required");
        }
        totp.disable(user.id());
        identities.revokeSessions(user);
        return Map.of("enabled", false);
    }

    private IamUser current(Authentication authentication) {
        if (authentication == null) {
            throw new IamException(IamErrorCode.INVALID_CREDENTIALS, "authentication required");
        }
        return users.findByUserName(tenantId, authentication.getName())
                .orElseThrow(() -> new IamException(IamErrorCode.USER_NOT_FOUND, "user not found"));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
