package io.lumnik.hub.entity.resolution;

import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Fires the incremental resolution pass at the end of a connector run (#613 lot #620, design doc
 * §7's first trigger) — same seam and posture as {@code SchemaCardRefresher}: an async CDI
 * observer on {@link ConnectorRunCompleted} (fired only for {@code Completed}/{@code Partial}
 * endings, never {@code Failed}/{@code Cancelled} — a run that wrote nothing needs no
 * re-resolution), failures logged and swallowed so a resolution error can never retroactively
 * fail a run that already finished successfully.
 *
 * <p>#622: {@link EntityResolutionPass} itself logs the {@code PassSummary} every trigger
 * produces (icreated's review on PR #645 — this hook used to log its own copy, which meant only
 * the incremental path got the summary; centralizing it means the manual replay door and even
 * {@code runRetry} — unwired to anything today — get it too, by construction).
 */
@ApplicationScoped
public class EntityResolutionRunHook {

  private static final Logger LOG = Logger.getLogger(EntityResolutionRunHook.class);

  @Inject EntityResolutionPass pass;

  void onRunCompleted(@ObservesAsync ConnectorRunCompleted ev) {
    try {
      pass.runIncremental(ev.tenantId(), ev.runId(), ev.tablesWritten());
    } catch (Exception e) {
      LOG.warnf(e, "EntityResolutionRunHook: incremental pass failed for tenant %s run %s — "
          + "a manual POST /api/entities/{name}/resolve remains available", ev.tenantId(), ev.runId());
    }
  }
}
