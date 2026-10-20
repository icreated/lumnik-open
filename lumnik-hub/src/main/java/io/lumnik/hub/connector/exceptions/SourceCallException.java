package io.lumnik.hub.connector.exceptions;

import io.lumnik.hub.connector.rest.OutboundFailure;

/**
 * A call to the source that threw: its message is the failure's kind and class
 * ({@link OutboundFailure#describe}), never the exception's own message, which can quote the
 * source URL — userinfo, a token in the path or the query (#1166).
 *
 * <p>The message is computed here, from the cause, so no caller can put anything else in it. The
 * cause is kept as it was: a stack and every reader of the cause chain see the original. That is
 * also why a reader that would print the cause's message — a log line with a stack, the run text
 * that appends a root cause — must read this type's message alone.
 *
 * <p>A {@link ConnectorException} but not a {@link TransientFailureException}: the REST retry
 * executor has already spent its attempts on a network failure before this is raised, and the
 * plain {@code RuntimeException} this replaces was not retryable either.
 */
public class SourceCallException extends ConnectorException {

    public SourceCallException(Exception cause) {
        super(OutboundFailure.describe(cause), cause);
    }
}
