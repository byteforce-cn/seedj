package cn.byteforce.seedj.iam;

/** Result of an ABAC evaluation. */
public record IamAuthorizationDecision(boolean allowed, String reason, String policyId) {

    public static IamAuthorizationDecision allow(String policyId) {
        return new IamAuthorizationDecision(true, "allowed", policyId);
    }

    public static IamAuthorizationDecision deny(String reason) {
        return new IamAuthorizationDecision(false, reason, null);
    }

    public static IamAuthorizationDecision deny(String reason, String policyId) {
        return new IamAuthorizationDecision(false, reason, policyId);
    }
}
