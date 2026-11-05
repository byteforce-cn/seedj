package cn.byteforce.seedj.iam.totp;

/** Enrollment material returned exactly once, before confirmation. */
public record TotpEnrollment(String secret, String otpauthUri) {
}
