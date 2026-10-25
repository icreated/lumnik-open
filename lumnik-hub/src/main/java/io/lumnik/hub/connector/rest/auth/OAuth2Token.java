package io.lumnik.hub.connector.rest.auth;

import java.time.Instant;

/** An access token and when to stop using it: shortly before it expires (#1393). Never printed. */
record OAuth2Token(String value, Instant renewAt) {

    boolean usableAt(Instant now) {
        return now.isBefore(renewAt);
    }

    @Override public String toString() {
        return "OAuth2Token[<redacted>, renew at " + renewAt + "]";
    }
}
