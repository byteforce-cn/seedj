package cn.byteforce.seedj.starter.iam.scim;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** SCIM error envelope for insufficient scopes. */
public class ScimAccessDeniedHandler implements AccessDeniedHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        response.setContentType(ScimSupport.CONTENT_TYPE);
        JSON.writeValue(
                response.getOutputStream(), ScimSupport.error("insufficient scope", 403, null));
    }
}
