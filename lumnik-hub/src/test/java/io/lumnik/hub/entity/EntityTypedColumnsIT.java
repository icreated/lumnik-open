package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.hub.ask.AskToSqlAgent;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.export.EntitySnapshotStreamer;
import io.lumnik.hub.export.ExportManifest;
import io.lumnik.hub.export.SnapshotShape;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * A date carried by several sources stays a date once fused (#1325). The same business date lands
 * under three types depending on its source: an Oracle DATE or a PostgreSQL timestamp as
 * {@code timestamptz} (wall clock read as UTC), a PostgreSQL date or a CSV {@code parse-date} with a
 * date-only pattern as {@code date}, an untransformed CSV column as {@code text}. The fused column is
 * {@code date} when every source is, {@code timestamptz} when they mix (a date is midnight UTC), and
 * text as soon as one source is text: a written date is not guessed.
 *
 * <p>Sources: {@code t_ty_erp} plays the ERP (timestamps), {@code t_ty_csv} the parsed CSV (dates),
 * with one column left as the CSV wrote it. {@code ReadonlyTransactionsProfile}: a view reads in UTC by a
 * {@code SET LOCAL} (#1304), which needs the readonly datasource's transaction, as in production.
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class EntityTypedColumnsIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntitySchemaBuilder entityCards;
  @Inject SchemaCardStore cards;
  @Inject AskToSqlAgent agent;
  @Inject EntitySnapshotStreamer streamer;
  @Inject EntityRegistry registry;
  @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
  @InjectMock @Analytic ChatPort chatPort;

  static final String MANIFEST = """
      kind: Entity
      name: TyOrder
      match: [order_no]
      scopes: [ty]
      sources:
        - table: t_ty_erp
          map:
            no: order_no
            at: at
            due: due
            shipped: shipped
            loose: loose
        - table: t_ty_csv
          map:
            code: order_no
            at: at
            due: due
            shipped: shipped
            loose: loose
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_tyorder\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_tyordercal\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_tyship\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_tydayfirst\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_tycaljoin\"");
      st.execute("DROP TABLE IF EXISTS connector.t_ty_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_ty_csv");
      st.execute("CREATE TABLE connector.t_ty_erp (tenant_id bigint NOT NULL, \"no\" text,"
          + " at timestamptz, due timestamptz, shipped date, loose timestamptz)");
      st.execute("CREATE TABLE connector.t_ty_csv (tenant_id bigint NOT NULL, code text,"
          + " at timestamptz, due date, shipped date, loose text)");
      for (String t : new String[]{"t_ty_erp", "t_ty_csv"}) {
        st.execute("DO $$ BEGIN GRANT SELECT ON connector." + t + " TO lumnik_app;"
            + " GRANT SELECT ON connector." + t + " TO lumnik_readonly;"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
      }
      // SO-1: both sources agree on every date, whatever type carries it; `loose` differs only in writing.
      st.execute("INSERT INTO connector.t_ty_erp VALUES (1,'SO-1','2026-07-01 00:00:00.0005+00',"
          + " '2026-07-01 00:00+00', '2026-07-02', '2026-07-03 00:00+00')");
      st.execute("INSERT INTO connector.t_ty_csv VALUES (1,'SO-1','2026-07-01 00:00:00.0005+00',"
          + " '2026-07-01', '2026-07-02', '03/07/2026')");
      // SO-2: a real disagreement on `due`: the ERP says the 5th, the CSV the 4th.
      st.execute("INSERT INTO connector.t_ty_erp VALUES (1,'SO-2','2026-07-04 10:00+00',"
          + " '2026-07-05 08:00+00', '2026-07-06', NULL)");
      st.execute("INSERT INTO connector.t_ty_csv VALUES (1,'SO-2', NULL, '2026-07-04', '2026-07-06', NULL)");
      // SO-4: a time and the day it falls on — the CSV only knows the day, and cannot contradict the hour (#1327).
      st.execute("INSERT INTO connector.t_ty_erp VALUES (1,'SO-4', NULL, '2026-07-08 14:30+00', NULL, NULL)");
      st.execute("INSERT INTO connector.t_ty_csv VALUES (1,'SO-4', NULL, '2026-07-08', NULL, NULL)");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'tyorder'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void theFusedViewKeepsTheTemporalTypeItCanAlign() throws Exception {
    Map<String, String> types = new LinkedHashMap<>();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute"
             + " WHERE attrelid = 'connector.v_tyorder'::regclass AND attnum > 0 ORDER BY attnum")) {
      while (rs.next()) types.put(rs.getString(1), rs.getString(2));
    }
    assertThat(types).containsEntry("order_no", "text")
        .containsEntry("at", "timestamp with time zone")      // timestamptz + timestamptz
        .containsEntry("due", "timestamp with time zone")     // timestamptz + date: midnight UTC
        .containsEntry("shipped", "date")                     // date + date
        .containsEntry("loose", "text");                      // timestamptz + text: not guessed
  }

  @Test
  void anEntityReadWritesTheInstant_andTheSameDayIsNoConflict() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tyorder?order_no=SO-1")
        .then().statusCode(200)
        .body("[0].at", equalTo("2026-07-01T00:00:00.000500Z"))
        .body("[0].due", equalTo("2026-07-01T00:00:00Z"))
        .body("[0].shipped", equalTo("2026-07-02"))
        .body("[0]._conflicts", not(containsString("\"due\"")))
        .body("[0]._conflicts", not(containsString("\"at\"")))
        .body("[0]._conflicts", containsString("\"loose\""));   // text against a timestamp: still compared as written
  }

  @Test
  void aRealDisagreementOnADateIsStillAConflict() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tyorder?order_no=SO-2")
        .then().statusCode(200)
        .body("[0].due", equalTo("2026-07-05T08:00:00Z"))      // the first source's value
        .body("[0]._conflicts", containsString("\"due\""));
  }

  /** #1327: a day and a time on that day agree — a source that only knows the day cannot contradict the hour. */
  @Test
  void aDayAndATimeOnThatDayAgree() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tyorder?order_no=SO-4")
        .then().statusCode(200)
        .body("[0].due", equalTo("2026-07-08T14:30:00Z"))      // the timed source's value, first in order
        .body("[0]._conflicts", equalTo("{}"));
  }

  @Test
  void anEntityReadFilterOnADateIsEqualAsAnInstant() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tyorder?due=2026-07-01")
        .then().statusCode(200).body("order_no", contains("SO-1"));
    given().headers(TestIdentity.tenantAndUser(1)).queryParam("at", "2026-07-01T09:00:00.0005+09:00")
        .get("/api/entities/tyorder")
        .then().statusCode(200).body("order_no", contains("SO-1"));
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tyorder?shipped=2026-07-06")
        .then().statusCode(200).body("order_no", contains("SO-2"));
  }

  @Test
  void theCardTellsAskAndViewsTheRealTypes() {
    Map<String, String> card = entityCards.resolve("ty").get(0).columns().stream()
        .collect(Collectors.toMap(SchemaCard.Column::name, SchemaCard.Column::pgType));
    assertThat(card).containsEntry("at", "timestamp with time zone")
        .containsEntry("due", "timestamp with time zone")
        .containsEntry("shipped", "date")
        .containsEntry("loose", "text")
        .containsEntry("order_no", "text");
  }

  @Test
  void aViewOverTheEntityFiltersADateAsADate() {
    cards.save(1L, "registry", new SchemaCard("ty", entityCards.resolve("ty")));
    String view = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: ty-view }
        spec:
          scope: ty
          table: connector.v_tyorder
          columns: [order_no, due, shipped]
        """;
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
        .queryParam("filter", "due=2026-07-01").body(view)
        .when().post("/api/views/render")
        .then().statusCode(200)
        .body("rows.order_no", contains("SO-1"))
        .body("rows.due", contains("2026-07-01T00:00:00Z"));
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
        .queryParam("filter", "shipped>2026-07-03").body(view)
        .when().post("/api/views/render")
        .then().statusCode(200)
        .body("rows.order_no", contains("SO-2"))
        .body("rows.shipped", contains("2026-07-06"));
  }

  /** An export reads the typed view too: no collation or text[] match on a date, and its text in UTC. */
  @Test
  void anExportWritesADateAsItsUtcText() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/exports/entities/TyOrder/download")
        .then().statusCode(200)
        .body(containsString("\"2026-07-01 00:00:00+00\""))
        .body(containsString("\"2026-07-02\""));
  }

  /** The conflicts door shows a date's ledger as the entity read does: in UTC. */
  @Test
  void theConflictsDoorWritesADatesLedgerInUtc() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("find { it.entity == 'TyOrder' && it.key == 'SO-2' }.conflicts.due.value",
            contains("2026-07-05 08:00:00+00", "2026-07-04 00:00:00+00"));
  }

  /**
   * Ask reads the card's real type and runs in UTC: a model writing {@code due = '2026-07-01'} gets the
   * day's midnight UTC, whatever zone the hub runs in — the row a view filter and an entity read find.
   */
  @Test
  void askSeesADateAndReadsItsLiteralInUtc() {
    cards.save(1L, "ty", new SchemaCard("ty", entityCards.resolve("ty")));
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT order_no FROM connector.v_tyorder WHERE due = '2026-07-01'")
        .thenReturn("SO-1.");

    AskResult r = agent.ask(1L, "ty", "which order is due on 2026-07-01?");

    assertThat(r.rowCount()).isEqualTo(1);
    ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
    Mockito.verify(chatPort, Mockito.atLeastOnce()).chat(prompts.capture());
    assertThat(prompts.getAllValues().get(0)).contains("timestamp with time zone");
  }

  /** A {@code joins:} lookup column keeps its own type: one source, nothing to align. */
  @Test
  void aLookedUpDateStaysADate() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector.t_ty_cal");
      st.execute("CREATE TABLE connector.t_ty_cal (tenant_id bigint NOT NULL, ref text, closing date)");
      st.execute("DO $$ BEGIN GRANT SELECT ON connector.t_ty_cal TO lumnik_app;"
          + " GRANT SELECT ON connector.t_ty_cal TO lumnik_readonly; EXCEPTION WHEN OTHERS THEN NULL; END $$");
      st.execute("INSERT INTO connector.t_ty_cal VALUES (1, 'SO-1', '2026-07-31')");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'tyordercal'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body("""
            kind: Entity
            name: TyOrderCal
            match: [order_no]
            sources:
              - table: t_ty_erp
                map: { no: order_no, due: due }
            joins:
              - table: t_ty_cal
                from: order_no
                to: ref
                map: { closing: closes_on }
            """)
        .post("/api/entities/apply").then().statusCode(200);

    try (Connection c = adminDs.getConnection()) {
      assertThat(EntityViewCompiler.viewColumnTypes(c, "TyOrderCal"))
          .containsEntry("closes_on", "date").containsEntry("due", "timestamp with time zone");
    }
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tyordercal?order_no=SO-1")
        .then().statusCode(200).body("[0].closes_on", equalTo("2026-07-31"));
  }

  /** An export's filter compares a date as an instant, as a view does: the ERP's midnight is the CSV's day. */
  @Test
  void anExportFilterOnADateIsEqualAsAnInstant() {
    List<String> cols = List.of("order_no", "due");
    SnapshotShape shape = new SnapshotShape(cols, cols, SnapshotShape.canonical(cols).formatters(),
        List.of(new ExportManifest.Filter("due", List.of("2026-07-01"))));
    String csv = QuarkusTransaction.requiringNew().call(() -> {
      try (Connection c = readonlyDs.getConnection()) {
        TenantRlsScope.applyTo(c, 1L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamer.stream(c, registry.find("TyOrder"), shape, List.of("due"), ExportManifest.Format.DEFAULT, out);
        return out.toString(StandardCharsets.UTF_8);
      }
    });
    assertThat(csv).contains("\"SO-1\",\"2026-07-01 00:00:00+00\"").doesNotContain("SO-2");
  }

  /** A view's record detail over the entity shows a date's ledger in UTC, as every read (#1325). */
  @Test
  void aViewDetailShowsADatesLedgerInUtc() {
    cards.save(1L, "registry", new SchemaCard("ty", entityCards.resolve("ty")));
    given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
        .queryParam("key", "SO-2").body("""
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata: { name: ty-view }
            spec:
              scope: ty
              table: connector.v_tyorder
              columns: [order_no, due]
            """)
        .when().post("/api/views/detail")
        .then().statusCode(200)
        .body("fields.value", contains("SO-2", "2026-07-05T08:00:00Z"))
        .body("conflicts.find { it.column == 'due' }.sources.value",
            contains("2026-07-05 08:00:00+00", "2026-07-04 00:00:00+00"));
  }

  /** A date match key stays a date (#1326 review): one record per day, its key the date's text, filtered as a date. */
  @Test
  void aDateMatchKeyStaysADate() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'tyship'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body("""
            kind: Entity
            name: TyShip
            match: [shipped]
            sources:
              - table: t_ty_erp
                map: { shipped: shipped, no: erp_no }
              - table: t_ty_csv
                map: { shipped: shipped, code: csv_code }
            """)
        .post("/api/entities/apply").then().statusCode(200);

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      assertThat(EntityViewCompiler.viewColumnTypes(c, "TyShip")).containsEntry("shipped", "date");
      st.execute("SET TimeZone = 'UTC'");
      try (ResultSet rs = st.executeQuery("SELECT shipped::text, \"_canonical_key\" FROM connector.v_tyship ORDER BY shipped")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("2026-07-02");
        assertThat(rs.getString(2)).isEqualTo("1|10:2026-07-02");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("2026-07-06");
        assertThat(rs.next()).as("one record per day, both sources fused").isFalse();
      }
    }
    given().headers(TestIdentity.tenantAndUser(1)).queryParam("shipped", "2026-07-06")
        .get("/api/entities/tyship")
        .then().statusCode(200).body("shipped", contains("2026-07-06"));
  }

  /**
   * Ask's honesty guard compares a date as the query does (#1326 review): {@code due = '2026-07-04'} reads no row —
   * SO-2 kept the 5th at 08:00 — and SO-2 is named as excluded, the CSV holding the 4th.
   */
  @Test
  void askNamesTheRecordADateFilterExcluded() {
    cards.save(1L, "ty", new SchemaCard("ty", entityCards.resolve("ty")));
    Mockito.when(chatPort.chat(Mockito.anyString()))
        .thenReturn("SELECT order_no FROM connector.v_tyorder WHERE due = '2026-07-04'")
        .thenReturn("Aucune.");

    AskResult r = agent.ask(1L, "ty", "which order is due on 2026-07-04?");

    assertThat(r.rowCount()).isZero();
    assertThat(r.disputed()).singleElement().satisfies(d -> {
      assertThat(d.key()).isEqualTo("SO-2");
      assertThat(d.counted()).isFalse();
      assertThat(d.matched()).isEqualTo("2026-07-04 00:00:00+00");
      assertThat(d.retained()).isEqualTo("2026-07-05 08:00:00+00");
    });
  }

  /**
   * #1327 review: a source that only knows the day never hides the hour beside it. Listed first, the CSV's day would
   * have been shown — midnight — and with the ledger empty (they agree on the day) the ERP's 14:30 would be nowhere.
   */
  @Test
  void aDayListedFirstNeverHidesTheHour() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'tydayfirst'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body("""
            kind: Entity
            name: TyDayFirst
            match: [order_no]
            sources:
              - table: t_ty_csv
                map: { code: order_no, due: due }
              - table: t_ty_erp
                map: { no: order_no, due: due }
            """)
        .post("/api/entities/apply").then().statusCode(200);
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tydayfirst?order_no=SO-4")
        .then().statusCode(200)
        .body("[0].due", equalTo("2026-07-08T14:30:00Z"))
        .body("[0]._conflicts", equalTo("{}"));
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/tydayfirst?order_no=SO-2")
        .then().statusCode(200)
        .body("[0].due", equalTo("2026-07-05T08:00:00Z"))       // another day: the hour still shown, the day in the ledger
        .body("[0]._conflicts", containsString("\"due\""));
  }

  /**
   * #1329: a lookup keyed by a day joins a {@code from} the sources hold as a day and as a time. Compared as text, the
   * fused {@code 2026-07-01 00:00:00+00} never met the calendar's {@code 2026-07-01}; a key one side holds as a date is
   * compared at the UTC day, as the fusion compares it (#1327).
   */
  @Test
  void aDayKeyedLookupJoinsAMixedDateKey() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector.t_ty_calendar");
      st.execute("CREATE TABLE connector.t_ty_calendar (tenant_id bigint NOT NULL, day date, label text)");
      st.execute("DO $$ BEGIN GRANT SELECT ON connector.t_ty_calendar TO lumnik_app;"
          + " GRANT SELECT ON connector.t_ty_calendar TO lumnik_readonly; EXCEPTION WHEN OTHERS THEN NULL; END $$");
      st.execute("INSERT INTO connector.t_ty_calendar VALUES (1, '2026-07-01', 'Day A'), (1, '2026-07-08', 'Day B')");
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'tycaljoin'");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body("""
            kind: Entity
            name: TyCalJoin
            match: [order_no]
            sources:
              - table: t_ty_erp
                map: { no: order_no, due: due }
              - table: t_ty_csv
                map: { code: order_no, due: due }
            joins:
              - table: t_ty_calendar
                from: due
                to: day
                map: { label: day_label }
            """)
        .post("/api/entities/apply").then().statusCode(200);

    given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/tycaljoin?order_no=SO-1")
        .then().statusCode(200).body("[0].day_label", equalTo("Day A"));       // midnight on both sides
    given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/tycaljoin?order_no=SO-4")
        .then().statusCode(200).body("[0].day_label", equalTo("Day B"));       // 14:30 that day
    given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/tycaljoin?order_no=SO-2")
        .then().statusCode(200).body("[0].day_label", nullValue());            // the 5th: no calendar row
  }
}
