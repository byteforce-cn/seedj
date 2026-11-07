package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamSubject;
import java.util.Map;

/**
 * Business-owned resource attribute seam. Without a resolver the PDP can only use token claims, so
 * ownership/department checks need one.
 */
public interface IamResourceAttributeResolver {

    boolean supports(String resourceType);

    Map<String, Object> resolve(String resourceType, String resourceId, IamSubject subject);
}
