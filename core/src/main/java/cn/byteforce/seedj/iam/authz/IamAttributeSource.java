package cn.byteforce.seedj.iam.authz;

import java.time.Instant;

/**
 * 属性快照拉取 SPI（规范路径 ATR-002）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface IamAttributeSource {

    AttributeLookup lookup(String principal, Instant asOf, Instant recordedAsOf);

    static IamAttributeSource none() {
        return (principal, asOf, recordedAsOf) -> AttributeLookup.missing();
    }
}
