package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/** coord PKI 的名称/序列号规范化与校验。只做格式判断，不碰网络。 */
final class CoordPkiNames {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern DNS_SAFE = Pattern.compile("[^a-z0-9.-]");

    private CoordPkiNames() {}

    static String commonName(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "subject must not be blank");
        }
        if (!subject.contains("=")) {
            return subject;
        }
        try {
            for (Rdn rdn : new LdapName(subject).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    String value = String.valueOf(rdn.getValue());
                    if (value.isBlank()) {
                        break;
                    }
                    return value;
                }
            }
        } catch (Exception e) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "cannot parse subject: " + subject, e);
        }
        throw new PkiException(
                PkiErrorCode.INVALID_REQUEST, "subject has no CN component: " + subject);
    }

    static String requireSerial(BigInteger serialNumber) {
        if (serialNumber == null || serialNumber.signum() < 0) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "serialNumber 不合法");
        }
        return serialNumber.toString(16);
    }

    static BigInteger parseSerial(String serial) {
        try {
            return new BigInteger(serial, 16);
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    static void requireName(String value, String field) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST,
                    field + " 必须匹配 " + NAME.pattern() + ": " + value);
        }
    }

    static void requireDefaultIssuer(String issuerRef) {
        if (issuerRef != null && !issuerRef.isBlank() && !"default".equalsIgnoreCase(issuerRef)) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST,
                    "coord PKI has a single issuer; only 'default' is accepted: " + issuerRef);
        }
    }

    static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * coord 在未显式传 SAN 时会把 CN 派生为 DNS SAN，CN 不是合法 DNS 名就拒绝签发。适配器永远显式传
     * SAN，所以任意 alias 都能签出来；这里只负责把 CN 归一化成合法的 DNS 标签。
     */
    static String dnsName(String cn) {
        String normalized = DNS_SAFE.matcher(cn.toLowerCase(Locale.ROOT)).replaceAll("-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "").replaceAll("\\.+$", "");
        if (normalized.isEmpty()) {
            normalized = "seedj-identity";
        }
        return normalized.length() > 63 ? normalized.substring(0, 63) : normalized;
    }
}
