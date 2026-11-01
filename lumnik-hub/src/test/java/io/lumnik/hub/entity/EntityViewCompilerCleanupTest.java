package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.error.Throwables;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * apply, delete and reshape run on {@code Jdbc.transaction}: its cleanup never hides the failure that ended the
 * transaction (#1607), and never commits what it held (#1649).
 */
class EntityViewCompilerCleanupTest {

  private static final EntityManifest CUSTOMER = new EntityManifestParser().parse("""
      kind: Entity
      name: Customer
      match: [id]
      sources:
        - table: t_cust_src
          map: { ROW_ID: id }
      """);

  private final EntityViewCompiler compiler = new EntityViewCompiler();
  private final Connection c = mock(Connection.class);
  /** The session beneath the pool's wrapper: what ending it closes. */
  private final Connection physical = mock(Connection.class);

  @BeforeEach
  void wire() throws SQLException {
    compiler.adminDs = mock(AgroalDataSource.class);
    when(compiler.adminDs.getConnection()).thenReturn(c);
    when(c.getAutoCommit()).thenReturn(true);
    when(c.unwrap(Connection.class)).thenReturn(physical);
  }

  /** The compiler's three transactions of its own. */
  enum Operation { APPLY, DELETE, RESHAPE }

  private void run(Operation op) {
    switch (op) {
      case APPLY -> compiler.apply(CUSTOMER, "yaml", null);
      case DELETE -> compiler.delete("Customer");
      case RESHAPE -> compiler.reshape(c, "connector", "t_cust_src", List.of("ROW_ID"), conn -> {});
    }
  }

  /** The first statement on the connection — the entity lock, or reshape's walk — fails with {@code failure}. */
  private void firstStatementFails(Throwable failure) throws SQLException {
    when(c.prepareStatement(anyString())).thenThrow(failure);
  }

  /**
   * #1649: back to autocommit after a rollback that failed, pgjdbc COMMITs whatever the transaction still held — and
   * so does the pool's own reset when the connection goes back. The session is ended instead: the server rolls back.
   * The failure that ended the transaction stays the one thrown, the rollback's riding on it.
   */
  @ParameterizedTest
  @EnumSource(Operation.class)
  void a_failed_rollback_ends_the_session_instead_of_committing(Operation op) throws SQLException {
    SQLException boom = new SQLException("boom");
    firstStatementFails(boom);
    SQLException rollback = new SQLException("connection gone");
    doThrow(rollback).when(c).rollback();

    assertThatThrownBy(() -> run(op))
        .hasRootCause(boom)
        .satisfies(t -> assertThat(Throwables.causeChain(t)).flatMap(cause -> List.of(cause.getSuppressed()))
            .containsExactly(rollback));
    verify(c, never()).setAutoCommit(true);
    verify(physical).close();
  }

  @ParameterizedTest
  @EnumSource(Operation.class)
  void an_error_inside_the_transaction_is_rolled_back(Operation op) throws SQLException {
    firstStatementFails(new StackOverflowError());

    assertThatThrownBy(() -> run(op)).isInstanceOf(StackOverflowError.class);
    verify(c).rollback();
  }

  @Test
  void a_failed_rollback_keeps_apply_s_precise_refusal() throws SQLException {
    firstStatementFails(new SQLException("ERROR: relation \"connector.t_cust_src\" does not exist", "42P01"));
    SQLException rollback = new SQLException("connection gone");
    doThrow(rollback).when(c).rollback();

    assertThatThrownBy(() -> compiler.apply(CUSTOMER, "yaml", null))
        .isInstanceOf(MissingSourceTableException.class)
        .satisfies(t -> assertThat(Throwables.causeChain(t)).flatMap(cause -> List.of(cause.getSuppressed()))
            .containsExactly(rollback));
  }
}
