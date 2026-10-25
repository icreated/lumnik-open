package io.lumnik.hub.connector.jdbc;

import io.lumnik.common.error.Throwables;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.connector.rest.OutboundFailure;

/**
 * What a jdbc source that cannot be reached says, wherever the connection was asked for: the run's
 * target before the first chunk, or a chunk after the source went down (#1389).
 *
 * <p>The SQLSTATE is looked for down the chain: Hikari puts the driver's under its own timeout, and
 * a pool that cannot start throws a RuntimeException over it. The sentence names the failure's kind,
 * never a message: the pool's carries the user, the driver's the host.
 */
final class JdbcUnreachable {

    private JdbcUnreachable() {}

    /** The failure as a {@link SourceUnreachableException} when its SQLSTATE is connection-class (08…), else null. */
    static SourceUnreachableException of(String table, Throwable failure) {
        String state = Jdbc.sqlStateOf(failure);
        if (state == null || !state.startsWith("08")) return null;
        return new SourceUnreachableException("could not reach " + table + " (SQLSTATE " + state + "): "
            + OutboundFailure.describe(Throwables.causeChain(failure).getLast()), failure);
    }
}
