package cn.byteforce.seedj.iam;


/** Paged group query. {@code startIndex} is 1-based, matching SCIM. */
public record IamGroupQuery(
        String tenantId,
        ScimFilter filter,
        int startIndex,
        int count) {

    public IamGroupQuery {
        tenantId = tenantId == null || tenantId.isBlank() ? IamUser.DEFAULT_TENANT : tenantId;
        startIndex = Math.max(1, startIndex);
        count = count < 0 ? 100 : Math.min(count, 1000);
    }

    public static IamGroupQuery all(String tenantId) {
        return new IamGroupQuery(tenantId, null, 1, 1000);
    }
}
