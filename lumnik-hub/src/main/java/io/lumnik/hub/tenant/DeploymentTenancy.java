package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.identity.tenant.Tenant;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

/**
 * Whether the hub serves several tenants (#1991): the condition under which writing what every tenant reads needs
 * {@code lm_superadmin} ({@code DeploymentWide}). {@code lumnik.tenancy} ({@code LUMNIK_TENANCY}, Helm
 * {@code tenancy}):
 * <ul>
 *   <li>{@code auto}, the default: multi-tenant once {@code identity.tenant} holds two tenants besides the seeded
 *       {@code hubadmin} one — archived ones included, so a verdict never flips back with the data. Read at every
 *       call, on the database, never cached: a tenant created a moment ago counts at the next write;</li>
 *   <li>{@code multi}: multi-tenant from the start, before the second tenant exists (Helm, cloud);</li>
 *   <li>{@code single}: never — for dev and test, where a test chooses the case it covers. A deployment refuses to
 *       start with it ({@code DeploymentDefaultsGuard}): no production setting makes a hub that counts several
 *       tenants single.</li>
 * </ul>
 * Anything else stops the hub at startup. Under {@code auto} the count and the write it allows are one step against a
 * tenant's creation ({@link #hold}): the count takes {@link Tenant#CREATION_LOCK} shared and keeps it until the write
 * is over, a creation takes it exclusively to its commit. A write never runs on a count a creation has made stale.
 */
@Startup
@ApplicationScoped
public class DeploymentTenancy {

  /** The tenant the shipped realm's hubadmin claims (identity V17): the hub's own, not a customer's. */
  public static final long HUBADMIN_TENANT = 11;

  private static final Logger LOG = Logger.getLogger(DeploymentTenancy.class);

  public enum Mode { AUTO, MULTI, SINGLE }

  /** A pool of its own: a held count never takes a connection the write it allows needs. */
  @Inject
  @DataSource("tenancy")
  AgroalDataSource tenancyDs;

  @ConfigProperty(name = "lumnik.tenancy", defaultValue = "auto")
  String configured;

  private volatile Mode mode;

  @PostConstruct
  void readSetting() {
    mode = parse(configured);
  }

  static Mode parse(String value) {
    return switch (value) {
      case "auto" -> Mode.AUTO;
      case "multi" -> Mode.MULTI;
      case "single" -> Mode.SINGLE;
      default -> throw new IllegalStateException("Invalid value '" + value + "' for lumnik.tenancy: expected exactly "
          + "'auto' or 'multi' ('single' in dev and test only)");
    };
  }

  /**
   * Whether what every tenant reads is written by lm_superadmin only, held until the verdict is closed: under
   * {@code auto}, on a transaction of its own that holds {@link Tenant#CREATION_LOCK} shared — a lock held across a
   * request, which is why this transaction is opened by hand. Close it once the write is over.
   */
  public Verdict hold() {
    if (mode != Mode.AUTO) return new Verdict(mode == Mode.MULTI, null);
    Connection c = null;
    try {
      c = tenancyDs.getConnection();
      c.setAutoCommit(false);
      Jdbc.advisoryXactLockShared(c, "DeploymentTenancy.hold", Tenant.CREATION_LOCK);
      return new Verdict(multiTenant(Jdbc.query(c, "DeploymentTenancy.count", "SELECT id FROM identity.tenant",
          ps -> {}, rs -> rs.getLong(1))), c);
    } catch (SQLException | RuntimeException failure) {
      if (c != null) release(c);
      throw failure instanceof RuntimeException r ? r : new IllegalStateException("DeploymentTenancy.hold", failure);
    }
  }

  /** Ends the transaction, which lets the lock go; a session that cannot end it is ended, and the lock with it. */
  private static void release(Connection held) {
    try (Connection c = held) {
      try {
        c.rollback();
        c.setAutoCommit(true);
      } catch (SQLException failed) {
        Jdbc.endSession(c);
      }
    } catch (SQLException failed) {
      LOG.warnf("deployment tenancy: a held count was not released: %s", failed.getMessage());
    }
  }

  /** A verdict, and the connection that holds it ({@code null} when a setting decides). */
  public record Verdict(boolean multiTenant, Connection held) implements AutoCloseable {

    @Override
    public void close() {
      if (held != null) release(held);
    }
  }

  /** The count itself: two tenants besides hubadmin. A fresh install ({@code default}, {@code hubadmin}) is single. */
  static boolean multiTenant(Collection<Long> tenantIds) {
    return tenantIds.stream().filter(id -> id != HUBADMIN_TENANT).count() >= 2;
  }

  /** A test's case, for the rest of its run: the setting is read once, at startup. */
  void override(Mode m) {
    mode = m;
  }
}
