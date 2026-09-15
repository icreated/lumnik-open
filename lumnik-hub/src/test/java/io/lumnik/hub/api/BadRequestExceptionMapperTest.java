package io.lumnik.hub.api;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper's own contract, called directly — no container needed. The wire witness that the
 * deployment DISCOVERS it is {@link BadRequestExceptionMapperIT}; this pins what it answers.
 */
class BadRequestExceptionMapperTest {

    private final BadRequestExceptionMapper mapper = new BadRequestExceptionMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Response r) {
        return (Map<String, Object>) r.getEntity();
    }

    @Test
    void a_refusal_thrown_with_a_message_carries_it_in_the_frozen_body() {
        Response r = mapper.toResponse(new BadRequestException("tenantId query param required"));

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(bodyOf(r)).containsEntry(ApiErrors.KEY, "tenantId query param required");
    }

    @Test
    void a_refusal_that_already_carries_a_body_is_returned_untouched() {
        // The trap BadRequestExceptionMapper's javadoc explains, pinned as its unit witness: the
        // synthesised message asserted below is what a naive mapper would have sent instead.
        BadRequestException rich = new BadRequestException(ApiErrors.badRequest("invalid UUID: warehouses"));
        // What the naive mapper would send instead. Asserted by what it must NOT be, not by its exact
        // text: that phrase is JAX-RS', not ours, and pinning a third party's string verbatim makes the
        // test fail on an upgrade for a reason the claim does not depend on.
        assertThat(rich.getMessage())
            .as("JAX-RS synthesises a message from the STATUS, so getMessage() cannot carry the body")
            .doesNotContain("invalid UUID")
            .contains("400");

        assertThat(bodyOf(mapper.toResponse(rich)))
            .containsEntry(ApiErrors.KEY, "invalid UUID: warehouses");
    }

    @Test
    void a_refusal_with_no_message_still_says_something() {
        // Reachable: the no-arg constructor leaves a null-ish message, and a framework-raised 400 need
        // not carry one either. "API error 400: null" would be its own small lie.
        assertThat(bodyOf(mapper.toResponse(new BadRequestException((String) null))))
            .containsEntry(ApiErrors.KEY, "bad request");
        assertThat(bodyOf(mapper.toResponse(new BadRequestException("   "))))
            .containsEntry(ApiErrors.KEY, "bad request");
    }
}
