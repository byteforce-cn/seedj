package cn.byteforce.seedj.cqrs.web;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 命令 HTTP 面。
 *
 * <p>命令响应只表达执行结果（{@code locator + version}），不携带读模型投影；
 * 业务拒绝是 400 {@code CommandRejected}，幂等冲突是 409
 * {@code IdempotencyConflict}，其余契约错误交给全局错误处理器。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public final class CommandController {

    private final CommandCatalog catalog;

    private final CommandStore commands;

    private final CallerProvider callers;

    /**
     * 构造控制器。
     *
     * @param catalog  命令注册表
     * @param commands 命令记录存储
     * @param callers  当前调用方
     */
    public CommandController(CommandCatalog catalog, CommandStore commands,
                             CallerProvider callers) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    /**
     * 提交命令。
     *
     * @param commandType 路径中的命令类型
     * @param request     请求体
     * @return 202 接受 / 400 拒绝 / 409 幂等冲突
     */
    @PostMapping("/commands/{commandType}")
    public ResponseEntity<?> submit(@PathVariable String commandType,
                                    @RequestBody CommandRequest request) {
        try {
            validateRequest(commandType, request);
            CommandSubmission submission;
            try {
                submission = new CommandSubmission(commandType, request.payload(),
                        request.idempotencyKey(), request.targets(), issuer());
            } catch (IllegalArgumentException error) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        messageOf(error, "invalid command request"));
            }
            CommandReceipt receipt = catalog.execute(submission);
            return switch (receipt.status()) {
                case ACCEPTED -> ResponseEntity.status(202).body(accepted(receipt));
                case REJECTED -> ResponseEntity.status(400).body(rejected(receipt));
                case PENDING -> throw new ContractException(ContractErrorCode.UNAVAILABLE,
                        "command is still pending");
            };
        } catch (ContractException error) {
            if (error.code() == ContractErrorCode.IDEMPOTENCY_CONFLICT) {
                return ResponseEntity.status(409).body(conflict(error));
            }
            if (error.code() == ContractErrorCode.UNAUTHENTICATED
                    || error.code() == ContractErrorCode.UNAVAILABLE) {
                throw error;
            }
            return ResponseEntity.status(400).body(rejected(error));
        }
    }

    /**
     * 查询命令终态。
     *
     * @param id 命令 ID
     * @return 200 命令状态 / 404 不存在
     */
    @GetMapping("/commands/{id}")
    public ResponseEntity<?> status(@PathVariable UUID id) {
        CommandRecord record = commands.find(id)
                .orElseThrow(() -> new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                        "command not found"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", record.id().toString());
        body.put("status", record.status().wireName());
        body.put("event_ids", record.eventIds().stream().map(UUID::toString).toList());
        if (record.rejectionReason() != null) {
            body.put("reason", record.rejectionReason());
        }
        if (record.rejectionCode() != null) {
            body.put("code", record.rejectionCode());
        }
        body.put("issued_at", record.issuedAt().toString());
        return ResponseEntity.ok(body);
    }

    private static void validateRequest(String commandType, CommandRequest request) {
        if (request == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command request body is required");
        }
        if (request.type() == null || request.type().isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command type is required");
        }
        if (!commandType.equals(request.type())) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command type mismatch: path " + commandType + ", body " + request.type());
        }
        if (request.payload() == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "command payload is required");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "idempotency_key is required");
        }
    }

    private UUID issuer() {
        Caller caller = callers.find()
                .orElseThrow(() -> new ContractException(ContractErrorCode.UNAUTHENTICATED,
                        "authentication required"));
        try {
            return UUID.fromString(caller.id());
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.UNAUTHENTICATED,
                    "caller id is not a UUID");
        }
    }

    private static Map<String, Object> accepted(CommandReceipt receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", receipt.commandId().toString());
        body.put("status", "accepted");
        body.put("event_ids", receipt.eventIds().stream().map(UUID::toString).toList());
        body.put("locator", receipt.locator().toString());
        body.put("version", receipt.version());
        return body;
    }

    private static Map<String, Object> rejected(CommandReceipt receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", receipt.commandId().toString());
        body.put("status", "rejected");
        body.put("reason", receipt.reason());
        body.put("code", receipt.code());
        return body;
    }

    private static Map<String, Object> rejected(ContractException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        Object commandId = error.details().get("command_id");
        if (commandId != null) {
            body.put("command_id", commandId);
        }
        body.put("status", "rejected");
        body.put("reason", error.reason());
        body.put("code", error.code().getCode());
        return body;
    }

    private static Map<String, Object> conflict(ContractException error) {
        Map<String, Object> details = error.details();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotency_key", details.get("idempotency_key"));
        body.put("original_command_id", details.get("original_command_id"));
        body.put("original_result", details.get("original_result"));
        return body;
    }

    private static String messageOf(IllegalArgumentException error, String fallback) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? fallback : error.getMessage();
    }
}
