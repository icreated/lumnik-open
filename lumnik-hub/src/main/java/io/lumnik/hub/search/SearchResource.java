package io.lumnik.hub.search;

import io.lumnik.common.ratelimit.RateLimited;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.extraction.spi.search.FacetCount;
import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.SearchIndexStore.HitPresentation;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Provider-neutral direct business-data search (#673): translates HTTP into an {@link
 * IndexedSearchQuery} and a {@link SearchOutcome} back into HTTP. The authorization restriction
 * itself is computed here, from {@link ScopeGrants} over the {@link EntityRegistry} — {@link
 * IndexedSearchQuery#allowedEntities()} is trusted as already correct by the gate and every
 * provider (its own javadoc says so); everything downstream of that set — absence/degraded/
 * rejected semantics, ranking, pagination — is theirs, not this class's.
 */
@Path("/api/search")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
@RateLimited("search")
public class SearchResource {

    private static final Logger LOG = Logger.getLogger(SearchResource.class);
    private static final int MAX_Q_LENGTH = 200;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_FACET_KEYS = 10;
    private static final int DEGRADED_RETRY_AFTER_SECONDS = 60;
    private static final String FACET_PREFIX = "facet.";

    @Inject SearchGate gate;
    @Inject EntityRegistry registry;
    @Inject AuditRecorder audit;
    @Inject SearchIndexStore indexes;

    @GET
    public Response search(@Context UriInfo uriInfo,
                            @QueryParam("q") String q,
                            @QueryParam("entity") String entityParam,
                            @QueryParam("limit") Integer limit,
                            @QueryParam("cursor") String cursor) {
        if (q == null || q.isBlank() || q.length() > MAX_Q_LENGTH) {
            return ApiErrors.badRequest("q must be 1-" + MAX_Q_LENGTH + " characters");
        }
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        // Refused, not clamped — unlike EntityReadResource/RunAdminResource's silent
        // Math.min(Math.max(limit,1),N): a caller outside this door's smaller, explicit bound
        // (#673's own size-cap requirement) should learn its limit was rejected, not get fewer
        // rows back than it asked for without being told.
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            return ApiErrors.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }

        Map<String, String> filters = new LinkedHashMap<>();
        String facetError = collectFacetFilters(uriInfo, filters);
        if (facetError != null) return ApiErrors.badRequest(facetError);

        // The open app's scope narrows the search (absent = the historical behaviour, for REST and lm).
        // Refused before the provider is asked: malformed → 400, a bound caller without it → the uniform 403.
        // Read from the raw query: a bare `scope=` reaches a @QueryParam String as null, i.e. as "absent",
        // and an empty scope must be refused, not silently widened to the historical behaviour.
        String scope = null;
        if (uriInfo.getQueryParameters().containsKey("scope")) {
            List<String> values = uriInfo.getQueryParameters().get("scope");
            // Refused like a repeated facet key: a second value would otherwise be dropped in silence.
            if (values.size() > 1) return ApiErrors.badRequest("scope may be given once");
            String raw = values.get(0);
            scope = raw == null ? "" : raw; // exactly as sent: no trimming into a valid identifier
            if (!ScopeGrants.IDENTIFIER.matcher(scope).matches()) {
                return ApiErrors.badRequest("scope must be a lowercase identifier");
            }
            if (!ScopeGrants.allows(scope)) return ApiErrors.scopeNotGranted();
        }
        Set<String> allowed = allowedFor(registry.all(), ScopeGrants.granted(), scope);
        String auditKey;
        if (entityParam != null) {
            // Deliberately not ResolutionGate.requireResolvable: that gate is for
            // resolution-bearing entities specifically (it also refuses a missing `resolution:`
            // policy) and answers 404 for an unknown name. Here an unknown name and an
            // out-of-scope one get the SAME 403 — search must never let an unauthorized `?entity=`
            // distinguish "doesn't exist" from "exists but you can't see it".
            EntityManifest m = registry.find(entityParam);
            if (m == null || !allowed.contains(m.name())) return ApiErrors.scopeNotGranted();
            allowed = Set.of(m.name());
            auditKey = m.name();
        } else {
            auditKey = "*"; // no single entity named — a cross-entity query
        }

        long tenant = RequireTenant.current();
        // An invalid declaration (#1187), or one withheld until rebuilt (#1219), takes no part in Search: its
        // documents and facets still carry a column it no longer declares. Left out after the ?entity= check, so
        // naming its entity is an empty answer the registry explains, never the 403 of an entity the caller
        // cannot see.
        Set<String> unserved = indexes.unservedEntities(tenant);
        Set<String> servable = new TreeSet<>(allowed);
        servable.removeIf(e -> unserved.contains(e.toLowerCase(Locale.ROOT)));
        IndexedSearchQuery query = new IndexedSearchQuery(tenant, servable, q, filters, effectiveLimit, cursor);

        SearchOutcome outcome = gate.search(query);
        return switch (outcome) {
            case SearchOutcome.Hits hits -> ok(hits.result(), auditKey, tenant);
            case SearchOutcome.Rejected rejected -> ApiErrors.badRequest(rejected.message());
            case SearchOutcome.Unavailable u -> u.capability().unavailableResponse(DEGRADED_RETRY_AFTER_SECONDS);
        };
    }

    private Response ok(IndexedSearchResult result, String auditKey, long tenant) {
        recordAudit(result, auditKey);
        Map<String, HitPresentation> presentations = presentationsOf(result, tenant);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hits", result.hits().stream()
            .map(h -> hitBody(h, presentations.get(h.searchIndex()))).toList());
        Map<String, Map<String, String>> labels = fieldLabels(
            result.hits().stream().map(IndexedSearchHit::entity).distinct().toList(), registry::find);
        if (!labels.isEmpty()) body.put("fieldLabels", labels); // #1055: absent when no entity names a column
        body.put("facets", facetsBody(result.facets()));
        body.put("nextCursor", result.nextCursor());
        body.put("asOf", result.asOf() == null ? null : result.asOf().toString());
        return Response.ok(body).build();
    }

    /** Titles and links are cosmetic: if their lookup fails, the search that already succeeded answers without them. */
    private Map<String, HitPresentation> presentationsOf(IndexedSearchResult result, long tenant) {
        if (result.hits().isEmpty()) return Map.of(); // nothing to head: no lookup, no transaction
        try {
            return indexes.presentations(tenant,
                result.hits().stream().map(IndexedSearchHit::searchIndex).distinct().toList());
        } catch (RuntimeException e) {
            LOG.warn("search titles and links unavailable, answering without them", e);
            return Map.of();
        }
    }

    /**
     * The human names (#1055) of the columns of each named entity that declares a {@code label}, keyed by
     * entity as the hits spell it. An entity none of whose columns is labelled, or that the registry does not
     * know, has no entry: the client then shows the raw column name, as before. Only the hits' own entities are
     * asked, so a caller learns no column name of an entity the search did not return.
     */
    static Map<String, Map<String, String>> fieldLabels(Collection<String> entityNames, Function<String, EntityManifest> find) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String entity : entityNames) {
            EntityManifest m = find.apply(entity);
            if (m == null || out.containsKey(entity)) continue;
            Map<String, String> labels = new LinkedHashMap<>();
            m.columns().forEach((column, meta) -> {
                if (meta != null && meta.label() != null && !meta.label().isBlank()) labels.put(column, meta.label().strip());
            });
            if (!labels.isEmpty()) out.put(entity, labels);
        }
        return out;
    }

    /**
     * The entities one search may read: visible to the caller (unscoped = all; bound = a scope in common),
     * narrowed to {@code scope} when the request names one. The grants are an argument so the arithmetic
     * is testable without the roles holder; an empty answer stays empty — never "every entity".
     */
    static Set<String> allowedFor(Collection<EntityManifest> all, Optional<Set<String>> grants, String scope) {
        Set<String> allowed = new TreeSet<>();
        for (EntityManifest m : all) {
            boolean visible = grants.map(g -> m.scopes().stream().anyMatch(g::contains)).orElse(true);
            if (visible && (scope == null || m.scopes().contains(scope))) allowed.add(m.name());
        }
        return allowed;
    }

    /** Never the raw query text — only what was hit and how many, per #673 D11. {@code auditKey} is
     *  the requested {@code entity} filter, or {@code "*"} for a cross-entity query — search has no
     *  single row identity of its own, and {@code entity_key} is a required column. */
    private void recordAudit(IndexedSearchResult result, String auditKey) {
        List<String> entities = result.hits().stream().map(IndexedSearchHit::entity).distinct().sorted().toList();
        audit.record("Search", auditKey, "READ", Map.of("entities", entities, "hitCount", result.hits().size()));
    }

    /**
     * The hit's business title (#1044): the value of the field its index declared as {@code titleField},
     * or null when the index declares none or this hit has no usable value there (blank; otherwise trimmed: legacy CHAR columns come padded) — the client then shows
     * the entity name, as before. Never inferred from the order of the returned fields.
     */
    private static String titleOf(IndexedSearchHit hit, String titleField) {
        if (titleField == null) return null;
        String value = hit.fields().get(titleField);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Object> hitBody(IndexedSearchHit hit, HitPresentation presentation) {
        String titleField = presentation == null ? null : presentation.titleField();
        String title = titleOf(hit, titleField);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity", hit.entity());
        body.put("title", title);
        body.put("titleField", title == null ? null : titleField);
        body.put("key", hit.key());
        body.put("score", hit.score());
        body.put("fields", hit.fields());
        body.put("highlights", hit.highlights());
        body.put("canonicalLink", canonicalLink(hit));
        // #1051: the templates as declared; the client fills them from `fields` (absent when none)
        if (presentation != null && !presentation.links().isEmpty()) body.put("links", presentation.links());
        return body;
    }

    /** Sorted so a composite key always produces the same link, regardless of the provider's own
     *  map iteration order. {@link UriBuilder} encodes the path segment and every query value. */
    private static String canonicalLink(IndexedSearchHit hit) {
        UriBuilder b = UriBuilder.fromPath("/api/entities/{name}");
        new TreeMap<>(hit.key()).forEach(b::queryParam);
        return b.build(hit.entity()).toString();
    }

    private static Map<String, Object> facetsBody(Map<String, List<FacetCount>> facets) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, List<FacetCount>> e : facets.entrySet()) {
            body.put(e.getKey(), e.getValue().stream()
                .map(f -> Map.of("value", f.value(), "count", f.count())).toList());
        }
        return body;
    }

    /**
     * {@code facet.<name>=value}, at most {@link #MAX_FACET_KEYS} distinct keys, each at most once —
     * {@link IndexedSearchQuery#filters()} is a {@code Map<String,String>}, so a repeated key can
     * only ever keep one value silently; refusing it is the honest answer. Unlike {@code
     * EntityReadResource.filtersFrom} (a different door, a different key namespace and validation
     * policy — canonical columns, first value wins), this one refuses rather than silently drops,
     * so the two are kept separate rather than forced under one shared parser.
     *
     * <p>{@code MAX_FACET_KEYS} is this door's own query-complexity bound (#673's size-cap
     * requirement), independent of how many facet fields any one {@code SearchIndex} declares —
     * a caller narrowing its own query, never a reflection of a hub-side limit.
     *
     * <p>Returns an error message to report as {@code 400}, or {@code null} on success — a plain
     * validation outcome, not an exception, so a genuine bug elsewhere in this method's call graph
     * is never mistaken for an intentional facet-validation refusal.
     */
    private static String collectFacetFilters(UriInfo uriInfo, Map<String, String> filters) {
        for (Map.Entry<String, List<String>> e : uriInfo.getQueryParameters().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(FACET_PREFIX)) continue;
            String field = key.substring(FACET_PREFIX.length());
            if (field.isBlank()) return "facet key must not be blank";
            List<String> values = e.getValue();
            if (values.size() > 1) return "facet '" + field + "' must not repeat";
            filters.put(field, values.get(0));
        }
        if (filters.size() > MAX_FACET_KEYS) {
            return "at most " + MAX_FACET_KEYS + " facet filters allowed";
        }
        return null;
    }
}
