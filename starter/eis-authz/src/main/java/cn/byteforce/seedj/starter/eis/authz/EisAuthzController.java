package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import cn.byteforce.seedj.starter.eis.core.EisRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EIS 授权解析端点（{@code 04-authz-contract/resolution.api.yaml}）：
 * {@code POST /resolve} 与 {@code POST /check}。
 *
 * <p>作为 IAM 服务的"授权解析权威"对外提供；网关与其他服务经 {@link EisAuthzClient}
 * （+ 进程内 TTL 缓存）调用。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public class EisAuthzController {

    private final EisAuthzOperations operations;

    /**
     * 构造端点。
     *
     * @param operations 授权操作面（本地引擎或缓存客户端）
     */
    public EisAuthzController(EisAuthzOperations operations) {
        this.operations = operations;
    }

    /**
     * 解析主体在时点的有效角色与权限。
     *
     * @param body 请求体（principal / as_of / recorded_as_of? / scope?）
     * @return 角色、权限与 SoD 告警
     */
    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestBody JsonNode body) {
        String principal = requireText(body, "principal");
        String asOfText = requireText(body, "as_of");
        Instant asOf = parseTime(asOfText);
        Instant recordedAsOf = parseTime(optionalText(body, "recorded_as_of"));
        JsonNode scope = body.get("scope");
        EisAuthzResolution resolution = operations.resolve(principal, asOf, recordedAsOf, scope);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("principal", resolution.getPrincipal());
        response.put("as_of", asOfText);
        response.put("roles", resolution.getRoles());
        response.put("permissions", resolution.getPermissions());
        response.put("sod_warnings", resolution.getSodWarnings());
        return ResponseEntity.ok(response);
    }

    /**
     * 检查权限。
     *
     * @param body 请求体（principal / permission / as_of / recorded_as_of?）
     * @return allowed + reason + reason_code（+ warnings）
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody JsonNode body) {
        String principal = requireText(body, "principal");
        String permission = requireText(body, "permission");
        String asOfText = requireText(body, "as_of");
        Instant asOf = parseTime(asOfText);
        Instant recordedAsOf = parseTime(optionalText(body, "recorded_as_of"));
        EisAuthzDecision decision = operations.check(principal, permission, asOf, recordedAsOf);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allowed", decision.isAllowed());
        response.put("reason", decision.getReason());
        response.put("reason_code", decision.getReasonCode());
        response.put("warnings", decision.getWarnings());
        return ResponseEntity.ok(response);
    }

    private static String requireText(JsonNode body, String field) {
        String value = optionalText(body, field);
        if (value == null || value.isBlank()) {
            throw EisRequestException.invalidPayload("缺少或非法字段: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw EisRequestException.invalidPayload("字段必须为字符串: " + field);
        }
        return node.stringValue();
    }

    /**
     * 时间解析（宽松）：支持 {@code Z}/偏移/RFC3339；无偏移按 UTC。
     *
     * @param value 时间文本
     * @return 时刻；空输入为 {@code null}
     */
    static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试偏移/本地形式
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试无偏移（按 UTC）
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw EisRequestException.invalidPayload("时间参数非法: " + value);
        }
    }
}
