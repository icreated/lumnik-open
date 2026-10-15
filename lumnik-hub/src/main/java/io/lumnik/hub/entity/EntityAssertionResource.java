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

  /**
   * No {@code assertedBy} field — the whole point (see class javadoc). Each side is named either by
   * its identity_ref or by its source handle {@code <source table>:<source id>} (#1087), the form
   * the entity view's {@code _handle} and {@code lm entity history} use — no door hands out the
   * identity_ref of a resolved row.
   */
  public record AssertionRequest(UUID leftRef, UUID rightRef, String left, String right, String reason) {}

  private static final String HANDLE_FORM = "<source table>:<source id>";

  /** A side's identity_ref, or the refusal that names what is wrong with it. */
  private record Side(UUID ref, Response refusal) {}

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
    if (body == null) {
      return ApiErrors.badRequest("left and right are required: each an identity_ref or a source handle "
          + HANDLE_FORM);
    }

    long tenantId = RequireTenant.current();
    long userId = RequireUser.current();
    String entityType = m.name().toLowerCase(Locale.ROOT);
    String assertedBy = String.valueOf(userId);

    Side left = side(name, tenantId, entityType, "left", body.leftRef(), body.left());
    if (left.refusal() != null) return left.refusal();
    Side right = side(name, tenantId, entityType, "right", body.rightRef(), body.right());
    if (right.refusal() != null) return right.refusal();

    try {
      if (same) {
        resolver.assertSame(tenantId, entityType, left.ref(), right.ref(), assertedBy, body.reason());
      } else {
        resolver.assertDistinct(tenantId, entityType, left.ref(), right.ref(), assertedBy, body.reason());
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

  /**
   * One side of the pair: its identity_ref as given, or the ref its source handle names. The source
   * is split at the FIRST colon — a table name never holds one, a source id may. A table name holds
   * no whitespace either, so a padded one is malformed; the source id is NOT trimmed — it is
   * compared as the source holds it (a CHAR-padded ERP key keeps its spaces), only a blank one is
   * malformed.
   */
  private Side side(String name, long tenantId, String entityType, String which, UUID ref, String handle) {
    if (ref != null && handle != null) {
      return new Side(null, ApiErrors.badRequest(which + " is given twice: send " + which + "Ref (an "
          + "identity_ref) or " + which + " (a source handle " + HANDLE_FORM + "), not both"));
    }
    if (ref != null) return new Side(ref, null);
    if (handle == null) {
      return new Side(null, ApiErrors.badRequest(which + " is required: an identity_ref (" + which
          + "Ref) or a source handle " + HANDLE_FORM + " (" + which + ")"));
    }
    int colon = handle.indexOf(':');
    String source = colon < 0 ? "" : handle.substring(0, colon);
    String sourceId = colon < 0 ? "" : handle.substring(colon + 1);
    if (source.isEmpty() || source.chars().anyMatch(Character::isWhitespace) || sourceId.isBlank()) {
      return new Side(null, ApiErrors.badRequest("'" + handle + "' is not a source handle: write "
          + HANDLE_FORM + ", for example t_crm:9003"));
    }
    return resolver.identityRefOf(tenantId, entityType, source, sourceId)
        .map(found -> new Side(found, null))
        .orElseGet(() -> new Side(null, ApiErrors.notFound("entity '" + name + "' has no row " + handle
            + " — check it with lm entity history " + name + " " + source + " " + sourceId
            + ", or run lm entity resolve " + name + " if the row is new")));
  }
}
