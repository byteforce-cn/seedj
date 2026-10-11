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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EIS 授权解析端点（{@code 04-authz-contract/resolution.api.yaml}）：
 * {@code POST /resolve} 与 {@code POST /check}。
 *
 * <p>作为 IAM 服务的"授权解析权威"对外提供；网关与其他服务经 {@link EisAuthzClient}
 * （+ 进程内 TTL 缓存）调用。</p>
 *
 * <p>属性条件（ADR-0004，seedj 加法）：{@code /check} 接受契约可选字段 {@code attributes}
 * （调用方已拉取的快照）与 seedj 扩展字段 {@code require_attribute}
 * （形如 {@code [{"key":"primary_position.grade","values":["P5"]}]}）；求值结果以
 * {@code attribute_state} 回传（新增字段，缺省不出现——无属性调用响应不变）。</p>
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
        EisAuthzAttributeSnapshot provided = attributeContext(principal, asOf, recordedAsOf, body);
        EisAuthzResolution resolution = operations.resolve(principal, asOf, recordedAsOf, scope);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("principal", resolution.getPrincipal());
        response.put("as_of", asOfText);
        response.put("roles", resolution.getRoles());
        response.put("permissions", resolution.getPermissions());
        response.put("sod_warnings", resolution.getSodWarnings());
        if (provided != null && provided.getState() != null) {
            response.put("attribute_state", provided.getState().getCode());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 检查权限（支持属性条件）。
     *
     * @param body 请求体（principal / permission / as_of / recorded_as_of? / attributes? / require_attribute?）
     * @return allowed + reason + reason_code（+ warnings / attribute_state）
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody JsonNode body) {
        String principal = requireText(body, "principal");
        String permission = requireText(body, "permission");
        String asOfText = requireText(body, "as_of");
        Instant asOf = parseTime(asOfText);
        Instant recordedAsOf = parseTime(optionalText(body, "recorded_as_of"));
        EisAuthzAttributeSnapshot provided = attributeContext(principal, asOf, recordedAsOf, body);
        List<EisAuthzAttributeCondition> conditions = conditions(body);
        EisAuthzDecision decision = conditions.isEmpty() && provided == null
                ? operations.check(principal, permission, asOf, recordedAsOf)
                : operations.check(principal, permission, asOf, recordedAsOf, conditions, provided);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allowed", decision.isAllowed());
        response.put("reason", decision.getReason());
        response.put("reason_code", decision.getReasonCode());
        response.put("warnings", decision.getWarnings());
        if (decision.getAttributeState() != null) {
            response.put("attribute_state", decision.getAttributeState().getCode());
        }
        return ResponseEntity.ok(response);
    }

    private static EisAuthzAttributeSnapshot attributeContext(String principal, Instant asOf,
                                                              Instant recordedAsOf, JsonNode body) {
        try {
            return EisAuthzAttributeSnapshot.fromAttributeContext(principal, asOf, recordedAsOf,
                    body == null ? null : body.get("attributes"));
        } catch (IllegalArgumentException ex) {
            throw EisRequestException.invalidPayload("attributes 非法: " + ex.getMessage());
        }
    }

    private static List<EisAuthzAttributeCondition> conditions(JsonNode body) {
        JsonNode node = body == null ? null : body.get("require_attribute");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw EisRequestException.invalidPayload("require_attribute 必须为数组");
        }
        List<EisAuthzAttributeCondition> conditions = new ArrayList<>();
        for (JsonNode item : node) {
            try {
                conditions.add(EisAuthzAttributeCondition.fromJson(item));
            } catch (IllegalArgumentException ex) {
                throw EisRequestException.invalidPayload("require_attribute 非法: " + ex.getMessage());
            }
        }
        return conditions;
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
