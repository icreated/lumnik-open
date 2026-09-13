package io.lumnik.hub.api;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper's own contract, called directly — no container needed. It used to be the junior partner
 * of an end-to-end witness at the apply door; closing kafka's {@code ConfigDef} gap took that door
 * away, so this is what the mapper has. {@link ConfigValidationExceptionMapper}'s javadoc carries why
 * and what is no longer pinned — kept in one place because it is a scope claim, and those rot in
 * copies.
 */
class ConfigValidationExceptionMapperTest {

    private final ConfigValidationExceptionMapper mapper = new ConfigValidationExceptionMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Response r) {
        return (Map<String, Object>) r.getEntity();
    }

    @Test
    void a_refusal_is_a_400_carrying_the_message_the_connector_wrote() {
        Response r = mapper.toResponse(new ConfigValidationException(
            "config key 'name' must be a plain identifier (letters, digits, underscore; "
                + "not starting with a digit): 9orders"));

        assertThat(r.getStatus()).as("a refused configuration is the caller's 400, not the hub's 500")
            .isEqualTo(400);
        assertThat(bodyOf(r)).containsEntry(ApiErrors.KEY,
            "config key 'name' must be a plain identifier (letters, digits, underscore; "
                + "not starting with a digit): 9orders");
    }

    @Test
    void the_body_is_json_even_on_a_door_that_also_declares_yaml() {
        // The reason ApiErrors sets the type explicitly: two view resources declare JSON *and* YAML at
        // the class level, so an unnegotiated refusal could otherwise leave the frozen body in the
        // wrong dress.
        assertThat(mapper.toResponse(new ConfigValidationException("nope")).getMediaType())
            .isEqualTo(MediaType.APPLICATION_JSON_TYPE);
    }

    @Test
    void a_refusal_with_no_message_still_says_something() {
        // Not reachable from any current throw site — all ~40 write a literal. Pinned so the fallback
        // cannot be dropped as dead: the body is a frozen contract, and "API error 400: null" would be
        // its own small lie.
        assertThat(bodyOf(mapper.toResponse(new ConfigValidationException(null))))
            .containsEntry(ApiErrors.KEY, "invalid connector configuration");
        assertThat(bodyOf(mapper.toResponse(new ConfigValidationException("   "))))
            .containsEntry(ApiErrors.KEY, "invalid connector configuration");
    }
}
