package io.lumnik.hub.api;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A configuration the hub REFUSES is a 400 that says why — {@code 400 {"error": …}}
 * ({@link ApiErrors#badRequest}) — never a 500.
 *
 * <p>{@code POST /api/platform/connectors/apply} validates twice: the declarative validator answers
 * {@code 400} with a {@code ValidationResult}, then the type's own {@code validateConfig} runs inside
 * the apply handler, because "validator == runtime" is a discipline each connector holds by hand. The
 * two are meant to agree; when they drift, the runtime one throws — and without this mapper that throw
 * crossed the door undressed, so Quarkus answered a bodyless 500 and {@code lm} printed "no details
 * from the server" while the message naming the broken rule sat unread. Measured at the kafka bench
 * (a {@code spec.name} the ConfigDef did not shape-check and {@code validateConfig} did — that
 * particular drift is now closed, see below).
 *
 * <p>This is the throw-shaped twin of {@link NotFoundExceptionMapper}, one tier down: the body it
 * sends is the one {@code JdbcSourceResource} already hand-built for this very exception, which is the
 * proof the shape is right and that only its reach was missing.
 *
 * <p><b>Why a mapper and not a {@code catch} in the resource.</b> The apply door is
 * {@code @Transactional}. Catching inside the method and returning a {@code Response} COMMITS the
 * transaction; letting the exception propagate rolls it back and then lands here. Today kafka
 * validates before it writes, so nothing is yet at stake — but the next handler that validates after a
 * partial write would commit it. A form that cannot get this wrong beats a guard that has to remember.
 *
 * <p>Scoped to this leaf, deliberately. Its four siblings under {@code ConnectorException} never reach
 * an HTTP response: {@code MappingException} is thrown nowhere at all,
 * {@code UnknownConnectorTypeException} only from {@code ConnectorEngine}, {@code
 * CredentialNotFoundException} only through {@code ChunkExecutor} — all run tier — and {@code
 * TransientFailureException} is retried by {@code RetryExecutor} or already dressed by {@code
 * RestDiscoveryResource}. Mapping the {@code ConnectorException} base would buy nothing and would turn
 * an honest 5xx into a 400. {@code ConnectorExceptionReachInventoryTest} keeps that reasoning from
 * going stale.
 *
 * <p>Two doors catch this exception locally and stay untouched: {@code JdbcSourceResource} names the
 * table it refused, {@code RestDiscoveryResource} distinguishes a 502. A local catch that knows more
 * than the mapper should keep saying it.
 *
 * <p><b>What this javadoc will not claim is its reach.</b> A mapper is deployment-wide by nature, and
 * "the only door it moves" is exactly the kind of scope assertion that cannot be grepped and rots
 * unseen — an earlier draft of this file made one and was wrong. One neighbouring door was checked by
 * running it rather than reading it: the CSV upload path does NOT reach
 * {@code DynamicTableWriter.refuseSystemColumnCollisions}, because {@code CsvIngestService} builds a
 * {@code MappingTarget} whose {@code table()} is null and {@code MultiTargetWriter} only enters the
 * dynamic-table branch when it is not.
 *
 * <p><b>No live door reaches it today, and that is the point.</b> Closing kafka's {@code ConfigDef}
 * gap took away the one manifest that got this far: the refusal now lands at the declarative tier as
 * a {@code ValidationResult}. Two structural facts, not a survey — {@code KafkaConnectorApplyHandler}
 * holds the hub's only production call to {@code validateConfig}, and {@code ConnectorApplyResource}
 * is the only door to a handler and always validates first. A net whose drift is closed catches
 * nothing until the drift reopens, which is exactly the day it is needed and the day nobody is
 * watching. {@code ConfigValidationExceptionMapperTest} pins what it answers;
 * {@code ConnectorExceptionReachInventoryTest} pins that {@code @Provider} is still on it. Neither
 * proves the deployment DISCOVERS it — nothing does, since there is no longer a door to drive.
 */
@Provider
public class ConfigValidationExceptionMapper implements ExceptionMapper<ConfigValidationException> {

    @Override
    public Response toResponse(ConfigValidationException ex) {
        // No producer passes null today — every throw site writes a literal or a concatenation —
        // but the body is a frozen contract lm branches on, and "API error 400: null" would be its
        // own small lie. Pinned so the fallback cannot later be dropped as dead.
        String why = ex.getMessage();
        return ApiErrors.badRequest(
            why == null || why.isBlank() ? "invalid connector configuration" : why);
    }
}
