package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1185: a {@code connector.t_*} table the hub wrote is insert-only — a changed source row is a NEW row
 * beside its old version, under a newer {@code _run_id}. The entity reads, per source and per key, only
 * the rows of the newest run that brought that key: the update wins, the old version is not a conflict
 * of the source with itself, and a changed lookup row no longer makes its key ambiguous. Rows a single
 * run brought twice stay a genuine disagreement. A table with no {@code _run_id} (a hybrid REST target,
 * an upsert already) is read as before.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityLatestRunIT {

  private static final String RUN1 = "00000000-0000-0000-0000-000000001185";
  private static final String RUN2 = "00000000-0000-0000-0000-000000002185";

  private static final String ERP = "t_lr_erp (code, ville, fam)";
  private static final String FAM = "t_lr_fam (fk, libelle)";
  private static final String FUSED = "ville || '|' || famille";

  @Inject EntityViewCompiler compiler;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @AfterEach
  void cleanup() throws Exception {
    exec("DROP VIEW IF EXISTS connector.v_latestrun", "DROP VIEW IF EXISTS connector.v_latestres",
        "DROP TABLE IF EXISTS connector.t_lr_erp, connector.t_lr_crm, connector.t_lr_fam",
        "DELETE FROM connector.entity_manifest WHERE name IN ('latestrun', 'latestres')");
  }

  @Test
  void the_newest_run_of_a_key_wins_and_its_old_version_is_no_conflict() throws Exception {
    exec(history("t_lr_erp", "code", "ville", "fam"),
        "CREATE TABLE connector.t_lr_crm (tenant_id bigint NOT NULL, code text, ville text)",
        history("t_lr_fam", "fk", "libelle"),
        // run 1, then run 2 changes C1's town, renames family F1 and brings C3 twice with two towns
        insert(ERP, RUN1, "C1", "Grenoble", "F1"), insert(ERP, RUN1, "C2", "Lyon", "F1"),
        insert(FAM, RUN1, "F1", "Visserie"),
        insert(ERP, RUN2, "C1", "Valence", "F1"),
        insert(FAM, RUN2, "F1", "Boulonnerie"),
        insert(ERP, RUN2, "C3", "Annecy", "F1"), insert(ERP, RUN2, "C3", "Albertville", "F1"),
        "INSERT INTO connector.t_lr_crm VALUES (1, 'C1', 'Valence')");
    apply("""
        kind: Entity
        name: LatestRun
        match: [code]
        sources:
          - table: t_lr_erp
            map: { code: code, ville: ville, fam: fam }
          - table: t_lr_crm
            map: { code: code, ville: ville }
        joins:
          - table: t_lr_fam
            from: fam
            to: fk
            map: { libelle: famille }
        """);

    assertThat(row("v_latestrun", FUSED, "code = 'C1'")).isEqualTo("Valence|Boulonnerie|{}");
    assertThat(row("v_latestrun", FUSED, "code = 'C2'")).as("a key run 2 did not bring keeps run 1's row")
        .isEqualTo("Lyon|Boulonnerie|{}");
    assertThat(row("v_latestrun", FUSED, "code = 'C3'")).as("two rows of ONE run still disagree")
        .startsWith("Albertville|Boulonnerie|{\"ville\":");
  }

  @Test
  void a_lookup_key_that_changes_shape_between_runs_is_still_one_key() throws Exception {
    // the join compares numeric-shaped keys by value (7.0 = 7), so the newest run is picked per value too
    exec(history("t_lr_erp", "code", "ville", "fam"), history("t_lr_fam", "fk", "libelle"),
        insert(ERP, RUN1, "C1", "Valence", "7"),
        insert(FAM, RUN1, "7.0", "Visserie"), insert(FAM, RUN2, "7", "Boulonnerie"),
        "CREATE TABLE connector.t_lr_crm (tenant_id bigint NOT NULL, code text, ville text)");
    apply("""
        kind: Entity
        name: LatestRun
        match: [code]
        sources:
          - table: t_lr_erp
            map: { code: code, ville: ville, fam: fam }
          - table: t_lr_crm
            map: { code: code, ville: ville }
        joins:
          - table: t_lr_fam
            from: fam
            to: fk
            map: { libelle: famille }
        """);

    assertThat(row("v_latestrun", FUSED, "code = 'C1'")).isEqualTo("Valence|Boulonnerie|{}");
  }

  @Test
  void a_resolution_entity_reads_the_newest_run_of_each_identity() throws Exception {
    exec(history("t_lr_erp", "code", "ville", "fam"),
        insert(ERP, RUN1, "C1", "Grenoble", "F1"), insert(ERP, RUN2, "C1", "Valence", "F1"));
    apply("""
        kind: Entity
        name: LatestRes
        sources:
          - table: t_lr_erp
            map: { code: code, ville: ville }
        resolution:
          identity:
            t_lr_erp: code
          cascade:
            - id: by-code
              exact: { fields: [code] }
        """);

    assertThat(row("v_latestres", "ville", "_handle = 't_lr_erp:C1'")).isEqualTo("Valence|{}");
  }

  /** A table shaped like the ones {@code DynamicTableWriter} creates: its system prelude, then text columns. */
  private static String history(String table, String... cols) {
    StringBuilder ddl = new StringBuilder("CREATE TABLE connector.").append(table)
        .append(" (id bigserial PRIMARY KEY, _run_id uuid, tenant_id bigint NOT NULL");
    for (String c : cols) ddl.append(", ").append(c).append(" text");
    return ddl.append(')').toString();
  }

  /** {@code into} is the table and its columns, e.g. {@code t_lr_fam (fk, libelle)}. */
  private static String insert(String into, String run, String... values) {
    int open = into.indexOf('(');
    return "INSERT INTO connector." + into.substring(0, open) + "(_run_id, tenant_id, " + into.substring(open + 1)
        + " VALUES ('" + run + "', 1, '" + String.join("', '", values) + "')";
  }

  private void apply(String yaml) {
    assertThat(compiler.apply(new EntityManifestParser().parse(yaml), yaml, null))
        .isInstanceOf(ScopeGuardedUpsert.Applied.class);
  }

  /** {@code cols} and the row's ledger, joined by {@code |}. */
  private String row(String view, String cols, String where) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT " + cols + " || '|' || _conflicts::text FROM connector." + view
             + " WHERE " + where)) {
      assertThat(rs.next()).as(view + " has a row where " + where).isTrue();
      String out = rs.getString(1);
      assertThat(rs.next()).as("one row only").isFalse();
      return out;
    }
  }

  private void exec(String... sql) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }
}
