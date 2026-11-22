package cn.byteforce.seedj.iam;

/**
 * Marker bean registered by {@code @EnableIamServer}.
 *
 * <p>It keeps the annotation a pure feature switch: IAM auto-configuration is discovered through
 * {@code AutoConfiguration.imports}, but only activates when the application explicitly opted in
 * with the annotation (or registered this marker itself). Adapter modules gate their store
 * auto-configuration on this same marker, so an application that ships an adapter without enabling
 * IAM still gets no IAM beans.
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamEnabledMarker {}
