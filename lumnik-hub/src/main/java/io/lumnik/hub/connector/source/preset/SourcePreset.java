package io.lumnik.hub.connector.source.preset;

import java.util.List;
import java.util.Map;

/**
 * A named connector-source vendor convention that expands to a real connector config
 * (Open/Closed: add a bean). Mirrors the REST {@code PaginationPreset} mechanism so that
 * "validator == runtime" holds: the engine/type only ever see the expanded config.
 *
 * <p>In manifests, the vendor alias is carried as an internal {@code spec.preset} field.
 */
public interface SourcePreset {

    /** The manifest kind that selects this preset, e.g. {@code "event-hubs"}. */
    String kind();

    /** Expand a user config into a real connector config (e.g. a {@code kind: kafka} config). */
    Map<String, Object> expand(Map<String, Object> config);

    /**
     * Input keys the user MUST supply (non-blank) for {@link #expand} to produce a usable config.
     * Expansion substitutes missing placeholders with empty strings, so without an up-front check an
     * incomplete spec would expand into garbage (e.g. {@code brokers: .servicebus.windows.net:9093});
     * both front doors (validator + apply) reject these before expanding. Default: none.
     */
    default List<String> requiredInputs() { return List.of(); }
}
