package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.testsupport.LogProbe;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #1646: {@link ApiErrors#status} is the one dress every error body is built in. */
class ApiErrorsStatusTest {

    @Test
    void any_status_goes_out_as_json_with_the_error_body() {
        Response r = ApiErrors.status(502, "source unreachable").build();
        assertThat(r.getStatus()).isEqualTo(502);
        assertThat(r.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        assertThat(r.getEntity()).isEqualTo(Map.of("error", "source unreachable"));
    }

    /** A door passing {@code e.getMessage()} must not trade its refusal for an NPE-shaped 500 (review). */
    @Test
    void a_missing_message_falls_back_to_the_status_reason_rather_than_failing() {
        assertThat(ApiErrors.status(502, null).build().getEntity()).isEqualTo(Map.of("error", "Bad Gateway"));
        assertThat(ApiErrors.badRequest(" ").getEntity()).isEqualTo(Map.of("error", "Bad Request"));
        assertThat(ApiErrors.status(422, null).build().getEntity()).isEqualTo(Map.of("error", "HTTP 422"));   // not in Jakarta's Status enum
    }

    /** The extra field may be null (it serialised as null before #1646), and follows the message. */
    @Test
    void the_extra_field_keeps_its_place_after_the_message_and_may_be_null() {
        Object body = ApiErrors.status(400, "generation failed", "raw", null).build().getEntity();
        Map<String, Object> expected = new HashMap<>();
        expected.put("error", "generation failed");
        expected.put("raw", null);
        assertThat(body).isEqualTo(expected);
        List<Object> keys = List.copyOf(((Map<?, ?>) ApiErrors.conflict("busy", "runId", 7).getEntity()).keySet());
        assertThat(keys).containsExactly("error", "runId");
    }

    /** The frozen scope refusal leaves its WARN whichever door builds it — not only scopeNotGranted(). */
    @Test
    void the_scope_refusal_is_logged_even_when_built_through_another_helper() {
        List<String> viaForbidden = LogProbe.warnings(ApiErrors.class, () -> ApiErrors.forbidden(ScopeGrants.REFUSAL));
        List<String> viaStatus = LogProbe.warnings(ApiErrors.class, () -> ApiErrors.status(403, ScopeGrants.REFUSAL));
        assertThat(viaForbidden).singleElement().asString().startsWith("Access denied: " + ScopeGrants.REFUSAL);
        assertThat(viaStatus).singleElement().asString().startsWith("Access denied: " + ScopeGrants.REFUSAL);
        assertThat(LogProbe.warnings(ApiErrors.class, ApiErrors::scopeNotGranted)).hasSize(1);   // once, not twice
        assertThat(LogProbe.warnings(ApiErrors.class, () -> ApiErrors.forbidden("user is deactivated"))).isEmpty();
    }
}
