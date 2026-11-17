package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Attests, before each Ask, that the role the model's SQL runs under cannot move the tenant boundary (#1987).
 *
 * <p>RLS binds that role to one tenant through {@code app.current_tenant}, and any role may overwrite a custom
 * setting: {@code set_config} inside a SELECT made RLS filter on the tenant the query chose, and
 * {@code query_to_xml} ran SQL held in a string literal, which no guard reads. Only a superuser can take those
 * functions away ({@code infra/postgres/init/02-extensions.sql}), so the hub cannot do it itself; it can refuse to
 * run Ask until it holds. Asked as the readonly role at boot, on every Ask and on every {@code /api/platform/health}
 * read: one catalog read each, and an operator who applies the file reopens Ask without a restart. Closed, the chat
 * door answers 503 ({@code AskUnavailableException}); readiness is not touched, the rest of the hub still serves.
 *
 * <p>{@code lumnik.ask.require-hardened-readonly=false} exists for {@code %test} only, whose readonly datasource
 * connects as the DevServices owner, which holds every privilege. Outside the test launch mode the hub refuses to
 * start with it. The proof is {@code ReadonlyHardeningRlsIT}, against the real {@code lumnik_readonly} with the
 * real file applied.
 */
@ApplicationScoped
public class ReadonlyHardening {

    private static final Logger LOG = Logger.getLogger(ReadonlyHardening.class);

    /** The pg_catalog functions 02-extensions.sql revokes, in its own words (ReadonlyHardeningInventoryTest). */
    static final String REVOKED_FUNCTIONS = "p.proname IN ('set_config', 'ts_stat', 'ts_rewrite', "
        + "'pg_cancel_backend', 'pg_terminate_backend') "
        + "OR p.proname ~ '^(query|table|cursor|schema|database)_to_xml' "
        + "OR p.proname ~ '^pg_(try_)?advisory_' "
        + "OR p.proname ~ '^pg_stat_get_(activity|backend_)'";

    /** pg_stat_activity (02-extensions.sql) and the platform tables (V92). */
    static final List<String> REVOKED_RELATIONS = List.of("pg_catalog.pg_stat_activity", "connector.connector",
        "connector.connector_credential", "connector.connector_dead_letter", "connector.connector_endpoint",
        "connector.connector_run", "connector.connector_cursor");

    /** Read back as the readonly role: one row per privilege it still holds; a column-level grant counts. */
    static final String STILL_OPEN = "SELECT p.oid::pg_catalog.regprocedure::text FROM pg_catalog.pg_proc p "
        + "WHERE p.pronamespace = 'pg_catalog'::pg_catalog.regnamespace AND (" + REVOKED_FUNCTIONS + ") "
        + "AND pg_catalog.has_function_privilege(p.oid, 'EXECUTE') "
        + "UNION ALL SELECT t FROM pg_catalog.unnest(ARRAY['" + String.join("', '", REVOKED_RELATIONS) + "']) t "
        // CASE, not AND: has_any_column_privilege raises on a missing relation, and AND does not fix the order
        + "WHERE CASE WHEN pg_catalog.to_regclass(t) IS NULL THEN false ELSE pg_catalog.has_any_column_privilege(t, 'SELECT') END "
        // a role RLS never binds has no boundary to harden
        + "UNION ALL SELECT 'the role itself: superuser or BYPASSRLS' FROM pg_catalog.pg_roles "
        + "WHERE rolname = current_user AND (rolsuper OR rolbypassrls)";

    /** What {@link #stillOpen} answers when the check could not run (pool, password, network): closed, but not a privilege. */
    public static final List<String> CHECK_FAILED = List.of("(the check itself failed)");

    /** Null until the first check, so the first verdict, open or closed, is logged. */
    private final AtomicReference<List<String>> lastSeen = new AtomicReference<>();

    @ConfigProperty(name = "lumnik.ask.require-hardened-readonly", defaultValue = "true")
    boolean required;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    @Inject ModuleManifestRegistry registry;

    /**
     * After the migrations (PlatformFlywayRunner is priority 2), so V92 has run: the first verdict is logged at boot —
     * only where analytic Ask exists (the rag module active), as {@code /api/platform/health} reports it.
     */
    void onStart(@Observes @Priority(20) StartupEvent ev) {
        if (!required && LaunchMode.current() != LaunchMode.TEST) {
            throw new IllegalStateException("lumnik.ask.require-hardened-readonly=false is for tests only: "
                + "Ask would run model-written SQL under a role that can switch tenant.");
        }
        if (registry.active().stream().anyMatch(m -> "rag".equals(m.id()))) stillOpen();
    }

    /** The privileges the readonly role still holds, empty when Ask may run. A check that cannot run keeps Ask closed. */
    public List<String> stillOpen() {
        if (!required) return List.of();
        List<String> open;
        RuntimeException failure = null;
        try {
            open = Jdbc.query(readonlyDs, "ReadonlyHardening.stillOpen", STILL_OPEN, ps -> {}, rs -> rs.getString(1));
        } catch (RuntimeException e) {
            open = CHECK_FAILED;
            failure = e;
        }
        // once per change, not once per question: a closed hub would otherwise log an ERROR for every Ask
        if (!open.equals(lastSeen.getAndSet(open))) {
            if (failure != null) {
                LOG.error("Ask is closed: the readonly role's privileges could not be checked", failure);
            } else if (open.isEmpty()) {
                LOG.info("Ask is open: the readonly role holds none of the privileges it must not hold.");
            } else {
                LOG.errorf("Ask is closed: the readonly role still holds %s. See "
                    + "docs/security/rls.md#what-the-read-only-role-cannot-call for how to remove each.", open);
            }
        }
        return open;
    }
}
