package cn.byteforce.seedj.iam.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP with RFC 4648 Base32 secrets. HMAC-SHA1 is the interoperable
 * default; SHA256/SHA512 are supported for RPs that advertise them.
 */
public final class TotpGenerator {

    private TotpGenerator() {
    }

    public static String generate(byte[] secret, long epochSecond, int digits, int period,
                                  TotpAlgorithm algorithm) {
        long step = Math.floorDiv(epochSecond, period);
        byte[] hash = hmac(secret, step, algorithm);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int modulo = (int) Math.pow(10, digits);
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulo);
    }

    public static String generate(byte[] secret, Instant now, int digits, int period,
                                  TotpAlgorithm algorithm) {
        return generate(secret, now.getEpochSecond(), digits, period, algorithm);
    }

    /**
     * Verifies a code within {@code +/- window} steps and rejects replayed steps.
     * The comparison is constant-time for every candidate step.
     */
    public static Result verify(byte[] secret, String code, Instant now, int window,
                                int digits, int period, TotpAlgorithm algorithm,
                                long lastUsedStep) {
        String normalized = code == null ? "" : code.replace(" ", "").trim();
        if (normalized.length() != digits || !normalized.chars().allMatch(Character::isDigit)) {
            return Result.invalid();
        }
        long currentStep = Math.floorDiv(now.getEpochSecond(), period);
        for (int delta = -window; delta <= window; delta++) {
            long step = currentStep + delta;
            if (step < 0) {
                continue;
            }
            String candidate = generate(secret, step * period, digits, period, algorithm);
            if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
                    normalized.getBytes(StandardCharsets.US_ASCII))) {
                if (step <= lastUsedStep) {
                    return Result.replayed(step);
                }
                return Result.valid(step);
            }
        }
        return Result.invalid();
    }

    public static String otpauthUri(String issuer, String accountName, String base32Secret,
                                    int digits, int period, TotpAlgorithm algorithm) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + urlEncode(base32Secret)
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=" + algorithm.otpauthName()
                + "&digits=" + digits
                + "&period=" + period;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] hmac(byte[] secret, long step, TotpAlgorithm algorithm) {
        byte[] counter = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance(algorithm.hmacName());
            mac.init(new SecretKeySpec(secret, algorithm.hmacName()));
            return mac.doFinal(counter);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP HMAC unavailable: " + algorithm, e);
        }
    }

    public record Result(boolean valid, long step, boolean replayed) {

        static Result valid(long step) {
            return new Result(true, step, false);
        }

        static Result invalid() {
            return new Result(false, -1, false);
        }

        static Result replayed(long step) {
            return new Result(false, step, true);
        }
    }
}
