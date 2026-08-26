package io.lumnik.hub.tenant;

import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Tenant comes ONLY from the auth-resolved context (JWT in prod, validated header in
 * dev-bypass) — NEVER from a client-supplied X-Tenant-Id header, which an authenticated
 * caller could point at another tenant's data (Act 0 carnet finding #16). Null and the
 * 0 sentinel (system tenant) are rejected, mirroring RagChatResource's analytic path.
 */
public final class RequireTenant {

    private RequireTenant() {}

    /** The authenticated tenant, or 401 via WebApplicationException. */
    public static long current() {
        Long t = CurrentTenantHolder.get();
        if (t == null || t <= 0) {
            throw new WebApplicationException(Response.status(401)
                .entity(Map.of("error", "no tenant in security context")).build());
        }
        return t;
    }
}
