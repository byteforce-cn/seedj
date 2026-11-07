package cn.byteforce.seedj.starter.autoconfigure;

/**
 * Marker bean registered by {@code @EnableBizServer}.
 *
 * <p>Keeps the annotation a pure feature switch: auto-configuration is discovered through {@code
 * AutoConfiguration.imports} but only activates when the application explicitly opted in.
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class BizEnabledMarker {}
