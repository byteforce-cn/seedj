package cn.byteforce.seedj.iam.spi;

import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;

/** ABAC enforcement seam. Implementations may delegate to OPA/Cedar. */
public interface IamPolicyDecisionPoint {

    IamAuthorizationDecision decide(IamAuthorizationRequest request);
}
