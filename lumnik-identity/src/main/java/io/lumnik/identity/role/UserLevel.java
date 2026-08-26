package io.lumnik.identity.role;

/**
 * Controls which data a role (and by extension its users) can access.
 * Three-tier access scope (system / tenant / organization) — a classic ERP role pattern.
 * <p>
 * The mask encodes visibility: System sees all tenants; Client is locked to one tenant;
 * Org is locked to specific orgs within the tenant; CO is the most common operational level.
 */
public enum UserLevel {

    /** S — can log in as any tenant. Reserved for SaaS admins. */
    SYSTEM("S"),

    /** C — can access all orgs within their own tenant. */
    CLIENT("C"),

    /** O — can access only specific orgs (org-level isolation). Unusual in practice. */
    ORG("O"),

    /** CO — standard business user: own tenant, specific orgs. Most roles use this. */
    CLIENT_ORG("CO");

    private final String code;

    UserLevel(String code) { this.code = code; }

    public String getCode() { return code; }

    public static UserLevel fromCode(String code) {
        for (UserLevel l : values()) {
            if (l.code.equals(code)) return l;
        }
        throw new IllegalArgumentException("Unknown UserLevel code: " + code);
    }
}
