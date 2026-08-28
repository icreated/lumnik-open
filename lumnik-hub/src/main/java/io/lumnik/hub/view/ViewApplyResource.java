package io.lumnik.hub.view;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Curated writes to the saved-view store: apply = validate-then-upsert. A view that
 * cannot render is not saved — the same parser + SchemaCard gate as render, resolved
 * against the applying tenant's card (the entity-apply asymmetry: manifests global,
 * validation tenant-local). Integrator door; the scope: boundary applies here too.
 */
@Path("/api/views/apply")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class ViewApplyResource {
  /** Sibling endpoint literals under /api/views — a view so named would list but never fetch. */
  static final Set<String> RESERVED_NAMES =
      Set.of("apply", "render", "detail", "generate");

  @Inject SchemaCardStore cards;
  @Inject SavedViewStore store;
  @Inject AuditRecorder audit;

  private final ViewManifestParser parser = new ViewManifestParser();
  private final ViewResolver resolver = new ViewResolver();

  @POST
  @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
  public Response apply(String body) {
    long tenant = RequireTenant.current();

    ViewManifest manifest;
    try {
      manifest = parser.parse(body);
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
    }

    // C3: a key the parser does not honor used to be accepted and saved, so a misspelled `titel:`
    // cost the integrator their title with no word said. Refused at the curated door only — the
    // store holds manifests as YAML, and refusing on the read path would break a view already
    // saved with an extra key.
    List<String> unknown = parser.unknownKeys(body);
    if (!unknown.isEmpty()) {
      return Response.status(400).entity(ApiErrors.error(
          "unknown key(s) in the manifest: " + String.join(", ", unknown)
              + " — kind:View honors " + ViewManifestParser.honoredSurface())).build();
    }

    // the name becomes the PK and a URL path segment in GET /api/views/{name}: refuse
    // anything unfetchable (unsafe characters) or shadowed (sibling endpoint literals)
    String name = manifest.name().strip().toLowerCase(Locale.ROOT);
    if (!name.matches("[a-z0-9][a-z0-9_-]*") || RESERVED_NAMES.contains(name)) {
      return Response.status(400).entity(ApiErrors.error(
          "metadata.name must be a URL-safe slug ([a-z0-9_-], starting with a letter or digit) "
              + "and not one of " + RESERVED_NAMES)).build();
    }

    if (!ScopeGrants.allows(manifest.scope())) {
      return ApiErrors.scopeNotGranted();
    }

    Optional<SchemaCard> card = cards.load(tenant, manifest.scope());
    if (card.isEmpty()) {
      return Response.status(404).entity(ApiErrors.error(
          "no schema for scope '" + manifest.scope() + "' — run discovery first")).build();
    }

    ResolvedView rv;
    try {
      rv = resolver.resolve(manifest, card.get());
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
    }

    store.upsert(name, manifest.scope(), rv.title(), body);
    // Audit P4: the upsert goes through raw JDBC, outside every Hibernate listener — this
    // explicit capture is the only record of who changed which métier screen when.
    audit.record("SavedView", name, "APPLY", Map.of(
        "scope", manifest.scope(),
        "manifest_sha256", AuditRecorder.sha256(body)));
    return Response.ok(Map.of("name", name, "scope", manifest.scope())).build();
  }
}
