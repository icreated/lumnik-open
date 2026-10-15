package io.lumnik.hub.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.ApplyDoorSupport;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.hub.validator.ValidationError;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
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
 * Curated writes to the app registry: apply = validate-then-upsert, the ViewApplyResource template.
 * Integrator door; the scope: boundary applies on every row this apply writes to — the scope the
 * manifest DECLARES, the scope of the row the name would overwrite, and the scope of the row that
 * loses the default flag when this one takes it.
 */
@Path("/api/apps/apply")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_integrator")
public class AppApplyResource {

    /** Sibling endpoint literal under /api/apps — an app so named would list but never fetch. */
    static final Set<String> RESERVED_NAMES = Set.of("apply");

    /** The floor's words, in ONE place: the door's ordered check and the store's atomic re-ask are
     *  the same refusal reached by two routes, and an integrator must not be able to tell which
     *  one answered by reading the sentence. Package-private: {@link AppWriteResource}'s delete
     *  door shares it too — "cannot remove the last default" already reads correctly for a
     *  delete, so the sentence stays single-sourced rather than growing a second, near-identical
     *  copy. */
    static final String LAST_DEFAULT_REFUSAL = "cannot remove the last default app — apply "
        + "a different app with default: true first, which demotes this one automatically";

    @Inject AppRegistrationStore store;
    @Inject AuditRecorder audit;
    @Inject ModuleManifestRegistry modules;

    private final AppManifestParser parser = new AppManifestParser();

    @POST
    @Consumes({"application/yaml", "text/yaml", MediaType.APPLICATION_JSON})
    public Response apply(String body) {
        JsonNode root;
        AppManifest manifest;
        try {
            root = parser.readTree(body);
            manifest = parser.parse(root);
        } catch (IllegalArgumentException e) {
            return ApiErrors.badRequest(e.getMessage());
        }

        // A key the parser does not honour would otherwise be accepted and stored, so a misspelled
        // `titel:` costs the integrator their title with no word said. Refused at the curated door
        // only — the store holds the manifest as YAML, and refusing on the read path would break
        // reading a row already saved with an extra key.
        List<String> unknown = parser.unknownKeys(root);
        Optional<Response> unknownRefusal =
            ApplyDoorSupport.refuseUnknownKeys("App", unknown, AppManifestParser.honoredSurface());
        if (unknownRefusal.isPresent()) return unknownRefusal.get();

        // the name becomes the PK, a URL path segment in GET /api/apps/{name}, and the ?m= tag the
        // PWA carries: refuse anything unfetchable or shadowed by a sibling endpoint literal
        String name = manifest.name().strip().toLowerCase(Locale.ROOT);
        Optional<Response> slugRefusal = ApplyDoorSupport.refuseReservedOrInvalidSlug(name, RESERVED_NAMES);
        if (slugRefusal.isPresent()) return slugRefusal.get();

        if (!ScopeGrants.allows(manifest.scope())) {
            return ApiErrors.scopeNotGranted();
        }

        // …and the NAME is the other half of the address. upsert conflicts on (name) and sets
        // scope = EXCLUDED.scope, so declaring into a scope you hold under a name belonging to one
        // you do not would re-home that app: the métier that owned the screen stops seeing it.
        // Same words as the check above, so the two refusals are indistinguishable (#509).
        //
        // ORDERED, like the default-holder check below and porous the same way: findScope reads on
        // its own autocommit connection, and the conflict arm runs later in another transaction
        // against whichever row holds the name at THAT instant. The store re-asks inside the insert
        // statement itself, and the NameHeldOutOfScope refusal further down is that second answer.
        // Belt AND braces on purpose: the ordered check is what a reader (and
        // ScopeBoundaryInventoryTest) finds here, the predicate is what survives a race.
        ApplyScopeBoundary.refuseUnlessCallerHoldsScope(store.findScope(name));

        // At most one default is the partial unique index's job; at LEAST one is this door's.
        // `default:` is an ordinary field, so re-applying the current default with `default: false`
        // would leave an absent ?m= resolving to nothing — the very gap #571 exists to close,
        // reopened from the other side. Promoting a replacement is always a single call (it
        // demotes this one automatically); demoting without one never is.
        //
        // ORDERED, like the two scope checks around it, and porous the same way: findDefaultName
        // reads on its own autocommit connection, and this name is not the default at THAT instant
        // — but a concurrent apply can promote it before the conflict arm runs, which would then
        // clear the flag it just took. The store re-asks the same question inside the insert
        // statement itself, and the LastDefaultWouldBeRemoved refusal further down is that second
        // answer, speaking these very words. Belt AND braces on purpose: the ordered check is what
        // a reader finds in the door, the predicate is what survives a race.
        if (!manifest.isDefault() && store.findDefaultName().filter(name::equals).isPresent()) {
            return ApiErrors.badRequest(LAST_DEFAULT_REFUSAL);
        }

        // …and the DEFAULT FLAG is a third address this apply writes to, unique to kind:App — no
        // other manifest kind has a deployment-global singleton. Taking the flag demotes whoever
        // holds it, so a caller bound to one métier could silently rewrite a row in a scope they
        // cannot even READ. Same question, same frozen words as the two checks above.
        //
        // This single check also closes the disclosure: `demotedDefault` is echoed in the response
        // below, and after this refusal the only name it can ever carry is one from a scope the
        // caller holds. Suppressing the field as well would be redundant — it is deliberately NOT
        // missing.
        //
        // It is an ORDERED check — read the holder, then write — which is correct on a quiet
        // deployment and porous under concurrency: the read and the demote take different
        // connections, so the holder can change between them. The store re-asks the same question
        // inside the demote statement itself, and the refusal further down is that second answer.
        // Belt AND braces on purpose: the ordered check is what a reader (and
        // ScopeBoundaryInventoryTest) finds here, the predicate is what survives a race.
        if (manifest.isDefault()) {
            Optional<String> holder = store.findDefaultName().filter(d -> !d.equals(name));
            if (holder.isPresent()) {
                ApplyScopeBoundary.refuseUnlessCallerHoldsScope(store.findScope(holder.get()));
            }
        }

        AppManifest stored = new AppManifest(name, manifest.scope(), manifest.title(),
            manifest.icon(), manifest.accentColor(), manifest.backgroundColor(),
            manifest.isDefault(), manifest.tabs());
        // granted().orElse(null) — empty Optional means unscoped, i.e. every scope, the same
        // expression AppRegistryResource.list() reads its filter from.
        AppRegistrationStore.Upserted outcome =
            store.upsert(stored, body, ScopeGrants.granted().orElse(null));
        // A sealed outcome, so the demoted name cannot be read without naming every refusal too.
        // They THROW rather than returning, the ApplyScopeBoundary idiom: these arms are refusals
        // and a refusal that can be dropped on the floor is not one.
        //
        // The two SCOPE arms answer with the SAME frozen 403 — byte-identical, deliberately.
        // Telling a caller whether it was the name or the default flag that stopped them would say
        // which of the two addresses is occupied by a scope they cannot see, which is the one bit
        // ApplyScopeBoundary's javadoc reasons about withholding.
        //
        // The floor arm is NOT a scope refusal and must not be dressed as one: it answers the same
        // 400, in the same words, as the ordered check above. It tells the caller about THEIR OWN
        // app — the store only ever reaches that arm for a row inside their grants — so there is
        // nothing here to withhold, and disguising a "fix your manifest" as "you may not" would
        // send an integrator hunting for a permission they already hold.
        Optional<String> demoted = switch (outcome) {
            case AppRegistrationStore.Upserted.Applied a -> a.demotedDefault();
            case AppRegistrationStore.Upserted.DefaultHeldOutOfScope ignored ->
                throw new WebApplicationException(ApiErrors.scopeNotGranted());
            case AppRegistrationStore.Upserted.NameHeldOutOfScope ignored ->
                throw new WebApplicationException(ApiErrors.scopeNotGranted());
            case AppRegistrationStore.Upserted.LastDefaultWouldBeRemoved ignored ->
                throw new WebApplicationException(ApiErrors.badRequest(LAST_DEFAULT_REFUSAL));
        };

        // Audit P4: the upsert goes through raw JDBC, outside every Hibernate listener — this
        // explicit capture is the only record of who changed which métier's identity, and of the
        // app that silently lost the default flag because of it.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scope", stored.scope());
        snapshot.put("manifest_sha256", AuditRecorder.sha256(body));
        demoted.ifPresent(d -> snapshot.put("demoted_default", d));
        audit.record("AppRegistration", name, "APPLY", snapshot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("scope", stored.scope());
        result.put("default", stored.isDefault());
        demoted.ifPresent(d -> result.put("demotedDefault", d));
        if (stored.tabs() != null) result.put("tabs", stored.tabs());
        // Absent rather than empty, like the Connector door: applied, and worth saying something.
        List<ValidationError> warnings = tabWarnings(stored.tabs());
        if (!warnings.isEmpty()) result.put("warnings", warnings);
        return Response.ok(result).build();
    }

    /** A declared tab this hub does not offer is KEPT (the manifest stays portable) and reported. */
    private List<ValidationError> tabWarnings(List<String> declared) {
        if (declared == null) return List.of();
        List<String> offered = modules.offeredTabs();
        List<ValidationError> out = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            if (!offered.contains(declared.get(i))) {
                out.add(new ValidationError("spec.tabs[" + i + "]", ValidationError.Severity.WARNING,
                    "tab '" + declared.get(i) + "' is not offered by this hub; the declaration is kept and takes "
                        + "effect when a module offering it is active",
                    List.of("offered here: " + offered)));
            }
        }
        return out;
    }
}
