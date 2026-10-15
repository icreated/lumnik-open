package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.resolution.EntityResolutionPass.PassSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EntityResolutionPassCoverageTest {

  static final long T = 1L;
  final UUID run = UUID.randomUUID();
  final EntityRegistry registry = mock(EntityRegistry.class);
  final ResolutionCoverageStore coverage = mock(ResolutionCoverageStore.class);
  final EntityResolutionPass pass = spy(new EntityResolutionPass());
  final EntityManifest alpha = resolution("Alpha", "t_cov748_alpha");
  final EntityManifest beta = resolution("Beta", "t_cov748_beta");

  @BeforeEach
  void wire() {
    pass.registry = registry;
    pass.coverage = coverage;
    pass.statistics = mock(CrosswalkStatistics.class);
  }

  private static EntityManifest resolution(String name, String table) {
    return new EntityManifestParser().parse("kind: Entity\nname: " + name + "\nsources:\n  - table: " + table
        + "\n    map:\n      CUSTOMER_ID: customer_id\n      EMAIL_COL: email\nresolution:\n  identity:\n    "
        + table + ": customer_id\n  cascade:\n    - id: email\n      exact: { fields: [email] }\n");
  }

  @Test
  void incrementalCoversAnEntityOnlyAfterItsOwnSuccessAndRethrowsTheFirstFailure() {
    doReturn(List.of(alpha, beta)).when(registry).all();
    doReturn(PassSummary.EMPTY).when(pass).resolveEntityIncremental(T, alpha, run, null);
    doThrow(new IllegalStateException("boom")).when(pass).resolveEntityIncremental(T, beta, run, null);

    assertThatThrownBy(() -> pass.runIncremental(T, run)).hasMessage("boom");

    verify(coverage).cover(T, "alpha", List.of(run));
    verify(coverage, never()).cover(eq(T), eq("beta"), any());
  }

  @Test
  void aFailingEntityDoesNotStopItsSiblings() {
    doReturn(List.of(beta, alpha)).when(registry).all();          // the failing one comes FIRST
    doThrow(new IllegalStateException("boom")).when(pass).resolveEntityIncremental(T, beta, run, null);
    doReturn(PassSummary.EMPTY).when(pass).resolveEntityIncremental(T, alpha, run, null);

    assertThatThrownBy(() -> pass.runIncremental(T, run)).hasMessage("boom");

    verify(coverage).cover(T, "alpha", List.of(run));
  }

  @Test
  void aManifestWithoutResolutionIsNeitherRunNorCovered() {
    EntityManifest matchOnly = mock(EntityManifest.class);
    when(matchOnly.resolution()).thenReturn(null);
    doReturn(List.of(matchOnly)).when(registry).all();

    pass.runIncremental(T, run);

    verifyNoInteractions(coverage);
    verify(pass, never()).resolveEntityIncremental(anyLong(), any(), any(), any());
  }

  /** #904: a source whose table the run did not write cannot hold a row of that run — never read, still covered. */
  @Test
  void aSourceTheRunDidNotWriteIsNeverReadYetItsEntityIsCovered() throws SQLException {
    AgroalDataSource ds = unreadable();
    doReturn(List.of(beta)).when(registry).all();

    pass.runIncremental(T, run, Set.of("connector.t_cov748_alpha"));

    verify(ds, never()).getConnection();
    verify(coverage).cover(T, "beta", List.of(run));
  }

  @Test
  void aSourceTheRunWroteIsRead() throws SQLException {
    AgroalDataSource ds = unreadable();
    doReturn(List.of(beta)).when(registry).all();

    assertThatThrownBy(() -> pass.runIncremental(T, run, Set.of("connector.t_cov748_beta")))
        .hasMessageContaining("t_cov748_beta");

    verify(ds).getConnection();
    verify(coverage, never()).cover(anyLong(), anyString(), any());
  }

  @Test
  void unknownTablesReadEverySource() throws SQLException {
    AgroalDataSource ds = unreadable();
    doReturn(List.of(beta)).when(registry).all();

    assertThatThrownBy(() -> pass.runIncremental(T, run)).hasMessageContaining("t_cov748_beta");

    verify(ds).getConnection();
  }

  /** The pass's admin pool, failing every checkout — so a test can tell whether a source was read. */
  private AgroalDataSource unreadable() throws SQLException {
    AgroalDataSource ds = mock(AgroalDataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("a source was read"));
    pass.adminDs = ds;
    return ds;
  }

  @Test
  void replaySnapshotsBeforeTheScanAndCoversExactlyTheSnapshot() {
    doReturn(alpha).when(registry).findReloadingIfMissing("Alpha");
    List<UUID> snapshot = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(coverage.finishedUncoveredRuns(T, "alpha")).thenReturn(snapshot);
    doReturn(PassSummary.EMPTY).when(pass).resolveEntityReplay(T, alpha);

    pass.runReplay(T, "Alpha");

    InOrder order = inOrder(coverage, pass);
    order.verify(coverage).finishedUncoveredRuns(T, "alpha");
    order.verify(pass).resolveEntityReplay(T, alpha);
    order.verify(coverage).cover(T, "alpha", snapshot);
  }

  @Test
  void aFailedReplayCoversNothing() {
    doReturn(alpha).when(registry).findReloadingIfMissing("Alpha");
    when(coverage.finishedUncoveredRuns(T, "alpha")).thenReturn(List.of(UUID.randomUUID()));
    doThrow(new IllegalStateException("boom")).when(pass).resolveEntityReplay(T, alpha);

    assertThatThrownBy(() -> pass.runReplay(T, "Alpha")).hasMessage("boom");

    verify(coverage, never()).cover(anyLong(), anyString(), any());
  }
}
