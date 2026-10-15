package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.SearchAvailability;
import io.lumnik.hub.api.ApiErrors;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * What a client learns about Search before asking anything (#668). {@code reason} is a machine token:
 * {@code not_installed}, {@code ambiguous_provider}, {@code unknown_provider}, {@code status_failed},
 * {@code provider_error} (a query threw or returned nothing, or the provider cannot name itself),
 * or the provider's own degraded reason.
 */
public record SearchCapability(SearchAvailability state, String provider, String reason, String version) {

    static final String NOT_INSTALLED = "not_installed";
    static final String AMBIGUOUS_PROVIDER = "ambiguous_provider";
    static final String UNKNOWN_PROVIDER = "unknown_provider";
    static final String STATUS_FAILED = "status_failed";
    static final String PROVIDER_ERROR = "provider_error";

    static SearchCapability notInstalled() {
        return new SearchCapability(SearchAvailability.UNAVAILABLE, null, NOT_INSTALLED, null);
    }

    static SearchCapability degraded(String provider, String reason) {
        return new SearchCapability(SearchAvailability.DEGRADED, provider, reason, null);
    }

    /**
     * The one HTTP shape every operational door over Search answers with when it cannot proceed —
     * {@code 501} when not installed, {@code 503} (+ {@code Retry-After} when {@code
     * retryAfterSeconds} is given) when degraded — so the mapping cannot drift between doors the
     * way {@code SearchResource} and {@code SearchOperationsResource} once each carried their own
     * copy (#673 review). Never called for {@link SearchAvailability#AVAILABLE}; the caller's own
     * guard decides that.
     */
    public Response unavailableResponse(Integer retryAfterSeconds) {
        if (state == SearchAvailability.UNAVAILABLE) {
            return Response.status(501).type(MediaType.APPLICATION_JSON)
                .entity(ApiErrors.error("search not installed")).build();
        }
        Response.ResponseBuilder b = Response.status(503).type(MediaType.APPLICATION_JSON)
            .entity(ApiErrors.error("search degraded", "reason", reason));
        if (retryAfterSeconds != null) b.header("Retry-After", String.valueOf(retryAfterSeconds));
        return b.build();
    }
}
