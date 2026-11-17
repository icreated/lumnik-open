package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.LockWaits;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.hub.tenant.DeploymentTenancy.Mode;
import io.lumnik.hub.tenant.DeploymentTenancy.Verdict;
import io.lumnik.identity.tenant.Tenant;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * #1991, #2016: on a hub serving several tenants, what every tenant reads — Entity, View and App manifests, the
 * platform corpus — is written by lm_superadmin only. A tenant's lm_admin or lm_integrator holds a role of their tenant,
 * and would otherwise rewrite or delete another tenant's. With one tenant, nothing changes. Real signed JWTs.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class DeploymentWideDoorsIT {

  private static final String REFUSAL = "lm_superadmin";
  private static final long EXTRA_TENANT = 19910;
  private static final String CREATED_CODE = "t1991created";
  /** A tenant's admin: every role a tenant grants, no scope. */
  private static final Set<String> TENANT_ADMIN = Set.of("lm_admin", "lm_integrator", "lm_user");
  /** The integrator who serves a tenant, given lm_superadmin as well: the doors' roles still apply. */
  private static final Set<String> SUPERADMIN = Set.of("lm_admin", "lm_integrator", "lm_user", "lm_superadmin");

  @Inject DeploymentTenancy tenancy;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject
  @DataSource("tenancy")
  AgroalDataSource tenancyDs;

  @AfterEach
  void restore() throws Exception {
    tenancy.override(Mode.SINGLE);
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM identity.tenant WHERE id = " + EXTRA_TENANT + " OR code = '" + CREATED_CODE + "'");
    }
  }

  /** Every global write: its method, its path, and a body when it takes one. */
  static Stream<Arguments> doors() {
    return Stream.of(
        Arguments.of("POST", "/api/entities/apply", "kind: Entity"),
        Arguments.of("DELETE", "/api/entities/nothing1991", null),
        Arguments.of("POST", "/api/views/apply", "kind: View"),
        Arguments.of("DELETE", "/api/views/nothing1991", null),
        Arguments.of("POST", "/api/apps/apply", "kind: App"),
        Arguments.of("DELETE", "/api/apps/nothing1991", null),
        Arguments.of("POST", "/api/platform/rag/reindex", null));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("doors")
  void a_tenant_admin_is_refused_on_a_multi_tenant_hub(String method, String path, String body) {
    tenancy.override(Mode.MULTI);

    send(TENANT_ADMIN, method, path, body).then().statusCode(403).body("error", containsString(REFUSAL));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("doors")
  void a_superadmin_passes_on_a_multi_tenant_hub(String method, String path, String body) {
    tenancy.override(Mode.MULTI);

    send(SUPERADMIN, method, path, body).then().statusCode(not(403));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("doors")
  void a_tenant_admin_is_unchanged_on_a_single_tenant_hub(String method, String path, String body) {
    tenancy.override(Mode.SINGLE);

    send(TENANT_ADMIN, method, path, body).then().statusCode(not(403));
  }

  /** #2050: what is written from the hub's own tenant is shared with every tenant, on a single-tenant hub too. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("doors")
  void a_tenant_admin_of_the_hubs_own_tenant_is_refused(String method, String path, String body) {
    tenancy.override(Mode.SINGLE);

    send(TENANT_ADMIN, DeploymentTenancy.HUBADMIN_TENANT, method, path, body).then().statusCode(403)
        .body("error", containsString("hub's own tenant"));
  }

  /** A caller the door refuses anyway is answered by its role gate: nothing told about the tenancy. */
  @Test
  void a_caller_without_the_doors_role_learns_nothing_of_the_tenancy() {
    tenancy.override(Mode.MULTI);

    send(Set.of("lm_user"), "DELETE", "/api/entities/nothing1991", null).then().statusCode(403)
        .body(not(containsString(REFUSAL)));
  }

  /** Detection: a tenant beyond the seeded ones makes the hub multi-tenant, read at the write, with no cache. */
  @Test
  void a_second_customer_tenant_turns_the_rule_on_at_the_next_write() throws Exception {
    tenancy.override(Mode.AUTO);
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO identity.tenant (id, code, name, created_at, updated_at) "
          + "VALUES (" + EXTRA_TENANT + ", 't1991', 'Second customer', now(), now()) ON CONFLICT (id) DO NOTHING");
    }

    send(TENANT_ADMIN, "DELETE", "/api/entities/nothing1991", null).then().statusCode(403)
        .body("error", containsString(REFUSAL));
  }

  /**
   * The count and the write it allows are one step against a tenant's creation: a write arriving while a tenant is
   * being created waits for its commit before it counts. Never a write allowed by a count a creation has made stale.
   */
  @Test
  void a_write_waits_for_a_tenant_being_created() throws Exception {
    tenancy.override(Mode.AUTO);
    CompletableFuture<Integer> write;
    try (Connection creating = adminDs.getConnection()) {
      creating.setAutoCommit(false);
      try {
        Jdbc.advisoryXactLock(creating, "test.creating", Tenant.CREATION_LOCK);
        Jdbc.update(creating, "test.insert", "INSERT INTO identity.tenant (id, code, name, created_at, updated_at) "
            + "VALUES (" + EXTRA_TENANT + ", 't1991', 'Second customer', now(), now())", ps -> {});
        write = CompletableFuture.supplyAsync(
            () -> send(TENANT_ADMIN, "DELETE", "/api/entities/nothing1991", null).statusCode());
        LockWaits.awaitBlockedBy(adminDs, creating);
        creating.commit();
      } finally {
        creating.rollback();   // nothing left once committed; an open transaction given back would be committed
        creating.setAutoCommit(true);
      }
    }
    assertThat(write.get(30, TimeUnit.SECONDS)).isEqualTo(403);
    assertNoLockLeftBehind();
  }

  /** The other side: a tenant's creation waits for a deployment-wide write in flight to end. */
  @Test
  void a_tenant_creation_waits_for_a_write_in_flight() throws Exception {
    CompletableFuture<Integer> create;
    try (Connection writing = adminDs.getConnection()) {
      writing.setAutoCommit(false);
      try {
        Jdbc.advisoryXactLockShared(writing, "test.writing", Tenant.CREATION_LOCK);
        create = CompletableFuture.supplyAsync(() -> given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(SUPERADMIN, null))
            .contentType("application/json").body("{\"code\":\"" + CREATED_CODE + "\",\"name\":\"Created\"}")
            .post("/api/tenants").statusCode());
        LockWaits.awaitBlockedBy(adminDs, writing);
      } finally {
        writing.rollback();
        writing.setAutoCommit(true);
      }
    }
    assertThat(create.get(30, TimeUnit.SECONDS)).isEqualTo(201);
  }

  /**
   * A write lets its lock go with its response: a creation is never left waiting. This database counts several
   * tenants, so the refusal is the case run here; {@code DeploymentWideInterceptorTest} holds the allowed one.
   */
  @Test
  void a_write_leaves_no_lock_behind() {
    tenancy.override(Mode.AUTO);
    send(TENANT_ADMIN, "DELETE", "/api/entities/nothing1991", null);
    send(TENANT_ADMIN, "POST", "/api/entities/apply", "kind: Entity");
    assertNoLockLeftBehind();
    assertThat(tenancyDs.getMetrics().activeCount()).as("held counts still open").isZero();
  }

  /**
   * A held count takes no connection the write needs: global writes as many as the admin pool holds would otherwise
   * each keep one and wait for another, and none would end.
   */
  @Test
  void a_held_count_leaves_the_admin_pool_whole() throws Exception {
    tenancy.override(Mode.AUTO);
    List<Connection> taken = new ArrayList<>();
    try (Verdict held = tenancy.hold()) {
      for (int i = 0; i < adminDs.getConfiguration().connectionPoolConfiguration().maxSize(); i++) {
        taken.add(adminDs.getConnection());
      }
    } finally {
      for (Connection c : taken) c.close();
    }
  }

  private void assertNoLockLeftBehind() {
    // On its own statement's transaction: a lock taken there goes with it.
    assertThat(Jdbc.queryOne(adminDs, "test.free", "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
        ps -> ps.setString(1, Tenant.CREATION_LOCK), rs -> rs.getBoolean(1))).as("a creation would get the lock at once").contains(true);
  }

  private static Response send(Set<String> roles, String method, String path, String body) {
    return send(roles, 1L, method, path, body);
  }

  private static Response send(Set<String> roles, long tenant, String method, String path, String body) {
    RequestSpecification r = given().config(YamlBody.CONFIG)
        .header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null, tenant));
    if (body != null) r = r.contentType("application/yaml").body(body);
    return r.request(method, path);
  }
}
