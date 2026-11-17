package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.AskUnavailableException;
import io.lumnik.extraction.spi.ask.SqlGuard.SqlRejectedException;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Query;
import org.postgresql.jdbc.PreferQueryMode;

import java.sql.SQLException;

/**
 * The guarded SQL as the driver reads it (#2005). pgjdbc is the third reader between the guards and the server. On a
 * {@code Statement} it splits the text on {@code ;} with its own reading of comments — {@code /}{@code *}{@code /} is
 * a whole comment to it, the opening of one to the lexer and to the server — and rewrites inside a statement
 * ({@code ??} into {@code ?}, a tail after a {@code ;} it reads as code dropped). Asked of the executing connection,
 * through the driver's own path for a plain {@code Statement} without escape processing: what it would send must be
 * exactly one statement, the text the guards read, or it is refused like anything the guard refuses.
 */
final class DriverReading {

    /** How the guards read a string: {@code standard_conforming_strings} on, a backslash is data (#1988). */
    static final boolean GUARDS_STANDARD_STRINGS = true;

    static final String NO_STATEMENT = "the driver reads no statement";
    static final String SEVERAL_STATEMENTS = "the driver reads more than one statement";
    static final String OTHER_SQL = "the driver would send other SQL than the guards read";

    /**
     * A readonly connection that is not the one the guards assume: the deployment's fault, never the model's — the chat
     * door's 503, as for a readonly role that is not hardened (#1987).
     */
    static final class MisconfiguredConnection extends AskUnavailableException {
        MisconfiguredConnection(String message) {
            super("analytic ask is unavailable: " + message);
        }
    }

    private DriverReading() {}

    /**
     * Returns when {@code pg} sends {@code sql} as it is. The connection must read strings as the guards do — the Ask
     * transaction pins it ({@code TenantRlsScope}); off, {@code 'x\''} closes later to the driver and the server than
     * to the guards — and be in the {@code extended} query mode, the one this reading describes: in {@code simple} and
     * {@code extendedForPrepared} the server splits the text itself, and {@code extendedCacheEverything} runs a parse
     * from the connection's cache, keyed without the reading of strings.
     */
    static void sentBy(BaseConnection pg, String sql) throws SQLException {
        if (pg.getStandardConformingStrings() != GUARDS_STANDARD_STRINGS) {
            throw new MisconfiguredConnection("the readonly connection reads strings otherwise than the Ask guards"
                + " (standard_conforming_strings): Ask refuses to run on it");
        }
        if (pg.getPreferQueryMode() != PreferQueryMode.EXTENDED) {
            throw new MisconfiguredConnection("the readonly connection's query mode (" + pg.getPreferQueryMode()
                + ") is not the one the Ask guards' reading describes (extended): Ask refuses to run on it");
        }
        Query sent = pg.createQuery(sql, false, false).query;
        if (sent.getSubqueries() != null) throw new SqlRejectedException(SEVERAL_STATEMENTS);
        if (sent.getNativeSql().isBlank()) throw new SqlRejectedException(NO_STATEMENT);
        if (!sent.getNativeSql().equals(sql)) throw new SqlRejectedException(OTHER_SQL);
    }
}
