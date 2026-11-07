package cn.byteforce.seedj.starter.iam.abac;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.infra.jdbc.iam.PolicyConditionCodec;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** ABAC policy management. Conditions are validated by decoding the typed AST. */
@RestController
@RequestMapping(path = "/iam/admin/policies", produces = MediaType.APPLICATION_JSON_VALUE)
public class IamPolicyAdminController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IamPolicyRepository policies;
    private final PolicyConditionCodec codec = new PolicyConditionCodec();
    private final IamProperties properties;
    private final IdGenerator<String> idGenerator;

    public IamPolicyAdminController(IamPolicyRepository policies, IamProperties properties,
                                    IdGenerator<String> idGenerator) {
        this.policies = policies;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return policies.findAll(properties.tenantId()).stream().map(this::map).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") String id) {
        return map(policies.findById(id).orElseThrow(() -> new IamException(
                IamErrorCode.POLICY_NOT_FOUND, "policy not found: " + id)));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        IamPolicy policy = policy(null, body);
        return map(policies.save(policy));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") String id,
                                      @RequestBody Map<String, Object> body) {
        IamPolicy current = policies.findById(id).orElseThrow(() -> new IamException(
                IamErrorCode.POLICY_NOT_FOUND, "policy not found: " + id));
        IamPolicy updated = policy(current, body);
        return map(policies.save(updated));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        policies.delete(id);
        return Map.of("deleted", id);
    }

    private IamPolicy policy(IamPolicy current, Map<String, Object> body) {
        String name = string(body.getOrDefault("name",
                current == null ? "policy" : current.name()));
        if (name == null || name.isBlank()) {
            throw new IamException(IamErrorCode.POLICY_INVALID, "policy name is required");
        }
        IamPolicyEffect effect = effect(string(body.getOrDefault("effect",
                current == null ? "DENY" : current.effect().name())));
        int priority = priority(body, current);
        java.util.Set<String> actions = body.containsKey("actions")
                ? strings(body.get("actions"))
                : current == null ? java.util.Set.of() : current.actions();
        java.util.Set<String> resources = body.containsKey("resources")
                ? strings(body.get("resources"))
                : current == null ? java.util.Set.of() : current.resources();
        PolicyCondition condition;
        if (body.containsKey("condition")) {
            try {
                condition = codec.decode(JSON.writeValueAsString(body.get("condition")));
            } catch (RuntimeException e) {
                throw new IamException(IamErrorCode.POLICY_INVALID,
                        "invalid policy condition: " + e.getMessage());
            }
        } else {
            condition = current == null ? PolicyCondition.Always.INSTANCE : current.condition();
        }
        boolean enabled = body.containsKey("enabled")
                ? Boolean.parseBoolean(String.valueOf(body.get("enabled")))
                : current == null || current.enabled();
        try {
            return new IamPolicy(current == null ? idGenerator.id() : current.id(), properties.tenantId(),
                    name, effect, priority, actions, resources, condition, enabled,
                    current == null ? 0 : current.version());
        } catch (RuntimeException e) {
            throw new IamException(IamErrorCode.POLICY_INVALID,
                    "invalid policy: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> map(IamPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", policy.id());
        value.put("name", policy.name());
        value.put("effect", policy.effect().name());
        value.put("priority", policy.priority());
        value.put("actions", new ArrayList<>(policy.actions()));
        value.put("resources", new ArrayList<>(policy.resources()));
        value.put("condition", JSON.readTree(codec.encode(policy.condition())));
        value.put("enabled", policy.enabled());
        value.put("version", policy.version());
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static IamPolicyEffect effect(String value) {
        try {
            return IamPolicyEffect.valueOf(value == null ? "DENY" : value.toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new IamException(IamErrorCode.POLICY_INVALID,
                    "invalid policy effect: " + value);
        }
    }

    private static int priority(Map<String, Object> body, IamPolicy current) {
        if (!body.containsKey("priority")) {
            return current == null ? 0 : current.priority();
        }
        Object value = body.get("priority");
        if (!(value instanceof Number number)) {
            throw new IamException(IamErrorCode.POLICY_INVALID,
                    "policy priority must be a number");
        }
        return number.intValue();
    }

    private static java.util.Set<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return java.util.Set.of();
        }
        java.util.Set<String> result = new LinkedHashSet<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }
}
