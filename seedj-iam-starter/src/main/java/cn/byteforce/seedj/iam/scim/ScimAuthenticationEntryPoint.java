package cn.byteforce.seedj.iam.scim;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** SCIM error envelope for missing/invalid bearer tokens. */
public class ScimAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"invalid_token\"");
        response.setContentType(ScimSupport.CONTENT_TYPE);
        JSON.writeValue(response.getOutputStream(),
                ScimSupport.error("invalid or missing bearer token", 401, null));
    }
}
