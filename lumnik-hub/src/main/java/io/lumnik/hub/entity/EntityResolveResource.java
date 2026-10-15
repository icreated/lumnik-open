package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.entity.resolution.EntityResolutionPass;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * The explicit door into the post-run resolution pass (#613 lot #620, design doc §7's second of
 * two triggers) — an operator-driven replay of one entity's whole extent, for after fixing a
 * human assertion, editing the cascade, or simply wanting a fresh answer without waiting for the
 * next connector run. Same posture as {@link EntityApplyResource}: {@code lm_admin}, {@code
 * allowsAll} (a replay writes into the crosswalk, the same authority apply already requires).
 */
@Path("/api/entities/{name}/resolve")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class EntityResolveResource {

  @Inject EntityRegistry registry;
  @Inject EntityResolutionPass pass;

  public record ResolveResult(long rowsSeen, long resolved, long ambiguous, long unresolvedNoRule,
      long unresolvedProvider, long newlyMinted, long providerCalls, long providerFailures,
      Map<String, Long> ruleHits) {}

  @POST
  public ResolveResult resolve(@PathParam("name") String name) {
    ResolutionGate.requireResolvable(registry, name, true);

    EntityResolutionPass.PassSummary summary = pass.runReplay(RequireTenant.current(), name);
    return new ResolveResult(summary.rowsProcessed(), summary.resolved(), summary.ambiguous(),
        summary.unresolvedNoRule(), summary.unresolvedProvider(), summary.newlyMinted(),
        summary.providerCalls(), summary.providerFailures(), summary.ruleHits());
  }
}
