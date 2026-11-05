package cn.byteforce.seedj.iam.autoconfigure;

/**
 * Marker bean registered by {@code @EnableIamServer}.
 *
 * <p>It keeps the annotation a pure feature switch: IAM auto-configuration is
 * discovered through {@code AutoConfiguration.imports}, but only activates when
 * the application explicitly opted in with the annotation (or registered this
 * marker itself).</p>
 */
public final class IamEnabledMarker {
}
