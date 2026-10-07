package cn.byteforce.seedj.starter.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 测试用校验服务（方法级约束，触发 {@code ConstraintViolationException} 路径）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Component
@Validated
public class ValidationService {

    /**
     * 校验入参。
     *
     * @param code 必填且非空
     * @return 原样返回
     */
    public String check(@NotBlank(message = "code 不能为空") String code) {
        return code;
    }
}
