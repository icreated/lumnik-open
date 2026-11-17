package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.exceptions.AuthoredRunFailure;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;

/**
 * A hybrid target names an existing table the hub cannot write through, refused before any DDL touches it, in a
 * sentence the hub wrote, so the run stores it as written: a table this hub's writer did not make (#1989, #2010) — one
 * of the hub's own under {@code connector}, or a foreign one — or one of its own that carries a policy it did not make.
 */
public class HybridTargetRefusedException extends ConfigValidationException implements AuthoredRunFailure {

    private HybridTargetRefusedException(String message) {
        super(message);
    }

    static HybridTargetRefusedException notMadeByTheHub(String table) {
        return new HybridTargetRefusedException(table
            + " is not a table this hub's writer made: name a new one under ext or connector as target.table");
    }

    static HybridTargetRefusedException widenedBy(String table, String policy) {
        return new HybridTargetRefusedException(table + " carries the policy " + policy
            + ", which the hub did not make and which widens what each tenant sees: drop it to write here again");
    }
}
