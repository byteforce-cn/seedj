package cn.byteforce.seedj.starter.validation;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.CommandValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用 Jakarta Bean Validation 校验命令载荷。
 *
 * <p>失败统一表达为无效载荷（400），消息包含字段路径，便于调用方定位；校验在锁与事务之前 执行，与 {@link
 * cn.byteforce.seedj.cqrs.CommandRuntime} 的顺序契约一致。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class BeanValidationCommandValidator implements CommandValidator {

    private final Validator validator;

    /**
     * 构造校验器。
     *
     * @param validator Bean Validation 校验器
     */
    public BeanValidationCommandValidator(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public void validate(Object command) {
        Set<ConstraintViolation<Object>> violations = validator.validate(command);
        if (violations.isEmpty()) {
            return;
        }
        String message =
                violations.stream()
                        .sorted(
                                Comparator.comparing(
                                        violation -> violation.getPropertyPath().toString()))
                        .map(
                                violation ->
                                        violation.getPropertyPath() + ": " + violation.getMessage())
                        .collect(Collectors.joining("; "));
        throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, message);
    }
}
