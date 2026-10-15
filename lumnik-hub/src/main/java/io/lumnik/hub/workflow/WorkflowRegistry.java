package io.lumnik.hub.workflow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Serves WorkflowGraphs from the per-tenant manifest store. Until 2026-08 this scanned the
 * classpath for @DocumentWorkflow enums — a pre-pivot mechanism that could only ever see
 * lifecycles compiled into lumnik, never the customer's. The store sees what was declared
 * about the ingested data, which is the whole point.
 *
 * <p><b>Invariant.</b> Every read re-parses the stored YAML — deliberately, not for want of a
 * cache: the yaml_text column is the single truth, so a served graph can never drift from the
 * manifest as applied, and the parser's refusals are re-applied to whatever the row holds — the
 * watch-target confinement above all, since a served graph reaches SQL on a privileged connection. Do
 * not memoize this, and do not start trusting the denormalized {@code on_table} /
 * {@code on_column} columns (they exist for lookups, not for serving).
 */
@ApplicationScoped
public class WorkflowRegistry {

    private static final Logger LOG = Logger.getLogger(WorkflowRegistry.class);

    @Inject WorkflowManifestStore store;

    private final WorkflowManifestParser parser = new WorkflowManifestParser();

    public List<WorkflowGraph> all(long tenantId) {
        return store.listByTenant(tenantId).stream()
            .filter(WorkflowManifestStore.StoredWorkflow::enabled)
            .map(w -> WorkflowGraph.from(parser.parse(w.yaml())))
            .toList();
    }

    /**
     * What a background pass reads: the same re-parse as {@link #all}, but a stored row the parser
     * refuses is stepped over and reported instead of ending the pass (#807). The sweep walks every
     * tenant, so one manifest that stopped parsing — the parser tightened after it was applied —
     * would otherwise stop the lifecycle of every other tenant too.
     *
     * <p>The report is the stored name and the failure's class and nothing else, and the exception
     * is NOT handed to the logger: the parser's message quotes the YAML, and so does the stack.
     * {@link #all} stays strict — a served list that quietly omitted a workflow would hide the
     * very row an operator has to fix.
     *
     * <p>Only the parser's own refusal ({@code IllegalArgumentException}) is stepped over, and only
     * around the parse: anything else — a defect in the parser or in {@code WorkflowGraph.from} —
     * still fails the pass instead of passing for a refused manifest.
     */
    public List<WorkflowGraph> allParsable(long tenantId) {
        List<WorkflowGraph> out = new ArrayList<>();
        for (WorkflowManifestStore.StoredWorkflow w : store.listByTenant(tenantId)) {
            if (!w.enabled()) continue;
            WorkflowManifest manifest;
            try {
                manifest = parser.parse(w.yaml());
            } catch (IllegalArgumentException e) {
                LOG.warnf("workflow manifest skipped (tenant %d, workflow %s): stored YAML no longer"
                    + " parses (%s) — re-apply it", tenantId, w.name(), e.getClass().getSimpleName());
                continue;
            }
            out.add(WorkflowGraph.from(manifest));
        }
        return out;
    }

    public Optional<WorkflowGraph> findByName(long tenantId, String name) {
        return store.findYaml(tenantId, name)
            .map(yaml -> WorkflowGraph.from(parser.parse(yaml)));
    }
}
