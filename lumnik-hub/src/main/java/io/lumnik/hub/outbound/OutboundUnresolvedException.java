package io.lumnik.hub.outbound;

import io.lumnik.hub.connector.exceptions.AuthoredRunFailure;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.export.AuthoredFailure;

/**
 * A host that does not resolve, refused for a client that keeps resolving for the whole run — a Kafka consumer, an S3
 * client (#2002). Left to it, the client would look the name up again later, past the JVM's cache, and connect
 * wherever it then led, unchecked. A source that cannot be reached for now: the run retries it. The sentence is the
 * hub's, so a run and an export store it as written; it never names the host.
 */
public class OutboundUnresolvedException extends SourceUnreachableException implements AuthoredRunFailure, AuthoredFailure {

    public OutboundUnresolvedException() {
        super("host not resolved: the hub checks where a source leads before it connects, and this one leads nowhere yet", null);
    }
}
