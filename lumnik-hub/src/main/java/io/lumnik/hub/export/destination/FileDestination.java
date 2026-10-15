package io.lumnik.hub.export.destination;

import java.io.OutputStream;

/**
 * Write-then-atomically-publish lifecycle for one export artifact — the writer-side counterpart
 * to {@link io.lumnik.hub.connector.transport.FileSource}, deliberately not folded into it: a
 * remote inbox read by cursor and an outbound artifact published exactly once have different
 * contracts.
 *
 * <p>Ownership contract, so a caller (the future export runner, #680) never has to know a given
 * transport's details or remember to close the stream itself:
 * <ul>
 *   <li>{@link #open()} may succeed at most once per instance.</li>
 *   <li>{@link #commit()} closes the stream first, then publishes the artifact exactly once,
 *       atomically and without clobbering an existing final artifact. A stream-close failure
 *       aborts and is never followed by a publish attempt.</li>
 *   <li>A successful {@link #commit()} returns the {@link PublishedArtifact} receipt, exactly once.
 *       A failed {@link #commit()} throws and returns nothing.</li>
 *   <li>{@link #abort()} closes the stream if it is still open, then best-effort removes the
 *       temporary artifact. A cleanup failure never masks the primary failure that caused the
 *       abort.</li>
 *   <li>{@link #close()} without a preceding {@link #commit()} behaves exactly like
 *       {@link #abort()}.</li>
 *   <li>Every transition attempted after {@link #commit()} fails stably (an unchecked exception),
 *       rather than silently doing nothing. {@link #abort()} is the one exception: it is
 *       idempotent, so a repeated call after an abort is a no-op, not a failure.</li>
 *   <li>Only {@link #close()} releases the underlying transport (the connection/session a given
 *       implementation holds to reach the destination). {@link #commit()} and {@link #abort()} do
 *       not — a caller must always follow either with {@link #close()} (or use try-with-resources)
 *       to release it.</li>
 * </ul>
 *
 * <p>The caller must not close the {@link OutputStream} returned by {@link #open()} directly —
 * {@link #commit()}/{@link #abort()} own closing it, since the artifact must never be renamed
 * into place with an unflushed or partially-written stream still open.
 */
public interface FileDestination extends AutoCloseable {

    OutputStream open();

    PublishedArtifact commit();

    void abort();

    @Override
    void close();
}
