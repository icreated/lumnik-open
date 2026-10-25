package io.lumnik.common.security;

import java.util.Optional;

/**
 * The authenticated user's default scope, for the chat door when a request names none.
 *
 * <p>A <b>preference</b>, never a grant: whatever this returns is treated exactly as if the caller
 * had named it, and then held to {@link ScopeGrants} like any named scope — a bound user whose
 * default names a scope they do not hold is refused the same way {@code --scope} would be.
 *
 * <p>Consumed by the chat door only, on purpose: search is an integrator retrieval probe where a
 * blank scope already means "unfiltered", and the view doors take their scope from a manifest.
 * Implemented by the hub (it owns the user row); consumed by {@code lumnik-rag} through
 * {@code Instance<>}, the seam {@code AskPort} rides.
 */
public interface DefaultScopeSource {

    /** Empty when there is no user, no row, or no default set. Never blank. */
    Optional<String> forCurrentUser();
}
