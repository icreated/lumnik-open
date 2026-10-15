package io.lumnik.hub.export;

/**
 * An export failure whose message the hub wrote itself — no credential, no third-party text, no
 * exported value — so a run's {@code failure_reason} may store it as written.
 * {@code ExportFailureText} checks this one type instead of a list of them.
 */
public interface AuthoredFailure {}
