package cn.byteforce.seedj.iam.spi;

/** Immediately invalidates all refresh/authorization records for a principal. */
public interface IamTokenRevoker {

    void revokeAllForUser(String tenantId, String principalName);
}
