package cn.byteforce.seedj.iam;


/** Paged user query. {@code startIndex} is 1-based, matching SCIM. */
public record IamUserQuery(
        String tenantId,
        ScimFilter filter,
        int startIndex,
        int count) {

    public IamUserQuery {
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        startIndex = Math.max(1, startIndex);
        count = count < 0 ? 100 : Math.min(count, 1000);
    }

    public static IamUserQuery all(String tenantId) {
        return new IamUserQuery(tenantId, null, 1, 1000);
    }
}
