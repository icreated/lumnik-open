package io.lumnik.common.security;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The métier-scope grant primitive. Grant = Keycloak realm role {@code scope:<tag>}
 * (flows JWT groups → {@link CurrentRolesHolder} via the hub's auth filter).
 *
 * Opt-in semantics (ratified 2026-07-07, PR #131): a user with ZERO {@code scope:*}
 * roles is unscoped — tenant-wide access, behavior unchanged; a user with at least
 * one is bound — only the granted scopes may be read.
 */
public final class ScopeGrants {

    private static final String PREFIX = "scope:";

    /**
     * The single source of the refusal a bound user gets outside their grants — served as
     * {@code 403 {"error":"scope not granted"}} by every scoped door (chat, search, views,
     * entities). A FROZEN cross-language contract: the PWA, {@code lm} and the OTK oracle all
     * branch on these exact bytes, so the string lives here, next to the decision, and the
     * per-module response helpers (hub {@code ApiErrors}, rag {@code RagErrors}) quote it.
     */
    public static final String REFUSAL = "scope not granted";

    private ScopeGrants() {}

    /** Empty = unscoped user (tenant-wide); present = the bound set, prefix stripped. */
    public static Optional<Set<String>> granted() {
        Set<String> scopes = CurrentRolesHolder.get().stream()
            .filter(r -> r.startsWith(PREFIX))
            .map(r -> r.substring(PREFIX.length()))
            .collect(Collectors.toUnmodifiableSet());
        return scopes.isEmpty() ? Optional.empty() : Optional.of(scopes);
    }

    /** May this user read the named scope? Unscoped: always; a null scope is denied when bound. */
    public static boolean allows(String scope) {
        return granted().map(g -> scope != null && g.contains(scope)).orElse(true);
    }

    /**
     * May this user read a resource carrying these scope tags? Unscoped: always.
     * Bound: needs a non-empty intersection — a scopeless resource is invisible.
     */
    public static boolean allowsAny(Collection<String> scopes) {
        return granted()
            .map(g -> scopes != null && scopes.stream().anyMatch(g::contains))
            .orElse(true);
    }

    /**
     * May this user PUBLISH a resource carrying these scope tags? Unscoped: always.
     * Bound: needs every tag, and at least one — an overlap is not enough.
     *
     * <p>Reading asks {@link #allowsAny}, writing asks this. The asymmetry is the whole point and
     * it is easy to miss, because {@code allowsAny} is the only other collection-shaped method here:
     * a user holding {@code clients} who applies a manifest scoped {@code [clients, magasin]} passes
     * {@code allowsAny} and thereby publishes into {@code magasin}, which they do not hold. Seeing a
     * resource someone else tagged with one of your scopes is fine; creating one that lands in a
     * scope you were never granted is escalation.
     */
    public static boolean allowsAll(Collection<String> scopes) {
        return granted()
            .map(g -> scopes != null && !scopes.isEmpty() && g.containsAll(scopes))
            .orElse(true);
    }
}
