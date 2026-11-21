package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationRequest;

/**
 * ABAC enforcement seam. Implementations may delegate to OPA/Cedar.
 *
 * <p>输入输出都是 {@code cn.byteforce.seedj.security} 的规范授权类型：命令、读路径、字段策略与
 * {@code /check} 共用同一套记录，不再有 IAM 专用副本。
 */
public interface IamPolicyDecisionPoint {

    AuthorizationDecision decide(AuthorizationRequest request);
}
