package cn.byteforce.seedj.starter.iam.web;

import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;

/**
 * Form-login success handler: if the user has confirmed TOTP, keep the
 * authentication pending in the session and redirect to /mfa. Otherwise use the
 * existing reauthentication-aware handler.
 */
public class IamMfaAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    public static final String PENDING_ATTRIBUTE = "cn.byteforce.seedj.starter.iam.MFA_PENDING";

    private final IamUserRepository users;
    private final IamTotpService totp;
    private final String tenantId;
    private final ReauthAwareAuthenticationSuccessHandler delegate;
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    public IamMfaAuthenticationSuccessHandler(IamUserRepository users, IamTotpService totp,
                                               String tenantId,
                                               AuthorizationServerSettings settings) {
        this.users = users;
        this.totp = totp;
        this.tenantId = tenantId;
        this.delegate = new ReauthAwareAuthenticationSuccessHandler(settings);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        IamUser user = users.findByUserName(tenantId, authentication.getName()).orElse(null);
        if (user != null && totp != null && totp.isEnabled(user.id())) {
            requestCache.saveRequest(request, response);
            request.getSession(true).setAttribute(PENDING_ATTRIBUTE, authentication);
            SecurityContextHolder.clearContext();
            response.sendRedirect(request.getContextPath() + "/mfa");
            return;
        }
        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}
