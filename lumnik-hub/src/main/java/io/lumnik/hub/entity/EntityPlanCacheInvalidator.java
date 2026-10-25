package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The one place that knows which pools read entity views, and recycles them after an entity apply (#1338).
 *
 * <p>An entity's view keeps its sources' types — dates (#1325), numbers (#1338) — so re-applying it once a source is
 * typed changes a column's type. pgjdbc prepares a statement server-side after five runs on a connection, and a
 * connection that had prepared a read of that view then answers "cached plan must not change result type" once: a 500
 * per pooled connection after the apply. Recycling the readers' pools drops those plans with their connections —
 * idle ones at once, those in use when they are returned ({@link AgroalDataSource.FlushMode#GRACEFUL}), never put back
 * in the pool. An apply is rare and integrator-only, so a few reconnections are its whole cost.
 *
 * <p>Not {@code autosave=conservative}: measured, it opens a subtransaction — with its own xid — for every
 * row-returning statement in a transaction, Hibernate's and the ingestion's {@code INSERT … RETURNING} included
 * (1,999 for 2,000 inserts, {@code cleanupSavepoints} or not), a subtransaction-overflow risk on every write path to
 * fix a rare apply.
 *
 * <p>A read already running when the apply commits began under the view as it was, and may still fail once: making it
 * atomic with a type change would need a coordination far heavier than the risk.
 *
 * <p><b>A datasource that reads entity views belongs in {@link #readers}.</b> {@code admin} does not: it writes
 * the views, it does not serve their reads.
 */
@ApplicationScoped
public class EntityPlanCacheInvalidator {

  private static final Logger LOG = Logger.getLogger(EntityPlanCacheInvalidator.class);

  @Inject AgroalDataSource defaultDs;
  @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

  /**
   * Called by {@link EntityViewCompiler#apply} once the view's DDL has committed — never on a refusal or a rollback.
   * Best-effort repair: it never throws, since the apply it follows has already happened. A pool that fails to flush is
   * logged and skipped, and never keeps the next one from being recycled.
   */
  public void afterEntityApplied(String entity) {
    for (AgroalDataSource ds : readers()) {
      try {
        ds.flush(AgroalDataSource.FlushMode.GRACEFUL);
      } catch (RuntimeException e) {
        LOG.warnf(e, "entity %s applied, but a reader pool could not be recycled: a read prepared on the old view may"
            + " fail once on its connections", entity);
      }
    }
    LOG.debugf("entity %s applied: the entity readers' pools are recycled (plans prepared on the old view go)", entity);
  }

  /** The pools whose connections may hold a prepared read of an entity view. */
  List<AgroalDataSource> readers() {
    return List.of(defaultDs, readonlyDs);
  }
}
