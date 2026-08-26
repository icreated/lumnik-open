package io.lumnik.hub.connector.transport.hook;

/**
 * A {@code script_ref} that {@link FileSourceHookRunner} refuses to load — either it fails the
 * classpath-confinement check or it simply isn't found there. Extends {@link IllegalArgumentException}
 * so it still maps to a 400 via {@code CsvIngestResource}'s existing exception mapper, but as a
 * distinct type callers can preserve through their own error-wrapping (see
 * {@code CsvIngestService.ingestRemoteFile}) without having to preserve every
 * {@code IllegalArgumentException} that might cross that boundary — e.g. a bad {@code encoding}
 * value is a different kind of failure and keeps its existing (wrapped, 500) behaviour.
 */
public class HookScriptRefusedException extends IllegalArgumentException {
    public HookScriptRefusedException(String message) {
        super(message);
    }
}
