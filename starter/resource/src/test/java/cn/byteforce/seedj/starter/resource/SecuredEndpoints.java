package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试端点：认证、授权（方法级）与身份上下文读取。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public class SecuredEndpoints {

    /**
     * 身份读取端点（需认证）。
     *
     * @return 当前请求上下文快照
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", RequestContext.traceId().orElse(null));
        result.put("userId", RequestContext.userId().orElse(null));
        result.put("tenantId", RequestContext.tenantId().orElse(null));
        result.put("username", RequestContext.username().orElse(null));
        return result;
    }

    /**
     * 管理端点（需 {@code SCOPE_admin} 权限）。
     *
     * @return 统一成功返回体
     */
    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    public ApiResponse<String> admin() {
        return ApiResponse.success("admin-ok");
    }

    /**
     * 免认证端点（默认被测试配置放行）。
     *
     * @return 统一成功返回体
     */
    @GetMapping("/public")
    public ApiResponse<String> publicPath() {
        return ApiResponse.success("public-ok");
    }
}
