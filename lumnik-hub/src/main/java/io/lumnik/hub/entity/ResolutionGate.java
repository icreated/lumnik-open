package io.lumnik.hub.entity;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import jakarta.ws.rs.WebApplicationException;

/**
 * The three refusals every door over a resolution-bearing entity shares — unknown entity,
 * no {@code resolution:} policy, scope not granted — factored out once #622 grew the second and
 * third caller ({@link EntityAssertionResource}, {@link EntityResolutionReadResource}) alongside
 * the original ({@link EntityResolveResource}): a copy in each door is a copy each one can drift
 * from (the pre-#622 wording already had two spellings of the same two messages).
 */
public final class ResolutionGate {

  private ResolutionGate() {}

  /**
   * The manifest, or throws the uniform refusal as a {@link WebApplicationException} (caught by
   * JAX-RS and rendered from its own {@code Response} regardless of the caller's own return type).
   * {@code writeAccess} picks {@link ScopeGrants#allowsAll}/{@link ScopeGrants#allowsAny} — the
   * same asymmetry every other write/read door in the hub already applies.
   */
  public static EntityManifest requireResolvable(EntityRegistry registry, String name, boolean writeAccess) {
    EntityManifest m = registry.findReloadingIfMissing(name);
    if (m == null) {
      throw new WebApplicationException(ApiErrors.notFound("entity '" + name + "' not found"));
    }
    if (m.resolution() == null) {
      throw new WebApplicationException(ApiErrors.badRequest("entity '" + name + "' has no resolution: policy"));
    }
    boolean allowed = writeAccess ? ScopeGrants.allowsAll(m.scopes()) : ScopeGrants.allowsAny(m.scopes());
    if (!allowed) {
      throw new WebApplicationException(ApiErrors.scopeNotGranted());
    }
    return m;
  }
}
