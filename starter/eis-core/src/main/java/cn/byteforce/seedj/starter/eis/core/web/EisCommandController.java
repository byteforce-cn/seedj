package cn.byteforce.seedj.starter.eis.core.web;

import cn.byteforce.seedj.starter.eis.core.EisActorResolver;
import cn.byteforce.seedj.starter.eis.core.EisCommandRequest;
import cn.byteforce.seedj.starter.eis.core.EisCommandResult;
import cn.byteforce.seedj.starter.eis.core.EisCommandRejectionDetail;
import cn.byteforce.seedj.starter.eis.core.EisCommandStore;
import cn.byteforce.seedj.starter.eis.core.EisCommandRuntime;
import cn.byteforce.seedj.starter.eis.core.EisRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * EIS 命令端点（{@code 02-operation-contract/command.openapi.yaml}）：
 * {@code POST /commands/{commandType}} 与 {@code GET /commands/{id}}。
 *
 * <p>响应体为契约形状（命令端点不经 {@code ApiResponse} 包裹——契约优先）；
 * 状态码：202 受理 / 400·403·404·409 拒绝（按错误码的 HTTP 段）/ 409 幂等冲突。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public class EisCommandController {

    private final EisCommandRuntime runtime;

    private final EisActorResolver actorResolver;

    /**
     * 构造命令端点。
     *
     * @param runtime       命令运行时
     * @param actorResolver 操作人解析
     */
    public EisCommandController(EisCommandRuntime runtime, EisActorResolver actorResolver) {
        this.runtime = runtime;
        this.actorResolver = actorResolver;
    }

    /**
     * 提交命令。
     *
     * @param commandType 路径命令类型
     * @param body        请求体（{@code type / payload / idempotency_key / targets}）
     * @return 契约形状响应
     */
    @PostMapping("/commands/{commandType}")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable("commandType") String commandType,
                                                      @RequestBody JsonNode body) {
        UUID actor = actorResolver.currentActor().orElseThrow(EisRequestException::unauthenticated);
        String type = textField(body, "type");
        if (!commandType.equals(type)) {
            throw EisRequestException.invalidPayload("路径命令类型与请求体 type 不一致");
        }
        JsonNode payload = body == null ? null : body.get("payload");
        String idempotencyKey = textField(body, "idempotency_key");
        List<UUID> targets = targets(body);
        EisCommandResult result = runtime.submit(
                new EisCommandRequest(type, payload, idempotencyKey, targets, actor));
        return respond(result, idempotencyKey);
    }

    /**
     * 查询命令终态。
     *
     * @param id 命令 ID
     * @return 200 命令状态 / 404 不存在
     */
    @GetMapping("/commands/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        UUID commandId;
        try {
            commandId = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw EisRequestException.invalidPayload("命令 ID 非法: " + id);
        }
        Optional<EisCommandStore.EisStoredCommand> stored = runtime.findCommand(commandId);
        if (stored.isEmpty()) {
            throw EisRequestException.notFound("命令不存在: " + id);
        }
        return ResponseEntity.ok(statusBody(stored.get()));
    }

    private ResponseEntity<Map<String, Object>> respond(EisCommandResult result, String idempotencyKey) {
        if (EisCommandResult.STATUS_CONFLICT.equals(result.getStatus())) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("idempotency_key", idempotencyKey);
            conflict.put("original_command_id", result.getCommandId());
            runtime.findCommand(result.getCommandId())
                    .ifPresent(original -> conflict.put("original_result", statusBody(original)));
            return ResponseEntity.status(409).body(conflict);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", result.getCommandId());
        body.put("status", result.getStatus());
        if ("accepted".equals(result.getStatus())) {
            body.put("event_ids", stringIds(result.getEventIds()));
            if (result.getCreated() != null) {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("type", result.getCreated().getObjectType());
                created.put("id", result.getCreated().getId());
                body.put("created", created);
            }
            if (result.isReplayed()) {
                body.put("replayed", true);
            }
            return ResponseEntity.status(202).body(body);
        }
        body.put("reason", result.getReason());
        body.put("code", result.getCode());
        if (result.getReasonCode() != null) {
            body.put("reason_code", result.getReasonCode());
        }
        if (!result.getDetails().isEmpty()) {
            List<Map<String, Object>> details = new ArrayList<>(result.getDetails().size());
            for (EisCommandRejectionDetail detail : result.getDetails()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("field", detail.getField());
                item.put("message", detail.getMessage());
                details.add(item);
            }
            body.put("details", details);
        }
        if (result.isReplayed()) {
            body.put("replayed", true);
        }
        return ResponseEntity.status(httpStatusOf(result.getCode())).body(body);
    }

    private static Map<String, Object> statusBody(EisCommandStore.EisStoredCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", command.getId());
        body.put("status", command.getStatus());
        body.put("event_ids", stringIds(command.getEventIds()));
        if (command.getRejectionReason() != null) {
            body.put("reason", command.getRejectionReason());
        }
        if (command.getRejectionCode() != null) {
            body.put("code", command.getRejectionCode());
        }
        if (command.getCreated() != null) {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("type", command.getCreated().getObjectType());
            created.put("id", command.getCreated().getId());
            body.put("created", created);
        }
        body.put("issued_at", command.getIssuedAt());
        return body;
    }

    private static List<String> stringIds(List<UUID> values) {
        List<String> ids = new ArrayList<>(values.size());
        for (UUID value : values) {
            ids.add(value.toString());
        }
        return ids;
    }

    private static int httpStatusOf(String code) {
        if (code != null) {
            String[] segments = code.split("-");
            if (segments.length >= 2) {
                try {
                    return Integer.parseInt(segments[1]);
                } catch (NumberFormatException ignored) {
                    // 落入缺省
                }
            }
        }
        return 400;
    }

    private static String textField(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw EisRequestException.invalidPayload("缺少或非法字段: " + field);
        }
        return node.stringValue();
    }

    private static List<UUID> targets(JsonNode body) {
        JsonNode node = body == null ? null : body.get("targets");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw EisRequestException.invalidPayload("targets 必须为数组");
        }
        List<UUID> targets = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isString()) {
                throw EisRequestException.invalidPayload("targets 元素必须为 UUID 字符串");
            }
            try {
                targets.add(UUID.fromString(item.stringValue()));
            } catch (IllegalArgumentException ex) {
                throw EisRequestException.invalidPayload("targets 元素非法: " + item.stringValue());
            }
        }
        return targets;
    }
}
