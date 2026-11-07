package cn.byteforce.seedj.starter.iam.autoconfigure;

import cn.byteforce.seedj.starter.iam.web.IamExceptionHandler;
import cn.byteforce.seedj.starter.iam.web.IamMfaController;
import cn.byteforce.seedj.starter.iam.web.IamTotpController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers IAM web endpoints even when the application scans another package. */
@Configuration(proxyBeanMethods = false)
@Import(IamExceptionHandler.class)
public class IamWebConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "seedj.iam.mfa.totp",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @Import({IamMfaController.class, IamTotpController.class})
    static class TotpWebConfiguration {}
}
