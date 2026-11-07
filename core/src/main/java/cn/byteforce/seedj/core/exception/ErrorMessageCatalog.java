package cn.byteforce.seedj.core.exception;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * 错误码的本地化文案目录。
 *
 * <p>格式参数为错误码本身；EN/ZH 两份资源由 {@code seedj-errors*.properties} 提供。
 * 文案缺失时回落到错误码的英文 label，绝不返回空串。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ErrorMessageCatalog {

    private static final String BUNDLE = "seedj-errors";

    private ErrorMessageCatalog() {
    }

    /**
     * 取本地化文案。
     *
     * @param code   错误码
     * @param locale 语言；{@code null} 使用默认 Locale
     * @return 文案
     */
    public static String message(ContractErrorCode code, Locale locale) {
        Locale target = locale == null ? Locale.getDefault() : locale;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, target);
            return bundle.getString(code.name());
        } catch (MissingResourceException missing) {
            return code.getLabel();
        }
    }
}
