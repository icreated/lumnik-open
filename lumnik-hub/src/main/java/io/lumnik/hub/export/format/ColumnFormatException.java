package io.lumnik.hub.export.format;

import io.lumnik.hub.export.AuthoredFailure;

/**
 * A value a formatter cannot write: the run fails rather than deliver a half-formatted column. The
 * message is authored by the hub — it names the column and the formatter, never the value — so the
 * run ledger stores it as written.
 */
public class ColumnFormatException extends RuntimeException implements AuthoredFailure {

    public ColumnFormatException(String authoredReason) {
        super(authoredReason);
    }
}
