package io.lumnik.hub.connector.exceptions;

/**
 * A run failure whose message the hub wrote whole — no source value, no third-party text — so the run stores it as
 * written, wherever it sits in the failure's chain, without its cause's message (#1434). The cause still reaches the
 * hub's log and the run's {@code error_stack}, which no API returns. The exports' twin is {@code AuthoredFailure}.
 */
public interface AuthoredRunFailure {}
