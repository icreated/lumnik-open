package io.lumnik.hub.connector.exceptions;

/**
 * Thrown by {@code ConnectorType.runStart} when measuring the run's target proved the source
 * cannot be reached at all — not that the measurement was unavailable, but that the read cannot
 * succeed either.
 *
 * <p>Measuring a target is a diagnostic, and {@code ConnectorEngine.runStartOf} owns the policy
 * that a diagnostic must not decide a run's fate: any other failure degrades to an unbounded run
 * with a WARN, and the first chunk then fails on its own with the source's own message. That is
 * right when the diagnostic and the read can disagree. It is waste when they cannot: against a
 * dead broker the measurement spent its full metadata timeout, said "reading unbounded", and the
 * reader spent the very same timeout to learn the very same thing — 120 s and 198 client WARN
 * lines for one answer (#486).
 *
 * <p>So a type raises THIS when it already holds that answer, and the engine fails the run there.
 * The bar is deliberately high: only a failure that no reader could survive belongs here, because
 * every use of it turns a degradation into a refusal. A type that is unsure throws anything else
 * and keeps the old, forgiving path.
 *
 * <p>A {@link TransientFailureException}, and not a new root: a source that is down is the textbook
 * transient failure, and this family already carries what a caller needs to know about one —
 * {@code isRetryable()}, which {@code ChunkExecutor} reads off the type for every other failure.
 * Declaring a second, parallel notion of "retryable" beside it is how the two come to disagree.
 */
public class SourceUnreachableException extends TransientFailureException {

    public SourceUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
