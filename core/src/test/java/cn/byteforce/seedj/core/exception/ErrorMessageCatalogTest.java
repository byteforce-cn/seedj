package cn.byteforce.seedj.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 错误码目录中英文案不同且不返回空串。 */
class ErrorMessageCatalogTest {

    @Test
    @DisplayName("同一错误码在 EN/ZH 下文案不同")
    void englishAndChineseDiffer() {
        String en = ErrorMessageCatalog.message(ContractErrorCode.FORBIDDEN, Locale.ENGLISH);
        String zh = ErrorMessageCatalog.message(ContractErrorCode.FORBIDDEN, Locale.CHINA);

        assertThat(en).isNotBlank();
        assertThat(zh).isNotBlank();
        assertThat(en).isNotEqualTo(zh);
    }

    @Test
    @DisplayName("所有冻结错误码都有两份文案")
    void everyCodeHasBothLocales() {
        for (ContractErrorCode code : ContractErrorCode.values()) {
            assertThat(ErrorMessageCatalog.message(code, Locale.ENGLISH))
                    .as(code.name())
                    .isNotBlank();
            assertThat(ErrorMessageCatalog.message(code, Locale.CHINA))
                    .as(code.name())
                    .isNotBlank();
        }
    }
}
