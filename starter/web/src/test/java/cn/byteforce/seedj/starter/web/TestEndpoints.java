package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.core.exception.BizException;
import cn.byteforce.seedj.core.exception.RemoteException;
import cn.byteforce.seedj.web.core.ApiResponse;
import cn.byteforce.seedj.web.core.ResponseCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试端点：覆盖成功、业务异常、未预期异常、参数校验与上下文读取。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public class TestEndpoints {

    private final ValidationService validationService;

    /**
     * 构造端点。
     *
     * @param validationService 校验服务（覆盖 jakarta 约束违例路径）
     */
    public TestEndpoints(ValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * 成功端点。
     *
     * @return 统一成功返回体
     */
    @GetMapping("/ok")
    public ApiResponse<String> ok() {
        return ApiResponse.success("hello");
    }

    /**
     * 业务异常端点。
     *
     * @return 永不返回（抛业务异常）
     */
    @GetMapping("/biz")
    public ApiResponse<Void> biz() {
        throw new BizException(ResponseCode.BIZ_ERROR, "库存不足");
    }

    /**
     * 未预期异常端点。
     *
     * @return 永不返回（抛运行时异常）
     */
    @GetMapping("/boom")
    public ApiResponse<Void> boom() {
        throw new IllegalStateException("内部细节不应外泄");
    }

    /**
     * 远程调用异常端点（503 分支）。
     *
     * @return 永不返回（抛远程调用异常）
     */
    @GetMapping("/remote")
    public ApiResponse<Void> remote() {
        throw new RemoteException(ResponseCode.SERVICE_UNAVAILABLE);
    }

    /**
     * 方法级参数校验端点（q 必填且非空）。
     *
     * @param q 查询参数
     * @return 统一成功返回体
     */
    @GetMapping("/named")
    public ApiResponse<String> named(@RequestParam("q") @NotBlank(message = "q 不能为空") String q) {
        return ApiResponse.success(q);
    }

    /**
     * 类型转换端点（page 必须为整数）。
     *
     * @param page 页码
     * @return 统一成功返回体
     */
    @GetMapping("/number")
    public ApiResponse<Integer> number(@RequestParam("page") Integer page) {
        return ApiResponse.success(page);
    }

    /**
     * 必填参数端点。
     *
     * @param q 必填查询参数
     * @return 统一成功返回体
     */
    @GetMapping("/required")
    public ApiResponse<String> required(@RequestParam("q") String q) {
        return ApiResponse.success(q);
    }

    /**
     * 服务层约束违例端点。
     *
     * @return 统一成功返回体
     */
    @GetMapping("/svc")
    public ApiResponse<String> svc() {
        return ApiResponse.success(validationService.check(null));
    }

    /**
     * 参数校验端点。
     *
     * @param command 校验命令
     * @return 统一成功返回体
     */
    @PostMapping("/validated")
    public ApiResponse<String> validated(@Valid @RequestBody ValidationCommand command) {
        return ApiResponse.success(command.name);
    }

    /**
     * 上下文读取端点。
     *
     * @return 当前请求上下文快照（traceId / 用户 / 租户 / 用户名）
     */
    @GetMapping("/ctx")
    public Map<String, Object> ctx() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", RequestContext.traceId().orElse(null));
        result.put("userId", RequestContext.userId().orElse(null));
        result.put("tenantId", RequestContext.tenantId().orElse(null));
        result.put("username", RequestContext.username().orElse(null));
        return result;
    }
}
