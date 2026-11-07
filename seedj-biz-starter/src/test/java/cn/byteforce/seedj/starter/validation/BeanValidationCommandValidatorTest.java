package cn.byteforce.seedj.starter.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bean Validation → 命令无效载荷（400）的统一映射。 */
class BeanValidationCommandValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final BeanValidationCommandValidator commandValidator =
            new BeanValidationCommandValidator(validator);

    @Test
    @DisplayName("字段违规：INVALID_PAYLOAD，消息包含字段路径")
    void violationBecomesInvalidPayload() {
        assertThatThrownBy(() -> commandValidator.validate(new Command("")))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                    assertThat(error.reason()).contains("name").contains("must not be blank");
                });
    }

    @Test
    @DisplayName("合法载荷通过")
    void validPayloadPasses() {
        assertThatCode(() -> commandValidator.validate(new Command("Alice")))
                .doesNotThrowAnyException();
    }

    record Command(@NotBlank(message = "must not be blank") String name) {}
}
