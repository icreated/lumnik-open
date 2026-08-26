package io.lumnik.hub.workflow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 * manifest as applied, and the parser's identifier grammar is re-applied to whatever the row
 * holds. Do not memoize this, and do not start trusting the denormalized {@code on_table} /
 * {@code on_column} columns (they exist for lookups, not for serving).
 */
@ApplicationScoped
public class WorkflowRegistry {

    @Inject WorkflowManifestStore store;

    private final WorkflowManifestParser parser = new WorkflowManifestParser();

    public List<WorkflowGraph> all(long tenantId) {
        return store.listByTenant(tenantId).stream()
            .filter(WorkflowManifestStore.StoredWorkflow::enabled)
            .map(w -> WorkflowGraph.from(parser.parse(w.yaml())))
            .toList();
    }

    public Optional<WorkflowGraph> findByName(long tenantId, String name) {
        return store.findYaml(tenantId, name)
            .map(yaml -> WorkflowGraph.from(parser.parse(yaml)));
    }
}
