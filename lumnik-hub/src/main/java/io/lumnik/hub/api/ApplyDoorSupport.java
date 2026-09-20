package io.lumnik.hub.api;

import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The two response-building steps that are genuinely byte-identical across the app-ladder apply
 * doors (View/App/Process/Workflow) — confirmed by reading all four, not assumed from their
 * similarity. Everything else about the doors (the scope checks, the {@code unknownKeys} CALL
 * itself, audit recording, App's extra default-boundary logic) stays inline in each door:
 * {@code ManifestKeyInventoryTest} and {@code ScopeBoundaryInventoryTest} sweep each
 * {@code *ApplyResource.java} file's own text for those literal calls, so hiding them behind a
 * shared method here would silently break the sweep for every door that stopped writing them
 * itself.
 */
public final class ApplyDoorSupport {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    private ApplyDoorSupport() {}

    /** Confirmed byte-identical across View/App/Process/Workflow (grep) — only the kind name
     *  and honoredSurface() differ. */
    public static Optional<Response> refuseUnknownKeys(
            String kind, List<String> unknown, String honoredSurface) {
        if (unknown.isEmpty()) return Optional.empty();
        return Optional.of(ApiErrors.badRequest("unknown key(s) in the manifest: "
            + String.join(", ", unknown) + " — kind:" + kind + " honors " + honoredSurface));
    }

    /** Confirmed byte-identical across the two doors that have this check (View, App) — same
     *  slug pattern, same message text; only the RESERVED_NAMES contents differ. Process and
     *  Workflow validate name shape inside their own parser instead and never call this. */
    public static Optional<Response> refuseReservedOrInvalidSlug(String name, Set<String> reserved) {
        if (SLUG.matcher(name).matches() && !reserved.contains(name)) return Optional.empty();
        return Optional.of(ApiErrors.badRequest("metadata.name must be a URL-safe slug ([a-z0-9_-], "
            + "starting with a letter or digit) and not one of " + reserved));
    }
}
