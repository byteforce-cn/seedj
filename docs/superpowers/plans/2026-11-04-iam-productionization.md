# IAM Productionization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for task-by-task implementation.

**Goal:** Turn the OIDC demo shell into a production-capable IAM: persistent users/clients/authorizations, JWK rotation, RBAC, TOTP, SCIM 2.0, ABAC, and a first-party native-auth grant, all behind one starter and one annotation.

**Architecture:** Keep `core` Spring-free and add IAM contracts there. Memory and PostgreSQL adapters implement the same SPI contract tests. A new `seedj-iam-starter` owns all Spring wiring, properties, SAS customization, web endpoints, and capability switches. `starter` aggregates it for backward compatibility, while heavy dependencies become optional.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7.1.1 / SAS 7.1.1, PostgreSQL 17, Jackson 3, JUnit 5, AssertJ, Testcontainers.

**Spec:** `docs/assessment/2026-11-04-iam-full-capability-readiness.md`

## Global Constraints

- `core` must stay free of Spring, BouncyCastle, and storage mechanisms.
- Implementations may depend on `core`; `core` must never depend on an implementation.
- Every capability must be independently switchable: `seedj.iam.{scim,mfa.totp,native-auth,rbac,abac}.enabled`.
- `seedj.iam.store=jdbc` must fail fast without a `DataSource`; `memory` must work without one.
- No plaintext TOTP secret, OTP, password, or client secret in logs or tables.
- SCIM filters and PATCH paths must never be concatenated into SQL.
- Demo clients are off by default; enabled only with `seedj.iam.demo.enabled=true`.
- PostgreSQL is the only supported JDBC dialect.
- Existing 100 OIDC conformance cases must stay green.

## Review Focus

- Unknown user + wrong password must take the same time and return the same error.
- TOTP replay inside the same 30-second step must be rejected.
- SCIM filter injection (`userName eq "x\" or 1=1"`) must be treated as a literal, never SQL.
- Disabled users must lose refresh/authorization immediately, not at access-token expiry.
- Cross-tenant role/user/policy lookups must never leak.

---

### Task 1: Module shape and annotation semantics

**Files:** root `pom.xml`, `seedj-iam-starter/pom.xml`, `starter/pom.xml`, `domain-jdbc-postgresql/pom.xml`, `domain-infra-memory/pom.xml`, `EnableIamServer.java`, `EnableBizServer.java`, test hosts.

- [ ] Add `seedj-iam-starter` module; move IAM Spring code there.
- [ ] Remove `@SpringBootApplication` from both enable annotations; add `@SpringBootApplication` to test hosts.
- [ ] Make PostgreSQL, actuator, BouncyCastle optional in aggregating modules.
- [ ] Verify: `mvn -o -pl starter -am test-compile`.

### Task 2: Core IAM contracts and pure algorithms

**Files:** `core/src/main/java/cn/byteforce/seedj/iam/**`.

- [ ] Add user/group/role/permission/policy/TOTP/MFA/audit records and SPI interfaces.
- [ ] Add SCIM filter + PATCH parser, TOTP RFC 6238/4648, ABAC evaluator.
- [ ] Tests: RFC 6238 Appendix B vectors, Base32, filter precedence/injection, PATCH, policy DENY precedence/default deny.
- [ ] Verify: `mvn -o -pl core test`.

### Task 3: Memory adapters + contract tests

**Files:** `domain-infra-memory/src/main/java/cn/byteforce/seedj/iam/memory/**`, `core/src/test/**` contract TCK.

- [ ] Implement in-memory user/group/authorization/policy/TOTP/audit/signing-key/token-revoker stores.
- [ ] Add AES-GCM and Transit secret protectors.
- [ ] Contract tests extended once per implementation.
- [ ] Verify: `mvn -o -pl domain-infra-memory -am test`.

### Task 4: JDBC schema, migrations, repositories, SAS persistence

**Files:** `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/iam/jdbc/**`.

- [ ] Refactor `JdbcMigrationRunner` to `migrate(scope, migrations)` / `validate(scope, migrations)`.
- [ ] Add `iam` and `oauth2` versioned migrations (SAS DDL adapted to PostgreSQL).
- [ ] Add JDBC repositories, JWK store, audit sink, token revoker, schema capabilities.
- [ ] Condition outbox on PostgreSQL driver and make Jackson a real dependency.
- [ ] Verify: `mvn -o -pl domain-jdbc-postgresql -am test` with Testcontainers.

### Task 5: IAM starter wiring, properties, security chains

**Files:** `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/**`, `META-INF/spring/...imports`.

- [ ] `IamProperties` + validation + issuer resolution + fail-fast store guard.
- [ ] Memory/JDBC store selection; user-details adapter; password encoder; bootstrap admin.
- [ ] JWK source with active/retired rotation; demo clients gated.
- [ ] SAS chains for `/oauth2/**`, `/scim/**`, `/iam/**`, actuator, default; browser MFA success handler.
- [ ] Token customizer (`roles`, `permissions`, `groups`, `tenant_id`, `amr`, `acr`, `auth_time`, `typ`, `aud`), UserInfo mapper, metadata.
- [ ] Verify: `mvn -o -pl seedj-iam-starter -am test-compile`.

### Task 6: RBAC + TOTP browser/native flows

**Files:** `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/rbac/**`, `mfa/**`, `biz/config/BizServerAutoConfiguration.java`.

- [ ] Effective role/permission calculation (direct + group + inheritance), epoch invalidation.
- [ ] Admin role/permission API and bootstrap admin permission.
- [ ] TOTP enroll/confirm/status/disable/recovery + replay protection.
- [ ] Browser MFA pending session upgrade and native MFA challenge.
- [ ] Biz JWT converter maps `scope→SCOPE_`, `roles→ROLE_`, `permissions→PERM_`, `groups→GROUP_`.
- [ ] Tests: role inheritance, claim content, TOTP replay, recovery one-time.

### Task 7: SCIM 2.0

**Files:** `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/scim/**`.

- [ ] Discovery, User/Group CRUD, PATCH, filter, pagination, ETag/If-Match, SCIM error `scimType`.
- [ ] SCIM bearer chain with read/write scopes; `active=false` revokes authorizations.
- [ ] Tests: CRUD, filter precedence, injection literal, PATCH, ETag 412, pagination.

### Task 8: ABAC

**Files:** `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/abac/**`.

- [ ] Policy JSON codec, policy admin API, resource resolver seam, `@iam.authorize(...)` facade.
- [ ] DENY precedence/default deny/audit, short-TTL decision cache keyed by authz epoch.
- [ ] Tests: allow/deny/default-deny/resource resolver/epoch invalidation.

### Task 9: native-auth grant

**Files:** `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/nativeauth/**`.

- [ ] Converter/provider, two-phase MFA (`mfa_required` + `mfa_token`), token generator, metadata advertisement.
- [ ] Client grant validation, lockout/rate limit, device binding, refresh revocation.
- [ ] Tests: success, bad password/unknown user, MFA, OTP replay, refresh, revoke, metadata.

### Task 10: Verification, docs, ADRs

- [ ] Update `docs/spi/README.md` module table and add IAM ADR.
- [ ] Shape checks from the spec all return 0.
- [ ] Run OIDC IT + new IAM ITs.
