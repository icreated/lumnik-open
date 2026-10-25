package io.lumnik.identity;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@link KnownKeysOnly} refusal: {@code 400 {"error": "unknown key: entity_filter — accepted
 * keys: …"}}. It names the key and the keys the body accepts; it never quotes a value — the body may
 * carry a secret.
 *
 * <p>Deployment-wide, as a mapper is, and it changes no status: without it Quarkus' own
 * {@code MismatchedInputException} mapper answers the same 400, with an empty body outside dev and
 * test. Here, not in the hub's {@code ApiErrors}, because the users door lives in this module and its
 * own tests boot without the hub.
 */
@Provider
public class UnknownKeyExceptionMapper implements ExceptionMapper<UnrecognizedPropertyException> {

    @Override
    public Response toResponse(UnrecognizedPropertyException e) {
        Collection<Object> known = e.getKnownPropertyIds();
        String accepted = known == null ? "" : known.stream().map(String::valueOf).sorted()
            .collect(Collectors.joining(", "));
        return Response.status(400).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", "unknown key: " + e.getPropertyName() + " — accepted keys: " + accepted))
            .build();
    }
}
