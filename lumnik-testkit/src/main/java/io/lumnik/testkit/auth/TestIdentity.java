package io.lumnik.testkit.auth;

import java.util.Map;

/**
 * The dev-bypass headers a test authenticates with, as a named posture.
 *
 * <p>Under {@code lumnik.security.dev-bypass=true} the auth filter reads {@code X-Tenant-ID} and
 * {@code X-User-ID} — it is the one place allowed to turn a header into an identity. Tests reach it
 * by sending them, and 273 call sites did so by hand, in two spellings, which made the interesting
 * half invisible: <b>79 of them send a tenant and no user at all</b>. That is not noise, it is a
 * claim — the door under test does not need an actor — and after the identity work of #390 a door
 * that calls {@code RequireUser} would answer 401 to exactly those calls.
 *
 * <p>So the postures get names rather than a single {@code asAdmin()} that would have quietly given
 * all 273 a user. A reader of any test now sees which one it asserts:
 *
 * <pre>{@code
 * given().headers(TestIdentity.tenantAndUser(1))   // an authenticated person acts
 * given().headers(TestIdentity.tenantOnly(1))      // the door needs no actor, and that is the point
 * }</pre>
 *
 * <p>A Map rather than a {@code RequestSpecification}: {@code lumnik-testkit} is a main-scope module
 * every test module consumes, and pulling rest-assured into it to shape one helper would put a test
 * HTTP client on the compile path of all of them.
 */
public final class TestIdentity {

    /**
     * The spelling {@code TenantContextFilter} actually reads ({@code getHeaderString("X-Tenant-ID")}),
     * and the majority in the docs. HTTP header names are case-insensitive, so the other casing
     * worked — which is how two spellings lived side by side across 273 call sites. This class is
     * where that stops, so it carries the canonical one.
     */
    public static final String TENANT_HEADER = "X-Tenant-ID";

    public static final String USER_HEADER = "X-User-ID";

    private TestIdentity() {}

    /** An authenticated person of that tenant — user 1 unless the test cares which. */
    public static Map<String, String> tenantAndUser(long tenantId) {
        return tenantAndUser(tenantId, 1L);
    }

    /** An authenticated person, named. */
    public static Map<String, String> tenantAndUser(long tenantId, long userId) {
        return Map.of(TENANT_HEADER, String.valueOf(tenantId), USER_HEADER, String.valueOf(userId));
    }

    /**
     * A tenant with no actor. Deliberate, not an omission: it asserts the door under test asks for
     * none. A door that later starts calling {@code RequireUser} turns these into 401s, which is
     * the signal you want — not a silently supplied user.
     */
    public static Map<String, String> tenantOnly(long tenantId) {
        return Map.of(TENANT_HEADER, String.valueOf(tenantId));
    }
}
