package cn.byteforce.seedj.starter.iam.web;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamMfaAttemptLimiter;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Browser MFA challenge: upgrades a pending password authentication to a complete one. */
@Controller
public class IamMfaController {

    private final IamUserRepository users;
    private final IamTotpService totp;
    private final IamMfaAttemptLimiter attempts;
    private final String tenantId;
    private final ReauthAwareAuthenticationSuccessHandler successHandler;

    public IamMfaController(
            IamUserRepository users,
            IamTotpService totp,
            IamMfaAttemptLimiter attempts,
            IamProperties properties,
            AuthorizationServerSettings settings) {
        this.users = users;
        this.totp = totp;
        this.attempts = attempts;
        this.tenantId = properties.tenantId();
        this.successHandler = new ReauthAwareAuthenticationSuccessHandler(settings);
    }

    @GetMapping(value = "/mfa", produces = MediaType.TEXT_HTML_VALUE)
    public void page(HttpServletRequest request, HttpServletResponse response, CsrfToken csrf)
            throws IOException {
        if (pending(request) == null) {
            response.sendRedirect(request.getContextPath() + "/login");
            return;
        }
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.getWriter()
                .write(
                        """
                <!doctype html><html><head><meta charset="utf-8"><title>MFA</title></head>
                <body><h1>Two-factor authentication</h1>
                <form method="post" action="%s/mfa">
                <input type="hidden" name="_csrf" value="%s">
                <label>Authentication code <input name="code" autocomplete="one-time-code" autofocus></label>
                <button type="submit">Verify</button></form></body></html>
                """
                                .formatted(request.getContextPath(), csrf.getToken()));
    }

    @PostMapping(value = "/mfa", produces = MediaType.TEXT_HTML_VALUE)
    public void verify(
            @RequestParam("code") String code,
            HttpServletRequest request,
            HttpServletResponse response,
            CsrfToken csrf)
            throws IOException, ServletException {
        Authentication pending = pending(request);
        if (pending == null) {
            response.sendRedirect(request.getContextPath() + "/login");
            return;
        }
        IamUser user = users.findByUserName(tenantId, pending.getName()).orElse(null);
        if (user == null) {
            failure(request, response, csrf, "unknown user");
            return;
        }
        if (attempts.locked(user.id())) {
            response.setStatus(429);
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter()
                    .write(
                            """
                    <!doctype html><html><head><meta charset="utf-8"><title>MFA</title></head>
                    <body><h1>Two-factor authentication</h1>
                    <p>Too many attempts. Try again later.</p></body></html>
                    """);
            return;
        }
        boolean recovery = false;
        try {
            totp.verify(user.id(), code);
        } catch (IamException e) {
            if (e.getCode() != IamErrorCode.TOTP_INVALID
                    && e.getCode() != IamErrorCode.TOTP_REPLAY) {
                failure(request, response, csrf, "invalid code");
                return;
            }
            try {
                totp.verifyRecoveryCode(user.id(), code);
                recovery = true;
            } catch (IamException recoveryFailure) {
                attempts.failure(user.id());
                failure(request, response, csrf, "invalid code");
                return;
            }
        }

        attempts.success(user.id());
        List<GrantedAuthority> authorities = new ArrayList<>(pending.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("AMR_PWD"));
        authorities.add(new SimpleGrantedAuthority(recovery ? "AMR_RECOVERY" : "AMR_TOTP"));
        authorities.add(
                new SimpleGrantedAuthority(
                        recovery ? "ACR_urn:seedj:acr:recovery" : "ACR_urn:seedj:acr:totp"));
        authorities.add(new SimpleGrantedAuthority("AUTH_TIME_" + Instant.now().getEpochSecond()));
        UsernamePasswordAuthenticationToken completed =
                new UsernamePasswordAuthenticationToken(
                        pending.getPrincipal(), pending.getCredentials(), authorities);
        completed.setDetails(pending.getDetails());

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(IamMfaAuthenticationSuccessHandler.PENDING_ATTRIBUTE);
            request.changeSessionId();
        }
        SecurityContextHolder.getContext().setAuthentication(completed);
        successHandler.onAuthenticationSuccess(request, response, completed);
    }

    private static Authentication pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null
                ? null
                : (Authentication)
                        session.getAttribute(IamMfaAuthenticationSuccessHandler.PENDING_ATTRIBUTE);
    }

    private static void failure(
            HttpServletRequest request,
            HttpServletResponse response,
            CsrfToken csrf,
            String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.getWriter()
                .write(
                        """
                <!doctype html><html><head><meta charset="utf-8"><title>MFA</title></head>
                <body><h1>Two-factor authentication</h1><p>Invalid code.</p>
                <form method="post" action="%s/mfa">
                <input type="hidden" name="_csrf" value="%s">
                <label>Authentication code <input name="code" autocomplete="one-time-code" autofocus></label>
                <button type="submit">Verify</button></form></body></html>
                """
                                .formatted(request.getContextPath(), csrf.getToken()));
    }
}
