package io.lumnik.hub.entity;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.entity.resolution.CascadeResolver;
import io.lumnik.hub.entity.resolution.SameSourceAssertionException;
import io.lumnik.hub.sql.SqlFailureText;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.RequireUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.UUID;

/**
 * The write door into {@code same}/{@code distinct} human assertions (#619) — the correction path
 * design doc §7 grants outranks every cascade rule. #619 built {@code CascadeResolver.assertSame}/
 * {@code .assertDistinct} service-level only and deferred the REST surface here on purpose.
 *
 * <p>Same authority posture as {@link EntityResolveResource}: {@code lm_admin}, {@code
 * allowsAll} — an assertion writes into the crosswalk's decision history the same way a replay
 * does. Two things it owns that the service layer must not: {@code assertedBy} comes ONLY from
 * the authenticated identity ({@link RequireUser#current()}), never the request body — a caller
 * cannot attribute a correction to someone else; and a ref belonging to another tenant or entity
 * type is refused with a clear 404 rather than the {@code fk_entity_assertion*} constraint
 * violation {@code CascadeResolverTest.an_assertion_on_a_pair_of_another_tenant_is_refused} pins
 * at the service layer (that guard is unchanged and does the real enforcement; this door only
 * translates its failure into the wire contract instead of adding a second, race-prone copy of
 * the same check in front of it — same posture as {@code WorkflowApplyResource.isUniqueViolation}).
 */
@Path("/api/entities/{name}/assertions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class EntityAssertionResource {

  private static final String FOREIGN_KEY_VIOLATION_SQLSTATE = "23503";

  @Inject EntityRegistry registry;
  @Inject CascadeResolver resolver;

  /** No {@code assertedBy} field — the whole point (see class javadoc). */
  public record AssertionRequest(UUID leftRef, UUID rightRef, String reason) {}

  @POST
  @Path("/same")
  public Response same(@PathParam("name") String name, AssertionRequest body) {
    return record(name, body, true);
  }

  @POST
  @Path("/distinct")
  public Response distinct(@PathParam("name") String name, AssertionRequest body) {
    return record(name, body, false);
  }

  private Response record(String name, AssertionRequest body, boolean same) {
    EntityManifest m = ResolutionGate.requireResolvable(registry, name, true);
    if (body == null || body.leftRef() == null || body.rightRef() == null) {
      return ApiErrors.badRequest("leftRef and rightRef are required");
    }

    long tenantId = RequireTenant.current();
    long userId = RequireUser.current();
    String entityType = m.name().toLowerCase(Locale.ROOT);
    String assertedBy = String.valueOf(userId);

    try {
      if (same) {
        resolver.assertSame(tenantId, entityType, body.leftRef(), body.rightRef(), assertedBy, body.reason());
      } else {
        resolver.assertDistinct(tenantId, entityType, body.leftRef(), body.rightRef(), assertedBy, body.reason());
      }
    } catch (SameSourceAssertionException e) {
      // icreated/lumnik#735: refuse with a clear 400 instead of recording an assertion that would
      // only crash later, asynchronously, when either row is next reprocessed.
      return ApiErrors.badRequest(e.getMessage());
    } catch (RuntimeException e) {
      if (FOREIGN_KEY_VIOLATION_SQLSTATE.equals(SqlFailureText.sqlStateOf(e))) {
        return ApiErrors.notFound(
            "unknown identity_ref for entity '" + name + "': neither ref may exist, belong to "
                + "another tenant, or belong to another entity type");
      }
      throw e;
    }
    return Response.noContent().build();
  }
}
