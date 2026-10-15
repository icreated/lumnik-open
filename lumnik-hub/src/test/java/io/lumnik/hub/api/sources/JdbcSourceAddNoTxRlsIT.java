package io.lumnik.hub.api.sources;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.jdbc.SchemaIntrospector;
import io.lumnik.hub.connector.jdbc.TableDescriptor;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EventItSupport;
import io.lumnik.jdbc.spi.ColumnDescriptor;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The guided add introspects every requested table on the customer's database — several catalog
 * round trips each, and {@code lm}'s interactive picker sends every picked table in one request.
 * The door used to be {@code @Transactional}, so one default-pool connection was held for the
 * whole introspection, and past the transaction timeout the add rolled back (#909). The existence
 * check and the writes now run in short transactions of their own, the introspection between them
 * with nothing open.
 *
 * <p>Under the RLS-enforced profile on purpose: a read or a write that slipped out of its short
 * transaction would lose the {@code SET LOCAL} tenant binding — the existing connector would not
 * be seen, or the insert would fail the policy — and the owner-connected default profile could not
 * tell.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class JdbcSourceAddNoTxRlsIT {

  private static final String SOURCE_PREFIX = "ntx-add-";

  @InjectMock SchemaIntrospector introspector;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;   // the owner, so the teardown reaches past RLS

  @AfterEach
  void purge() {
    EventItSupport.execSql(adminDs, "DELETE FROM connector.connector WHERE name LIKE '" + SOURCE_PREFIX + "%'");
    EventItSupport.execSql(adminDs, "DELETE FROM connector.connector_credential WHERE name LIKE '" + SOURCE_PREFIX + "%'");
  }

  @Test
  void tables_are_introspected_with_no_transaction_held() {
    List<Boolean> txActiveAtDescribe = new CopyOnWriteArrayList<>();
    when(introspector.describe(any(), anyString(), anyString())).thenAnswer(inv -> {
      txActiveAtDescribe.add(QuarkusTransaction.isActive());
      return new TableDescriptor("public", inv.getArgument(2), List.of("id"),
          List.of(new ColumnDescriptor("id", "BIGINT", 19, false)), List.of(), Optional.empty(), 0L, List.of());
    });
    Map<String, Object> body = Map.of("sourceName", SOURCE_PREFIX + System.nanoTime(),
        "url", "jdbc:postgresql://localhost:1/source", "user", "u", "password", "p",
        "tables", List.of(Map.of("table", "orders")));

    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(body)
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200).body("created.size()", is(1));

    assertThat(txActiveAtDescribe)
        .as("the add must not hold a JTA transaction (and its pooled connection) across the introspection")
        .containsExactly(false);

    // The second add finds the connector the first one wrote — through RLS — and skips it without
    // asking the customer's catalog again: the existence check still comes before introspection.
    given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(body)
        .when().post("/api/platform/sources/jdbc/add")
        .then().statusCode(200).body("created.size()", is(0)).body("skipped.size()", is(1));

    assertThat(txActiveAtDescribe).as("an existing connector's table is not introspected again").hasSize(1);
  }
}
