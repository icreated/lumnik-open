package io.lumnik.hub.view;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyDoorSupport;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.manifest.LinkTemplate;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.validator.ValidationError;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    JsonNode root = ApiErrors.orBadRequest(() -> parser.readTree(body));
    ViewManifest manifest = ApiErrors.orBadRequest(() -> parser.parse(root));

    // C3: a key the parser does not honor used to be accepted and saved, so a misspelled `titel:`
    // cost the integrator their title with no word said. Refused at the curated door only — the
    // store holds manifests as YAML, and refusing on the read path would break a view already
    // saved with an extra key.
    List<String> unknown = parser.unknownKeys(root);
    Optional<Response> unknownRefusal =
        ApplyDoorSupport.refuseUnknownKeys("View", unknown, ViewManifestParser.honoredSurface());
    if (unknownRefusal.isPresent()) return unknownRefusal.get();

    // the name becomes the PK and a URL path segment in GET /api/views/{name}: refuse
    // anything unfetchable (unsafe characters) or shadowed (sibling endpoint literals)
    String name = manifest.name().strip().toLowerCase(Locale.ROOT);
    Optional<Response> slugRefusal = ApplyDoorSupport.refuseReservedOrInvalidSlug(name, RESERVED_NAMES);
    if (slugRefusal.isPresent()) return slugRefusal.get();

    if (!ScopeGrants.allows(manifest.scope())) {
      return ApiErrors.scopeNotGranted();
    }

    // …and the NAME is the other half of the address. SavedViewStore.upsert conflicts on (name) and
    // sets scope = EXCLUDED.scope, so declaring into a scope you hold under a name that belongs to
    // one you do not would re-home that view: the scope that owned the screen stops seeing it.
    // Same words as the check above, so the two refusals are indistinguishable (#509).
    //
    // ORDERED — read, then write — which a race can jump; the store re-asks atomically and
    // refuseUnlessApplied below is that second answer. This earlier one is kept because it refuses
    // BEFORE the 404 further down names a scope, and a caller refused for the NAME must not read
    // which scopes exist on the way out (#582).
    ApplyScopeBoundary.refuseUnlessCallerHoldsScope(store.findScope(name));

    Optional<SchemaCard> card = cards.load(tenant, manifest.scope());
    if (card.isEmpty()) {
      return Response.status(404).entity(ApiErrors.error(
          "no schema for scope '" + manifest.scope() + "' — run discovery first")).build();
    }

    ResolvedView rv = ApiErrors.orBadRequest(() -> resolver.resolve(manifest, card.get()));

    // granted().orElse(null) — empty Optional means unscoped, i.e. every scope, the same
    // expression the scope-filtered list reads its filter from. The write's own answer goes
    // straight into the void guard, so it cannot be had and dropped.
    ApplyScopeBoundary.refuseUnlessApplied(
        store.upsert(name, manifest.scope(), rv.title(), body, ScopeGrants.granted().orElse(null)));

    // Audit P4: the upsert goes through raw JDBC, outside every Hibernate listener — this
    // explicit capture is the only record of who changed which scope's screen when.
    audit.record("SavedView", name, "APPLY", Map.of(
        "scope", manifest.scope(),
        "manifest_sha256", AuditRecorder.sha256(body)));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("scope", manifest.scope());
    // Absent rather than empty, like the App and Connector doors: applied, and worth saying something.
    List<ValidationError> warnings = linkWarnings(manifest, rv);
    if (!warnings.isEmpty()) result.put("warnings", warnings);
    return Response.ok(result).build();
  }

  /**
   * The value a filter carries is the cell's value read back from JSON, then matched as TEXT by the
   * target (`col::text = ?`). That round-trips for text, integers, uuids and booleans — not for a
   * numeric(18,4) (10.5000 comes back 10.5), a bigint past 2^53 or a timestamp (ISO is not PostgreSQL's
   * text; a date or timestamp TARGET column compares it as an instant since #1310, but JSON keeps
   * milliseconds where the column may hold microseconds) — and a mismatch would show an honest-looking empty table, which is what this door exists
   * to prevent. Said at apply, per placeholder column, never refused: a view might still be usable.
   */
  private static List<ValidationError> typeWarnings(ViewLink link, String path, ResolvedView rv) {
    List<ValidationError> out = new ArrayList<>();
    for (Map.Entry<String, String> f : link.filters().entrySet()) {
      for (String column : LinkTemplate.placeholders(f.getValue())) {
        rv.columns().stream().filter(c -> c.name().equals(column)).findFirst()
            .filter(c -> !roundTripsAsText(c.pgType()))
            .ifPresent(c -> out.add(new ValidationError(path + ".filters." + f.getKey(),
                ValidationError.Severity.WARNING,
                "column '" + column + "' is " + c.pgType() + ": its value may not match as text on the target,"
                    + " and the link would then open an empty view; link from a text or integer column",
                List.of())));
      }
    }
    return out;
  }

  private static boolean roundTripsAsText(String pgType) {
    String t = pgType == null ? "" : pgType.toLowerCase(Locale.ROOT).strip();
    return t.startsWith("text") || t.startsWith("char") || t.startsWith("varchar") || t.startsWith("bpchar")
        || t.startsWith("character") || t.equals("integer") || t.equals("int") || t.equals("int2")
        || t.equals("int4") || t.equals("smallint") || t.equals("bigint") || t.equals("int8") || t.equals("uuid") || t.equals("boolean")
        || t.equals("bool");
  }

  /**
   * #1050: a link's target is checked against what exists NOW, and never refused — views are applied in
   * either order (A→B and B→A cannot both wait for the other), so an unresolved target is kept and the
   * client fails closed when the link is followed. A target that is missing and a target that belongs to
   * another scope get the same words: the door must not tell which scopes hold which view names.
   */
  private List<ValidationError> linkWarnings(ViewManifest manifest, ResolvedView rv) {
    List<ValidationError> out = new ArrayList<>();
    for (int i = 0; i < manifest.links().size(); i++) {
      ViewLink link = manifest.links().get(i);
      if (link.targetView() == null) continue;
      String path = "spec.links[" + i + "]";
      out.addAll(typeWarnings(link, path, rv));
      Optional<SavedViewStore.Row> target = store.find(link.targetView())
          .filter(r -> r.scope().equals(manifest.scope()));
      if (target.isEmpty()) {
        out.add(new ValidationError(path + ".targetView", ValidationError.Severity.WARNING,
            "targetView '" + link.targetView() + "' is not a view of scope '" + manifest.scope() + "' (yet); the"
                + " declaration is kept, and following the link is refused until that view is applied",
            List.of()));
        continue;
      }
      List<String> shown;
      try {
        shown = parser.parse(target.get().yaml()).columns();
      } catch (IllegalArgumentException e) {
        continue; // a stored view that no longer parses is the render door's refusal to speak
      }
      for (String column : link.filters().keySet()) {
        if (!shown.contains(column)) {
          out.add(new ValidationError(path + ".filters." + column, ValidationError.Severity.WARNING,
              "view '" + link.targetView() + "' does not show column '" + column + "'; following the link will be"
                  + " refused until it does",
              List.of("shown there: " + shown)));
        }
      }
    }
    return out;
  }
}
