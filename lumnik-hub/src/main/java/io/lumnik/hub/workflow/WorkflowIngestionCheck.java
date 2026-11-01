package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code spec.check.trigger: ingestion} (#1589): after each completed run that wrote the watched table, the
 * declaration is confronted with the data again — the apply's comparison, its status values only
 * ({@link WorkflowDataTruth#checkValues}) — and a value no earlier report named raises one
 * {@code workflow.undeclared.<workflow>} event. Without it the check runs at apply only, and a spelling the
 * night's export brings stays unseen until someone re-applies.
 *
 * <p>Same seam and posture as {@code EntityResolutionRunHook}: an async observer of
 * {@link ConnectorRunCompleted} (never fired for a failed or cancelled run), failures logged and swallowed,
 * so a check can never fail a run that already ended. The memory of what was reported is
 * {@code core.workflow_undeclared_value} (V86), written with the event in one transaction on the admin
 * datasource under the due sweep's own lock and outbox statement. A value still undeclared on the next
 * run is not said twice, and two runs ending together cannot both say it: the primary key lets one
 * insert through.
 *
 * <p>The values themselves ride in the payload, not in the sentence: an inbox or an e-mail subject is
 * read by people the source column's content was never meant for (#1365's rule).
 */
@ApplicationScoped
public class WorkflowIngestionCheck {

    private static final Logger LOG = Logger.getLogger(WorkflowIngestionCheck.class);
    /** What comes back is what no earlier report named. */
    private static final String REMEMBER_SQL =
        "INSERT INTO core.workflow_undeclared_value (tenant_id, workflow_name, value) "
        + "SELECT ?, ?, unnest(?::text[]) ON CONFLICT DO NOTHING RETURNING value";

    @Inject WorkflowRegistry registry;
    @Inject WorkflowDataTruth truth;
    @Inject EntityRegistry entities;
    @Inject ObjectMapper json;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    void onRunCompleted(@ObservesAsync ConnectorRunCompleted ev) {
        try {
            checkAfter(ev);
        } catch (Exception e) {
            LOG.warnf(e, "workflow ingestion check failed for tenant %d run %s — re-applying the workflow "
                + "reports its undeclared values", ev.tenantId(), ev.runId());
        }
    }

    /** Package-private: the IT drives a run's end directly, without a connector. */
    void checkAfter(ConnectorRunCompleted ev) {
        for (WorkflowGraph g : registry.checkedOnIngestion(ev.tenantId(), t -> touched(t, ev.tablesWritten()))) {
            try {
                checkOne(ev, g);
            } catch (RuntimeException e) {
                // one broken declaration never stops the others
                LOG.errorf(e, "workflow ingestion check failed for tenant %d workflow %s — skipped",
                    ev.tenantId(), g.name());
            }
        }
    }

    /**
     * Did this run write what the workflow reads? {@code null} means the run could not tell, so it may have.
     * A fused view reads the tables its entity fuses and looks up — the run names those, never the view.
     * The registry's cache answers, never a reload: this runs for every workflow over a view on every run,
     * and an entity it does not hold leaves any write a suspect — the memory makes a needless check cost a
     * query, never a second event. Package-private for the IT.
     */
    boolean touched(String table, Set<String> written) {
        if (written == null) return true;
        String entity = EntityViewCompiler.entityNameFromView(table);
        if (entity == null) return written.contains(table);
        EntityManifest m = entities.find(entity);
        if (m == null) return !written.isEmpty();
        return Stream.concat(
                Stream.ofNullable(m.sources()).flatMap(List::stream).filter(Objects::nonNull).map(EntityManifest.Source::table),
                Stream.ofNullable(m.joins()).flatMap(List::stream).filter(Objects::nonNull).map(EntityManifest.Join::table))
            .anyMatch(t -> written.contains("connector." + t));
    }

    private void checkOne(ConnectorRunCompleted ev, WorkflowGraph g) {
        long tenant = ev.tenantId();
        // The check issues SET LOCAL, so it needs a transaction of its own — as at the apply door.
        WorkflowDataTruth.Report report = QuarkusTransaction.requiringNew().call(() -> truth.checkValues(tenant, g));
        // An unavailable probe logged itself; a missing table has nothing to say yet.
        if (report.checkUnavailable() || report.undeclared().isEmpty()) return;
        Jdbc.transaction(adminDs, "WorkflowIngestionCheck.remember", c -> {
            if (!Jdbc.exists(c, "WorkflowIngestionCheck.stillDeclared", WorkflowDueSweep.STILL_DECLARED_SQL, ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, g.name());
            })) return;
            Set<String> fresh = new HashSet<>(Jdbc.query(c, "WorkflowIngestionCheck.remember", REMEMBER_SQL, ps -> {
                ps.setLong(1, tenant);
                ps.setString(2, g.name());
                ps.setArray(3, c.createArrayOf("text",
                    report.undeclared().stream().map(WorkflowDataTruth.ValueCount::value).toArray()));
            }, rs -> rs.getString(1)));
            List<WorkflowDataTruth.ValueCount> news =
                report.undeclared().stream().filter(v -> fresh.contains(v.value())).toList();
            if (!news.isEmpty()) emit(c, ev, g, news, report);
        });
    }

    private void emit(Connection c, ConnectorRunCompleted ev, WorkflowGraph g,
                      List<WorkflowDataTruth.ValueCount> news, WorkflowDataTruth.Report report) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        String watched = g.table() + "." + g.column();
        p.put("message", news.size() == 1
            ? "1 new value of " + watched + " matches no state of workflow " + g.name()
                + " — declare it as an alias, or correct the source"
            : news.size() + " new values of " + watched + " match no state of workflow " + g.name()
                + " — declare them as aliases, or correct the source");
        p.put("workflow", g.name());
        p.put("table", g.table());
        p.put("column", g.column());
        p.put("values", news.stream().map(v -> Map.of("value", v.value(), "rows", v.rows())).toList());
        p.put("undeclared", report.undeclared().size());
        p.put("null_or_blank", report.nullOrBlank());
        p.put("run_id", ev.runId());
        String payload = json.writeValueAsString(p);
        Jdbc.update(c, "WorkflowIngestionCheck.emit", WorkflowDueSweep.EMIT_SQL, ps -> {
            ps.setLong(1, ev.tenantId());
            ps.setString(2, "workflow.undeclared." + g.name());
            ps.setString(3, "workflow:" + g.name());
            ps.setString(4, g.table());
            ps.setString(5, WorkflowDormancySweep.bounded(g.name()));
            ps.setString(6, payload);
        });
    }
}
