package io.lumnik.hub.tenant;

import io.lumnik.common.tenant.TenantContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.hibernate.Session;

/**
 * Activates the {@code tenantFilter} Hibernate filter on the request-scoped
 * {@link EntityManager} so every Panache or JPA SELECT through this session
 * is automatically constrained to the current tenant.
 *
 * Runs after {@link TenantContextFilter} (priority AUTHORIZATION = 2000)
 * at priority USER+100 = 5100.
 *
 * In {@link TenantContext#isSystemMode()} mode, activation is skipped.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.USER + 100)
public class TenantSessionFilter implements ContainerRequestFilter {

    @Inject EntityManager em;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (TenantContext.isSystemMode()) return;

        Long tenantId = TenantContextFilter.current();
        if (tenantId == null) return;

        Session session = em.unwrap(Session.class);
        session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
    }
}
