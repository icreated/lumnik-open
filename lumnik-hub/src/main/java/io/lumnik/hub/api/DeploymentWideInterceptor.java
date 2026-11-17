package io.lumnik.hub.api;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.security.PermissionInterceptor;
import io.lumnik.hub.tenant.DeploymentTenancy;
import io.lumnik.hub.tenant.DeploymentTenancy.Verdict;
import io.lumnik.hub.tenant.ManifestOwner;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.WebApplicationException;

import java.util.Set;

/**
 * {@link DeploymentWide}: on a multi-tenant hub, a caller without {@code lm_superadmin} is refused, with the reason.
 * Around the door's method, after every request filter (so a scope-bound caller keeps its uniform refusal) and outside
 * its transaction ({@code @Transactional} is {@code PLATFORM_BEFORE + 200}): the count the write rests on is held until
 * the write has committed or rolled back, and let go in a {@code finally} whatever happens ({@link DeploymentTenancy#hold}).
 * A superadmin passes before anything is read. A caller the door's own role gate refuses is left to it: they learn
 * nothing of the tenancy, and cost no count. On any hub, a write from the hub's own tenant (hubadmin) needs
 * {@code lm_superadmin}: what it writes is shared with every tenant (#2050).
 */
@DeploymentWide
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class DeploymentWideInterceptor {

  static final String REFUSAL = "deployment-wide: this hub serves several tenants, and entities, views, apps and the "
      + "platform corpus are written by lm_superadmin only";

  static final String HUB_REFUSAL = "deployment-wide: what is written from the hub's own tenant is shared with every "
      + "tenant, and is written by lm_superadmin only";

  @Inject DeploymentTenancy tenancy;

  @AroundInvoke
  Object around(InvocationContext ctx) throws Exception {
    Set<String> roles = CurrentRolesHolder.get();
    // The door's own gate, read as PermissionInterceptor reads it: a caller it refuses learns nothing of the tenancy.
    if (roles.contains("lm_superadmin") || !PermissionInterceptor.allows(PermissionInterceptor.gateOf(ctx), roles)) {
      return ctx.proceed();
    }
    if (ManifestOwner.fromTheHub(CurrentTenantHolder.get())) {
      throw new WebApplicationException(ApiErrors.forbidden(HUB_REFUSAL));
    }
    try (Verdict verdict = tenancy.hold()) {
      if (verdict.multiTenant()) throw new WebApplicationException(ApiErrors.forbidden(REFUSAL));
      return ctx.proceed();
    }
  }
}
