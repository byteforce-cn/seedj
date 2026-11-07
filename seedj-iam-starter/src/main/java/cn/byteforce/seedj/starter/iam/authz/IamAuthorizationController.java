package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.authz.AttributeLookup;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.AttributeState;
import cn.byteforce.seedj.iam.authz.IamCheckResult;
import cn.byteforce.seedj.iam.authz.IamResolution;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationSubject;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授权契约 HTTP 面：/resolve、/check、/principals/{id}/attributes。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class IamAuthorizationController {

    private static final String ATTRIBUTES_PERMISSION = "hr.attributes.read";

    private static final String IAM_ATTRIBUTES_PERMISSION = "iam.attributes.read";

    private final IamContractAuthorizationService service;

    private final AuthorizationDecisionPoint decisions;

    private final ObjectProvider<CallerProvider> callers;

    /**
     * 兼容旧装配：/check 使用契约服务本身作为决策点。
     *
     * @param service 契约服务
     * @param callers 当前调用方
     */
    public IamAuthorizationController(
            IamContractAuthorizationService service, ObjectProvider<CallerProvider> callers) {
        this(service, service, callers);
    }

    /**
     * 构造控制器。
     *
     * @param service 契约服务（/resolve 与属性端点）
     * @param decisions 唯一授权决策点（/check）
     * @param callers 当前调用方
     */
    public IamAuthorizationController(
            IamContractAuthorizationService service,
            AuthorizationDecisionPoint decisions,
            ObjectProvider<CallerProvider> callers) {
        this.service = service;
        this.decisions = decisions;
        this.callers = callers;
    }

    /**
     * 解析主体在指定时点的角色与权限。
     *
     * @param request 请求
     * @return 解析结果
     */
    @PostMapping(path = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IamAuthorizationDtos.ResolveResponse resolve(
            @RequestBody IamAuthorizationDtos.ResolveRequest request) {
        if (request.principal() == null
                || request.principal().isBlank()
                || request.asOf() == null) {
            throw new IamException(
                    IamErrorCode.SCIM_BAD_REQUEST, "principal and as_of are required");
        }
        IamResolution resolution =
                service.resolve(
                        request.principal(),
                        request.asOf(),
                        null,
                        request.scope() == null ? Map.of() : request.scope());
        return new IamAuthorizationDtos.ResolveResponse(
                resolution.roles(), resolution.permissions(), resolution.sodWarnings());
    }

    /**
     * 检查权限。
     *
     * @param request 请求
     * @return 检查结果
     */
    @PostMapping(path = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IamAuthorizationDtos.CheckResponse check(
            @RequestBody IamAuthorizationDtos.CheckRequest request) {
        if (request.principal() == null
                || request.principal().isBlank()
                || request.permission() == null
                || request.permission().isBlank()
                || request.asOf() == null) {
            throw new IamException(
                    IamErrorCode.SCIM_BAD_REQUEST, "principal, permission and as_of are required");
        }
        IamAuthorizationDtos.AttributeContext context = request.attributes();
        Map<String, Object> provided = context == null ? null : context.snapshot();
        AttributeState state = context == null ? null : parseState(context.attributeState());
        boolean resourceAware =
                request.resourceType() != null
                        || request.resourceId() != null
                        || (request.resourceAttributes() != null
                                && !request.resourceAttributes().isEmpty());
        if (!resourceAware) {
            IamCheckResult result =
                    service.check(
                            request.principal(),
                            request.permission(),
                            request.asOf(),
                            null,
                            provided,
                            state);
            return new IamAuthorizationDtos.CheckResponse(
                    result.allowed(),
                    result.reason(),
                    result.reasonCode(),
                    result.attributeState().wireName());
        }
        AuthorizationDecision decision =
                decisions.decide(
                        new AuthorizationRequest(
                                new AuthorizationSubject(
                                        request.principal(),
                                        request.principal(),
                                        "default",
                                        Set.of(),
                                        Set.of(),
                                        Set.of(),
                                        Set.of(),
                                        "",
                                        Map.of()),
                                request.permission(),
                                request.resourceType() == null
                                        ? "permission"
                                        : request.resourceType(),
                                request.resourceId(),
                                request.resourceAttributes() == null
                                        ? Map.of()
                                        : request.resourceAttributes(),
                                request.asOf(),
                                Map.of()));
        IamCheckResult attributes =
                service.check(
                        request.principal(),
                        request.permission(),
                        request.asOf(),
                        null,
                        provided,
                        state);
        return new IamAuthorizationDtos.CheckResponse(
                decision.allowed(),
                decision.reason(),
                decision.reasonCode(),
                attributes.attributeState().wireName());
    }

    /**
     * 拉取主体属性快照（仅服务身份）。
     *
     * @param principalId 主体
     * @param asOf 业务时点
     * @param recordedAsOf 系统时点
     * @return 快照或失败状态
     */
    @GetMapping("/principals/{principalId}/attributes")
    public ResponseEntity<?> attributes(
            @PathVariable("principalId") String principalId,
            @RequestParam("as_of") Instant asOf,
            @RequestParam(name = "recorded_as_of", required = false) Instant recordedAsOf) {
        if (!trustedService()) {
            return ResponseEntity.status(403)
                    .body(
                            Map.of(
                                    "reason_code", "ATTRIBUTE_CALLER_VIOLATION",
                                    "message", "trusted service permission required"));
        }
        AttributeLookup lookup = service.attributes(principalId, asOf, recordedAsOf);
        if (lookup.state() == AttributeState.MISSING) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "reason_code",
                                    "ATTRIBUTE_MISSING",
                                    "principal",
                                    principalId,
                                    "as_of",
                                    asOf.toString()));
        }
        if (lookup.state() == AttributeState.SOURCE_UNAVAILABLE) {
            return ResponseEntity.status(503)
                    .body(
                            Map.of(
                                    "reason_code",
                                    "ATTRIBUTE_SOURCE_UNAVAILABLE",
                                    "principal",
                                    principalId,
                                    "as_of",
                                    asOf.toString()));
        }
        AttributeSnapshot snapshot = lookup.snapshot();
        if (snapshot == null) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "reason_code",
                                    "ATTRIBUTE_MISSING",
                                    "principal",
                                    principalId,
                                    "as_of",
                                    asOf.toString()));
        }
        return ResponseEntity.ok(
                new IamAuthorizationDtos.AttributesResponse(
                        snapshot.principal(),
                        snapshot.asOf(),
                        snapshot.recordedAt(),
                        snapshot.expiresAt(),
                        snapshot.source(),
                        snapshot.sourceVersion(),
                        lookup.state().wireName(),
                        snapshot.snapshot()));
    }

    private boolean trustedService() {
        CallerProvider provider = callers.getIfAvailable();
        if (provider == null) {
            return false;
        }
        Caller caller = provider.find().orElse(null);
        return caller != null
                && (caller.hasPermission(ATTRIBUTES_PERMISSION)
                        || caller.hasPermission(IAM_ATTRIBUTES_PERMISSION));
    }

    private static AttributeState parseState(String value) {
        if (value == null || value.isBlank()) {
            return AttributeState.FRESH;
        }
        try {
            return AttributeState.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return AttributeState.MISSING;
        }
    }
}
