package cn.byteforce.seedj.iam.policy;

import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;

import cn.byteforce.seedj.iam.PolicyCondition;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamResourceAttributeResolver;
import cn.byteforce.seedj.id.IdGenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default PDP: DENY wins, no matching ALLOW means deny, and policies are evaluated
 * highest priority first. It only understands the typed {@link PolicyCondition}
 * AST; richer policy engines plug in as another {@link IamPolicyDecisionPoint}.
 */
public class DefaultIamPolicyDecisionPoint implements IamPolicyDecisionPoint {

    private final IamPolicyRepository policies;
    private final List<IamResourceAttributeResolver> resolvers;
    private final SecurityAuditSink auditSink;
    private final IdGenerator<String> idGenerator;

    public DefaultIamPolicyDecisionPoint(IamPolicyRepository policies,
                                         List<IamResourceAttributeResolver> resolvers,
                                         SecurityAuditSink auditSink,
                                         IdGenerator<String> idGenerator) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.resolvers = resolvers == null ? List.of() : List.copyOf(resolvers);
        this.auditSink = auditSink;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public IamAuthorizationDecision decide(IamAuthorizationRequest request) {
        Map<String, Object> resourceAttributes = resolveResource(request);
        IamAuthorizationRequest resolved = request.withResourceAttributes(resourceAttributes);

        List<IamPolicy> ordered = new ArrayList<>(policies.findEnabled(request.subject().tenantId()));
        ordered.sort(Comparator.comparingInt(IamPolicy::priority).reversed());
        String deniedBy = null;
        String allowedBy = null;

        for (IamPolicy policy : ordered) {
            boolean resourceMatches = matches(policy.resources(), request.resourceType())
                    || matches(policy.resources(), request.resourceType() + ":" + request.resourceId())
                    || matches(policy.resources(), request.resourceId());
            if (!matches(policy.actions(), request.action()) || !resourceMatches) {
                continue;
            }
            boolean condition = evaluate(policy.condition(), resolved);
            if (!condition) {
                continue;
            }
            if (policy.effect() == IamPolicyEffect.DENY) {
                deniedBy = policy.id();
                break;
            }
            if (allowedBy == null) {
                allowedBy = policy.id();
            }
        }

        if (deniedBy != null) {
            audit(request, "DENY", deniedBy);
            return IamAuthorizationDecision.deny("denied by policy", deniedBy);
        }
        if (allowedBy != null) {
            return IamAuthorizationDecision.allow(allowedBy);
        }
        audit(request, "DENY", null);
        return IamAuthorizationDecision.deny("no matching allow policy");
    }

    private Map<String, Object> resolveResource(IamAuthorizationRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("type", request.resourceType());
        attributes.put("id", request.resourceId());
        for (IamResourceAttributeResolver resolver : resolvers) {
            if (resolver.supports(request.resourceType())) {
                Map<String, Object> resolved = resolver.resolve(
                        request.resourceType(), request.resourceId(), request.subject());
                if (resolved != null) {
                    attributes.putAll(resolved);
                }
            }
        }
        // Explicit attributes win: callers may pass an already-resolved snapshot,
        // and tests/previews must be able to override a resolver.
        attributes.putAll(request.resourceAttributes());
        return attributes;
    }

    private boolean evaluate(PolicyCondition condition, IamAuthorizationRequest request) {
        if (condition == PolicyCondition.Always.INSTANCE) {
            return true;
        }
        if (condition instanceof PolicyCondition.And and) {
            return and.conditions().stream().allMatch(c -> evaluate(c, request));
        }
        if (condition instanceof PolicyCondition.Or or) {
            return or.conditions().stream().anyMatch(c -> evaluate(c, request));
        }
        if (condition instanceof PolicyCondition.Not not) {
            return !evaluate(not.condition(), request);
        }
        if (condition instanceof PolicyCondition.Exists exists) {
            return resolve(exists.operand(), request) != null;
        }
        if (condition instanceof PolicyCondition.Compare compare) {
            Object left = resolve(compare.left(), request);
            Object right = resolve(compare.right(), request);
            return compare(compare.operator(), left, right);
        }
        return false;
    }

    private Object resolve(PolicyOperand operand, IamAuthorizationRequest request) {
        IamSubject subject = request.subject();
        return switch (operand.source()) {
            case LITERAL -> operand.literal();
            case ACTION -> request.action();
            case RESOURCE -> request.resourceAttributes().get(operand.path());
            case ENVIRONMENT -> request.environment().get(operand.path());
            case SUBJECT -> switch (operand.path()) {
                case "userId", "sub" -> subject.userId();
                case "username" -> subject.username();
                case "tenantId", "tenant_id" -> subject.tenantId();
                case "roles" -> subject.roles();
                case "permissions" -> subject.permissions();
                case "groups" -> subject.groups();
                case "amr" -> subject.amr();
                case "acr" -> subject.acr();
                default -> subject.attributes().get(operand.path());
            };
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean compare(PolicyComparisonOperator operator, Object left, Object right) {
        if (operator == PolicyComparisonOperator.EQ) {
            return equal(left, right);
        }
        if (operator == PolicyComparisonOperator.NE) {
            return !equal(left, right);
        }
        if (operator == PolicyComparisonOperator.IN) {
            if (right instanceof Collection<?> collection) {
                return collection.stream().anyMatch(candidate -> equal(left, candidate));
            }
            if (right instanceof String text && left != null) {
                return text.contains(String.valueOf(left));
            }
            return false;
        }
        if (operator == PolicyComparisonOperator.CONTAINS) {
            if (left instanceof Collection<?> collection) {
                return collection.stream().anyMatch(candidate -> equal(candidate, right));
            }
            return left != null && right != null && String.valueOf(left).contains(String.valueOf(right));
        }
        if (operator == PolicyComparisonOperator.STARTS_WITH) {
            return left != null && right != null && String.valueOf(left).startsWith(String.valueOf(right));
        }
        if (operator == PolicyComparisonOperator.ENDS_WITH) {
            return left != null && right != null && String.valueOf(left).endsWith(String.valueOf(right));
        }
        if (left == null || right == null) {
            return false;
        }
        int compared = compareValues(left, right);
        return switch (operator) {
            case GT -> compared > 0;
            case LT -> compared < 0;
            case GE -> compared >= 0;
            case LE -> compared <= 0;
            default -> false;
        };
    }

    private static boolean equal(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue()) == 0;
        }
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(candidate -> equal(candidate, right));
        }
        return left.equals(right) || String.valueOf(left).equals(String.valueOf(right));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue());
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private void audit(IamAuthorizationRequest request, String outcome, String policyId) {
        if (auditSink == null) {
            return;
        }
        try {
            auditSink.record(new SecurityAuditEvent(idGenerator.id(), request.subject().tenantId(),
                    request.subject().userId(), "abac." + request.action(),
                    request.resourceType(), request.resourceId(), outcome,
                    policyId == null ? "no matching policy" : "policy " + policyId,
                    java.time.Instant.now(), Map.of("action", request.action())));
        } catch (RuntimeException ignored) {
            // auditing must never change the authorization outcome
        }
    }

    static boolean matches(Collection<String> patterns, String value) {
        for (String pattern : patterns) {
            if (pattern.equals("*") || pattern.equals(value)) {
                return true;
            }
            if (pattern.endsWith("*") && value.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
