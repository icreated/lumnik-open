package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

/**
 * ConnectorType registration for the REST connector.
 *
 * <p>Registered in the {@link io.lumnik.hub.connector.registry.ConnectorTypeRegistry} via the
 * {@link ConnectorTypeId} qualifier; the connector engine drives it like any other type.
 *
 * <p>The paginated fetch + write runs entirely through the engine's chunk loop: {@link #reader(ConnectorContext)}
 * returns a {@link RestEndpointReader} (one endpoint = one chunk, full pagination in-memory) and
 * {@link #writer(ConnectorContext)} a {@link RestEndpointWriter} (hybrid JSONB write via MultiTargetWriter).
 * This is the only execution path — the former direct {@code RestConnectorRuntime} path was retired.
 */
@ConnectorTypeId(RestConnectorType.KIND)
@ApplicationScoped
public class RestConnectorType implements ConnectorType {

    public static final String KIND = "rest-generic";

    @Inject ConnectorEndpointRepository epRepo;
    @Inject AuthProviderRegistry authReg;
    @Inject PaginationFactory paginationFactory;
    @Inject RateLimiterRegistry rateRegistry;
    @Inject RestHttpClient http;
    @Inject MultiTargetWriter multiTargetWriter;
    @Inject ObjectMapper mapper;

    @Override
    public String id() { return KIND; }

    @Override
    public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }

    @Override
    public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL, TriggerMode.POLL); }

    /**
     * The one spelling of "this config can address a source at all". Static because apply is not
     * the only door: the flat connector CRUD persists a config without running this class at all,
     * so whatever composes a request from a stored row must be able to refuse it by the same name.
     */
    public static String requireBaseUrl(Map<String, Object> config) {
        Object baseUrl = config.get("base_url");
        if (!(baseUrl instanceof String s) || s.isBlank())
            throw new ConfigValidationException("rest-generic requires 'base_url'");
        Urls.whyNotAbsolute(s).ifPresent(why -> {
            throw new ConfigValidationException("rest-generic 'base_url' " + why);
        });
        return s;
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        requireBaseUrl(config);
        // auth is OPTIONAL — an omitted auth block means `none` at runtime (e.g. 10-offset-none.yaml).
        // When present, auth.kind is validated cross-field by RestConnectorValidator/AuthBlockValidator.
    }

    /**
     * ItemReader bridge for the connector engine.
     *
     * <p>One endpoint = one chunk: {@link RestEndpointReader} runs the full pagination loop in-memory
     * (auth + pagination + rate-limit + retry + http) and emits every item in a single
     * {@link ReadResult.HasMore}, then short-circuits to end-of-stream on the next call — letting
     * {@code ConnectorEngine.runSync} ingest a {@code rest-generic} connector through the standard loop.
     *
     * <p>The endpoint row is resolved HERE, not inside {@code read()}: the engine builds the reader
     * inside a transaction but runs the read outside one, so a lookup during the read would miss the
     * tenant GUC and, on an RLS-enforced datasource, find nothing.
     */
    @Override
    public ItemReader<?> reader(ConnectorContext ctx) {
        return new RestEndpointReader(epRepo.findById(ctx.endpointId()),
            authReg, paginationFactory, rateRegistry, http, mapper);
    }

    /** ItemWriter bridge: writes the collected batch via the runtime's hybrid JSONB write path. */
    @Override
    public ItemWriter<?> writer(ConnectorContext ctx) {
        return new RestEndpointWriter(multiTargetWriter);
    }
}
