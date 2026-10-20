package io.lumnik.hub.api;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** #1293: the one form of "this step's IllegalArgumentException is the caller's 400". */
class ApiErrorsOrBadRequestTest {

    @Test
    void the_steps_value_is_returned_untouched() {
        assertThat(ApiErrors.orBadRequest(() -> "parsed")).isEqualTo("parsed");
    }

    @Test
    void an_illegal_argument_from_the_step_is_the_dressed_400_the_mapper_passes_through() {
        BadRequestException refused = catchThrowableOfType(BadRequestException.class,
            () -> ApiErrors.orBadRequest(() -> { throw new IllegalArgumentException("spec.on.table is required"); }));

        Response r = refused.getResponse();
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(r.getEntity()).isEqualTo(Map.of("error", "spec.on.table is required"));
        assertThat(new BadRequestExceptionMapper().toResponse(refused)).isSameAs(r);
    }

    /** The refusal keeps the step's IAE as its cause: a trace points at the step, not at ApiErrors (#1297 review). */
    @Test
    void the_refusal_keeps_the_steps_failure_as_its_cause() {
        var failure = new IllegalArgumentException("spec.on.table is required");
        BadRequestException refused = catchThrowableOfType(BadRequestException.class,
            () -> ApiErrors.orBadRequest(() -> { throw failure; }));
        assertThat(refused.getCause()).isSameAs(failure);
    }

    /** The mapper's own fallback: a message-less IAE would otherwise be Map.of(KEY, null), an NPE, a 500. */
    @Test
    void an_illegal_argument_with_no_message_is_still_the_400() {
        BadRequestException refused = catchThrowableOfType(BadRequestException.class,
            () -> ApiErrors.orBadRequest(() -> { throw new IllegalArgumentException(); }));
        assertThat(refused.getResponse().getEntity()).isEqualTo(Map.of("error", "bad request"));
    }

    @Test
    void any_other_failure_of_the_step_is_not_a_400() {
        var failure = new IllegalStateException("a defect, not the caller's input");
        assertThatThrownBy(() -> ApiErrors.orBadRequest(() -> { throw failure; })).isSameAs(failure);
    }

    @Test
    void a_check_with_no_value_is_refused_the_same_way() {
        BadRequestException refused = catchThrowableOfType(BadRequestException.class,
            () -> ApiErrors.checkOrBadRequest(() -> { throw new IllegalArgumentException("unknown column"); }));
        assertThat(refused.getResponse().getEntity()).isEqualTo(Map.of("error", "unknown column"));
    }

    @Test
    void a_check_that_passes_is_silent() {
        ApiErrors.checkOrBadRequest(() -> { });
    }
}
