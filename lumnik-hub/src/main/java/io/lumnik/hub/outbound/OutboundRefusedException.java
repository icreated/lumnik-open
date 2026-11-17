package io.lumnik.hub.outbound;

import io.lumnik.hub.connector.exceptions.AuthoredRunFailure;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.export.AuthoredFailure;

/**
 * An address the hub will not connect to (#2002), or one it cannot tell. A configuration problem, so a door answers
 * {@code 400}; a sentence the hub wrote — the range and the switch, never the address — so a connector run and an
 * export run both store it as written, and an export counts it as its definition to fix. Never retried.
 */
public class OutboundRefusedException extends ConfigValidationException implements AuthoredRunFailure, AuthoredFailure {

    public OutboundRefusedException(String message) {
        super(message);
    }
}
