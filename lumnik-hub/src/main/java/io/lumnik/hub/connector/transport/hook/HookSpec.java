package io.lumnik.hub.connector.transport.hook;

/**
 * Declares a Kotlin (.kts) hook script to run at a specific extension point.
 *
 * @param kind      Extension point name: shouldProcess | transform | postProcess
 * @param scriptRef Classpath-relative path under META-INF/hooks/connectors/csv-file/.
 *                  Absolute paths and '..' traversal are refused — this is reachable from
 *                  the CSV ingest HTTP endpoint, so it must never read an arbitrary file.
 */
public record HookSpec(String kind, String scriptRef) {}
