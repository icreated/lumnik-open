package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transactional READ must not write. Every read of the run ledger runs in a transaction (RLS
 * needs the tenant GUC, which only a transaction sets), so the ledger is loaded by
 * {@code lm run list}, the TUI's {@code :runs} poll, {@code lm run get} — while runs finish
 * underneath. If the loaded entities are flushed back at commit, the reader's stale copy
 * overwrites the run's own completion: a run that ended stays {@code Running} forever, with no
 * error and no log (observed live: two orphan runs, then reproduced at will with
 * {@code lm run list} in a loop; Postgres {@code log_statement=mod} showed one full-row
 * {@code UPDATE connector_run} per listed row, on rows nobody had touched).
 */
@QuarkusTest
class TransactionalReadIsNotAWriteIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorCursorEntityRepository cursors;
  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntityManager em;

  @Test
  void reading_a_run_does_not_overwrite_its_concurrent_completion() throws Exception {
    UUID runId = TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
      Connector c = connector();
      ConnectorRun run = new ConnectorRun();
      run.setTenantId(1L);
      run.setConnectorId(c.getId());
      run.setStatus("Running");
      run.setSyncMode("FullRefresh");
      run.setStartedAt(Instant.now());
      run.setTriggeredBy("MANUAL");
      run.setTriggeredMeta(JSON.valueToTree(Map.of("userId", 1)));   // what the engine stamps
      runs.persist(run);
      runs.flush();
      return run.getId();
    }));

    TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
      ConnectorRun listed = runs.findById(runId);                     // `lm run list`
      assertEquals("Running", listed.getStatus());
      // Meanwhile the run finishes on its own connection.
      int n = execSql("UPDATE connector.connector_run SET status = 'Completed', chunk_count = 1 WHERE id = '" + runId + "'");
      assertEquals(1, n, "the concurrent completion must reach the row");
      assertEquals("Completed", scalar("SELECT status FROM connector.connector_run WHERE id = '" + runId + "'"),
          "the completion is committed before the read commits");

      // The assertion after this block observes the CONSEQUENCE — the row rewritten to the
      // reader's stale `Running`. This one observes the CAUSE, in the only place it is visible:
      // a read that leaves the session dirty is a read that will write at commit, whatever the
      // row happens to say afterwards. Kept rather than used once: when this test fails in the
      // full suite and passes alone, the two assertions say which half moved.
      // Supplier, not String: the message argument of the String overload is built EAGERLY, so the
      // Hibernate introspection below would run on every green pass — paying for it, and worse,
      // able to fail for a reason that is not the defect (a null loaded state would NPE and turn a
      // passing run red). A diagnostic must cost nothing and risk nothing while the rule holds.
      assertFalse(em.unwrap(Session.class).isDirty(),
          () -> "a transactional READ must leave nothing to flush — a dirty session here IS the "
              + "write. Dirty: " + dirtyProperties(listed));
    }));                                                              // the read commits

    assertEquals("Completed", scalar("SELECT status FROM connector.connector_run WHERE id = '" + runId + "'"),
        "the reader's stale copy must not overwrite the run's completion");
  }

  private static String simpleName(String entityName) {
    return entityName.substring(entityName.lastIndexOf('.') + 1);
  }

  /**
   * WHICH property Hibernate believes changed, with both values — because "the session is dirty"
   * names the mechanism and not the cause, and this failure is rare enough that a run which
   * reproduces it must not have to be reproduced again to be understood. Hibernate's own dirty
   * check is asked, not re-implemented: the same {@code findDirty} the flush would consult.
   */
  private String dirtyProperties(Object entity) {
    var session = em.unwrap(SessionImplementor.class);
    EntityEntry entry = session.getPersistenceContextInternal().getEntry(entity);
    if (entry == null) return "(no persistence-context entry — the entity is detached?)";
    EntityPersister persister = entry.getPersister();
    Object[] loaded = entry.getLoadedState();
    if (loaded == null) return "(no loaded state — read-only or immutable entry)";
    Object[] current = persister.getValues(entity);
    int[] dirty = persister.findDirty(current, loaded, entity, session);
    if (dirty == null || dirty.length == 0) return "(findDirty says nothing — a collection, then?)";
    String[] names = persister.getPropertyNames();
    StringBuilder sb = new StringBuilder();
    for (int i : dirty) {
      sb.append(sb.isEmpty() ? "" : ", ").append(names[i])
        .append(" [loaded=").append(loaded[i]).append(" → now=").append(current[i]).append(']');
    }
    return sb.toString();
  }

  /**
   * The EFFECT the ledger depends on: no JSON column may answer "dirty" without comparing.
   *
   * <p>Hibernate's basic-type dirty check short-circuits to dirty when the mutability plan is
   * {@code MutableMutabilityPlan.INSTANCE}, values never read — the only reading of the
   * {@code loaded=null → now=null} the diagnostic above once reported. Which plan the application
   * gets was a race until {@link
   * io.lumnik.hub.connector.persistence.converters.JsonNodeTypeContributor} pinned it; that class
   * carries the mechanism and the measurements (#529). {@code JsonColumnMutabilityInventoryTest}
   * asks the DECLARATION; this asks the effect.
   *
   * <p>Asked of the live mapping metamodel rather than a list kept here: a {@code JsonNode} column
   * added to any entity tomorrow is swept the day it exists, and the anchor below fails the day
   * the sweep stops finding the column the defect was found on — a guard that inspects an empty
   * inventory passes for the wrong reason.
   */
  @Test
  void no_json_column_is_dirty_without_comparing_its_values() {
    var session = em.unwrap(SessionImplementor.class);
    boolean[] oneColumn = {true};
    List<String> alwaysDirty = new ArrayList<>();
    List<String> swept = new ArrayList<>();

    for (EntityPersister persister : session.getFactory().getMappingMetamodel()
        .streamEntityDescriptors().toList()) {
      String[] names = persister.getPropertyNames();
      Type[] types = persister.getPropertyTypes();
      for (int i = 0; i < names.length; i++) {
        if (!JsonNode.class.isAssignableFrom(types[i].getReturnedClass())) continue;
        swept.add(simpleName(persister.getEntityName()) + "." + names[i]);
        // Two nulls: any answer but "not dirty" is the value-blind branch, by construction.
        if (types[i].isDirty(null, null, oneColumn, session)) {
          alwaysDirty.add(simpleName(persister.getEntityName()) + "." + names[i]);
        }
      }
    }

    assertEquals(List.of(), alwaysDirty,
        () -> "these JSON columns are dirty on every read — a transactional read will write them "
            + "back. The plan is pinned by JsonNodeTypeContributor, not by the per-field "
            + "annotation: that is the knob to look at");
    assertTrue(swept.contains("ConnectorRun.triggeredMeta"),
        () -> "the sweep must still find the column the defect was found on, saw: " + swept);
  }

  @Test
  void reading_a_connector_does_not_overwrite_its_concurrent_rename() throws Exception {
    UUID id = TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> connector().getId()));

    TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
      Connector listed = connectors.findById(id);                     // `lm connector list`
      execSql("UPDATE connector.connector SET name = 'renamed-meanwhile' WHERE id = '" + id + "'");
    }));

    assertEquals("renamed-meanwhile", scalar("SELECT name FROM connector.connector WHERE id = '" + id + "'"),
        "the reader's stale copy must not overwrite the concurrent rename");
  }

  /** The cursor maps its JsonNode through an AttributeConverter and carries @Version. This case
   *  was GREEN before the fix: the converter path compares the converted value structurally, so
   *  it is documentation of the boundary, not proof of the fix — it turns red the day the cursor
   *  moves to @JdbcTypeCode(SqlTypes.JSON) without @Immutable (a stale flush would then throw on
   *  the version check and fail the READ). */
  @Test
  void reading_a_cursor_does_not_fail_on_its_concurrent_advance() throws Exception {
    UUID connectorId = TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> connector().getId()));
    UUID endpointId = UUID.randomUUID();
    execSql("INSERT INTO connector.connector_cursor (endpoint_id, connector_id, tenant_id, org_id, cursor_value, captured_at, "
        + "created_at, updated_at, created_by, updated_by, version) VALUES ('" + endpointId + "', '" + connectorId
        + "', 1, 0, '{\"pk\": 1}', now(), now(), now(), 0, 0, 0)");

    TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
      cursors.findById(endpointId);                                     // the engine reads the cursor each chunk
      execSql("UPDATE connector.connector_cursor SET cursor_value = '{\"pk\": 2}', version = version + 1 "
          + "WHERE endpoint_id = '" + endpointId + "'");                 // meanwhile another writer advances it
    }));

    assertEquals("{\"pk\": 2}", scalar("SELECT cursor_value::text FROM connector.connector_cursor WHERE endpoint_id = '" + endpointId + "'"));
  }

  private Connector connector() {
    Connector c = new Connector();
    c.setTenantId(1L);
    c.setName("read-is-not-write-" + System.nanoTime());
    c.setTypeId("hello-world");
    c.setConfig(Map.of("count", 1));
    connectors.persist(c);
    connectors.flush();
    return c;
  }

  private int execSql(String sql) {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      return st.executeUpdate(sql);
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  private String scalar(String sql) {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (Exception e) { throw new RuntimeException(e); }
  }
}
