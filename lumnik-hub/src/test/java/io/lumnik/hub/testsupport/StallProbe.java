package io.lumnik.hub.testsupport;

import io.lumnik.hub.connector.rls.RlsEnforcedPgResource;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Callable;

/**
 * A photograph of the database at the moment a test's wait gives up: which sessions are doing
 * something, which wait on a lock and on whom, and the locks that are not granted or are held by a
 * blocker. The nightly stall of {@code ExportAcceptanceS3IT} left no such evidence, so every
 * hypothesis about it (a snapshot-read transaction that stays open) stayed a hypothesis (#761).
 *
 * <p>The probe opens its own superuser connection, so it cannot queue behind the very lock it is
 * asked about, and bounds its own statements. That superuser exists only under
 * {@code RlsEnforcedProfile} (every export acceptance IT runs under it); elsewhere the report says
 * the probe failed to connect, it does not guess. It reports what it could read, and never throws: a
 * failing probe must not replace the failure it was called to explain.
 *
 * <p>It says nothing about waits that are not in the database (a relay that never releases, a
 * thread that does not return). An empty report is itself a finding: the database was not the stall.
 */
public final class StallProbe {

    private static final Logger LOG = Logger.getLogger(StallProbe.class);

    private static final String SESSIONS =
        "SELECT pid, usename, state, coalesce(wait_event_type || '/' || wait_event, '-'), "
      + "       coalesce(round(extract(epoch FROM now() - xact_start)::numeric, 1)::text, '-'), "
      + "       round(extract(epoch FROM now() - state_change)::numeric, 1), "
      + "       pg_blocking_pids(pid)::text, replace(left(query, 200), E'\\n', ' ') "
      + "FROM pg_stat_activity "
      + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
      + "  AND backend_type = 'client backend' AND state IS DISTINCT FROM 'idle' "
      + "ORDER BY pid";

    private static final String LOCKS =
        "SELECT l.pid, l.mode, l.granted, coalesce(l.relation::regclass::text, l.locktype) "
      + "FROM pg_locks l "
      + "WHERE l.pid <> pg_backend_pid() "
      + "  AND (NOT l.granted OR l.pid IN (SELECT unnest(pg_blocking_pids(pid)) FROM pg_stat_activity)) "
      + "ORDER BY l.pid, l.granted, l.mode";

    private StallProbe() {}

    /** Runs {@code wait}; if it fails for any reason, logs {@link #report()} at ERROR and rethrows. */
    public static <T> T diagnosing(String what, Callable<T> wait) throws Exception {
        return diagnosing(what, null, wait);
    }

    /**
     * {@link #diagnosing(String, Callable)} for a wait whose stall is not in the database: on
     * failure the log also carries {@code evidence} (a relay's state, a container's last lines),
     * read only then. Evidence that itself fails is reported as such; it never replaces the failure.
     */
    public static <T> T diagnosing(String what, Callable<String> evidence, Callable<T> wait) throws Exception {
        try {
            return wait.call();
        } catch (Throwable t) {
            LOG.errorf("%s did not complete (%s);%n%sdatabase at that moment:%n%s", what, t,
                evidence == null ? "" : "evidence:\n" + evidenceText(evidence) + "\n", report());
            throw t;
        }
    }

    private static String evidenceText(Callable<String> evidence) {
        try {
            return evidence.call();
        } catch (Exception e) {
            return "(the evidence itself failed: " + e + ")";
        }
    }

    public static String report() {
        StringBuilder out = new StringBuilder("sessions (not idle):\n");
        try {
            String url = ConfigProvider.getConfig()
                .getValue("quarkus.datasource.\"admin\".jdbc.url", String.class);
            try (Connection c = DriverManager.getConnection(url, RlsEnforcedPgResource.SUPERUSER,
                    RlsEnforcedPgResource.SUPERUSER_PASSWORD);
                 Statement st = c.createStatement()) {
                st.execute("SET statement_timeout = '5s'");
                st.execute("SET lock_timeout = '2s'");
                try (ResultSet rs = st.executeQuery(SESSIONS)) {
                    while (rs.next()) {
                        out.append("  pid=").append(rs.getLong(1)).append(" user=").append(rs.getString(2))
                            .append(" state=").append(rs.getString(3)).append(" wait=").append(rs.getString(4))
                            .append(" xact_age_s=").append(rs.getString(5))
                            .append(" state_age_s=").append(rs.getString(6))
                            .append(" blocked_by=").append(rs.getString(7).replace("{", "[").replace("}", "]"))
                            .append(" query=").append(rs.getString(8)).append('\n');
                    }
                }
                out.append("locks (not granted, or held by a blocker):\n");
                try (ResultSet rs = st.executeQuery(LOCKS)) {
                    while (rs.next()) {
                        out.append("  pid=").append(rs.getLong(1)).append(" mode=").append(rs.getString(2))
                            .append(" granted=").append(rs.getBoolean(3))
                            .append(" on=").append(rs.getString(4)).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            out.append("  (the probe itself failed: ").append(e).append(")\n");
        }
        return out.toString();
    }
}
