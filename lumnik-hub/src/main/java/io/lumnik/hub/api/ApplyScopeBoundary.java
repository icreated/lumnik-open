package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import jakarta.ws.rs.WebApplicationException;

import java.util.List;
import java.util.Optional;

/**
 * The half of the métier boundary an apply door forgets: the scope the manifest ALREADY lives in.
 *
 * <p>Every apply door asks {@code ScopeGrants} about the scope the caller declares — may you publish
 * INTO this métier. None of them asked about the row they were about to overwrite. Each store upserts
 * on the NAME ({@code ON CONFLICT (tenant_id, name) DO UPDATE SET … scope = EXCLUDED.scope}, and its
 * four siblings), so a bound admin holding only {@code scope:magasin} could post a manifest named
 * after one that belongs to {@code scope:clients}, declare it under magasin, and the upsert rewrote
 * the row — name, scope and all. The clients manifest was gone, re-homed into a métier its owner does
 * not hold, and no refusal was spoken.
 *
 * <p>The name is half the address. This class answers the other half, with the same words as the
 * first: {@code 403 {"error":"scope not granted"}}, {@link ScopeGrants#REFUSAL} verbatim, so the PWA,
 * {@code lm} and the OTK oracle keep branching on one string.
 *
 * <p><b>What this does and does not hide.</b> A caller who holds the incoming scope and is refused
 * here learns one bit: that name is taken by a scope they do not hold. That bit cannot be hidden
 * while still refusing the overwrite — the only way to conceal it would be to answer 200 and lose the
 * manifest. What IS withheld is everything else: which scope owns it, and whether the scope exists at
 * all, because the refusal is byte-identical to the incoming one.
 *
 * <p>The predicate is {@link ScopeGrants#allowsAll}, not {@code allowsAny}, for the reason that
 * method's own javadoc gives: taking over a manifest tagged {@code [clients, magasin]} while holding
 * only {@code clients} publishes into magasin. Overwriting is a write, and a write asks for all of
 * them.
 */
public final class ApplyScopeBoundary {

    private ApplyScopeBoundary() {}

    /**
     * Refuses this apply when it would overwrite a manifest whose scopes the caller does not all
     * hold. Returns normally when it may proceed.
     *
     * <p><b>It throws rather than returning the refusal, and that is the point.</b> A method that
     * HANDS BACK a {@code Response} can have its result dropped — {@code refuseUnlessCallerHolds(…);}
     * on a line of its own compiles, refuses nothing, and still satisfies a source-scanning
     * inventory looking for the call. The five doors are policed by exactly such a sweep
     * ({@code ScopeBoundaryInventoryTest}), so the guard has to be a shape that cannot be half-used
     * rather than one that can be called and ignored.
     *
     * <p>The wire is unchanged because nothing intercepts it: no {@code ExceptionMapper} in the hub
     * handles {@code WebApplicationException} (only {@code NotFoundException},
     * {@code ConfigValidationException}, {@code BusinessException}), so JAX-RS serves the attached
     * response as given. {@code EntityApplyResource} has raised this same refusal by throwing since
     * before the four other doors did. <b>That last inch is measured</b>, by
     * {@code ScopeRefusalOnTheWireIT}: a caller bound to one métier posts the name of a manifest
     * living in another and reads back {@code 403 {"error":"scope not granted"}}, byte-identical to
     * the refusal the four returning doors serve.
     *
     * <p>{@code Optional.empty()} means NO row is stored under that name: creating one is not
     * re-homing, so it passes. That is the difference from the read doors' {@code refuseUnlessHeld},
     * where an absent row is a 404 — here an absent row is the ordinary case.
     * {@code Optional.of(List.of())} is a stored row with NO scopes, and a bound caller is refused
     * it: {@code allowsAll} requires at least one tag, the same rule that stops a bound user
     * creating a scopeless resource they could never see afterwards.
     *
     * @param storedScopes the scopes on the row this apply would overwrite; empty when the name is free
     * @throws WebApplicationException 403 {@link ScopeGrants#REFUSAL}, when the caller does not hold them all
     */
    public static void refuseUnlessCallerHolds(Optional<List<String>> storedScopes) {
        if (storedScopes.isEmpty()) return;
        if (!ScopeGrants.allowsAll(storedScopes.get())) {
            throw new WebApplicationException(ApiErrors.scopeNotGranted());
        }
    }

    /** {@link #refuseUnlessCallerHolds} for the single-scope kinds — View, Process, Workflow. */
    public static void refuseUnlessCallerHoldsScope(Optional<String> storedScope) {
        refuseUnlessCallerHolds(storedScope.map(List::of));
    }
}
